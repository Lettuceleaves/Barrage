
# Barrage项目架构白皮书：基于JDK 25与io\_uring的高性能压测引擎设计与实施方案

## 1\. 执行摘要与项目愿景

__Barrage__ 项目旨在构建下一代开源压力测试引擎，通过彻底革新底层I/O模型与内存管理策略，解决传统基于JVM的压测工具（如JMeter、Gatling）在高并发场景下因垃圾回收（GC）停顿和线程上下文切换导致的性能抖动问题。本项目立足于2026年的技术前沿，利用 __JDK 25__ 的正式版特性（Project Loom虚拟线程、Project Panama外部函数与内存API）与Linux内核的 __io\_uring__ 异步I/O子系统，实现“零拷贝（Zero\-Copy）”与“零GC（Zero\-GC）”的极致性能目标。

传统的压测工具往往受限于Java堆内存的管理开销，在高负载下，GC暂停时间（Stop\-The\-World）会严重扭曲测试结果，导致尾部延迟（P99/P999）数据失真。Barrage通过引入\*\*堆外内存架构（Off\-Heap Architecture）\*\*用于负载生成阶段，并结合JDK 25的 __ZGC__ 用于数据分析阶段，创造性地分离了“压测”与“分析”的内存生命周期，从而在保持Java语言开发效率的同时，达到接近C\+\+/Rust原生实现的吞吐量。

本报告将详细阐述Barrage的技术可行性、架构设计、模块拆分及MVP（最小可行性产品）的实施路径，旨在为开源社区提供一份详尽的工程蓝图，以在极短时间内（24小时内）交付具有震撼力的首个版本，吸引全球开发者参与。

## 2\. 技术选型深度解析与可行性分析

### 2\.1 核心运行时：JDK 25 \(Oracle OpenJDK\)

JDK 25作为Barrage的基石，其选择并非仅仅为了追求版本号的新颖，而是基于其包含的若干关键特性，这些特性是实现Barrage“高性能”与“开发体验”并存的先决条件。

#### 2\.1\.1 虚拟线程 \(Virtual Threads\) 与并发模型

JDK 25标志着Project Loom的完全成熟。在Barrage中，虚拟线程用于模拟每一个并发用户 1。

- __技术优势__：传统压测工具依赖线程池或异步回调（Reactive）模型。线程池受限于操作系统线程数，上下文切换开销大；异步回调虽然性能高，但代码逻辑破碎，难以编写复杂的业务场景脚本。虚拟线程允许我们以同步阻塞的代码风格（Thread\-per\-Request）编写压测脚本，底层却由JVM自动挂起和调度，实现了代码的可读性与运行时的高并发性统一 2。
- __Barrage应用__：每个虚拟线程代表一个“User”，执行 Connect \-> Send \-> Receive \-> Think 的循环。当执行到I/O操作时，虚拟线程自动卸载（Unmount），仅消耗极少的堆内存（约几百字节），使得单机模拟百万级并发连接成为可能。
- __可行性__：JDK 25已于2025年9月发布GA，虚拟线程调度器经过多个版本的迭代，解决了早期的Pinning（载体线程钉住）问题，特别是对 synchronized 块的兼容性大幅提升 3。

#### 2\.1\.2 Project Panama \(FFM API\)

外部函数与内存API（Foreign Function & Memory API）是实现“零GC”的核心 5。

- __技术优势__：FFM API提供了一套标准的Java API（Linker、MemorySegment、Arena），用于替代脆弱且难以维护的JNI（Java Native Interface）。它允许Java代码安全、高效地访问堆外内存（Off\-Heap Memory）并调用本地代码（如 liburing）。
- __Barrage应用__：
	- __内存管理__：使用 Arena 分配不受GC管控的内存段，用于存放HTTP请求报文、io\_uring的提交队列（SQ）和完成队列（CQ）6。
	- __本地调用__：通过 Linker\.nativeLinker\(\) 直接调用Linux内核的系统调用封装函数，无需编写一行C/C\+\+胶水代码，极大降低了维护成本 7。

#### 2\.1\.3 ZGC \(The Z Garbage Collector\)

虽然压测期追求0GC，但在数据分析阶段（压测结束后），系统需要处理海量的统计数据（Histogram、Percentiles）。

- __技术优势__：ZGC在JDK 25中已更加成熟，支持分代收集（Generational ZGC），能够处理TB级的堆内存且保持亚毫秒级的暂停时间 1。
- __Barrage应用__：在压测停止后，系统从堆外内存读取计数器，转换为Java对象进行聚合分析。此时开启ZGC，确保即使在生成复杂HTML报告时，CLI界面和API响应依然流畅，不会出现卡顿。

### 2\.2 核心I/O引擎：Linux io\_uring

选择 io\_uring 而非 epoll 是Barrage性能超越传统工具的关键。

#### 2\.2\.1 异步I/O与零拷贝

io\_uring 是Linux 5\.1引入的全新异步I/O接口，通过共享内存环（Ring Buffer）在用户态和内核态之间传递I/O请求，彻底消除了系统调用的开销 10。

- __核心机制__：
	- __SQ \(Submission Queue\)__：用户态生产者，内核态消费者。Barrage将“发送请求”指令写入SQ。
	- __CQ \(Completion Queue\)__：内核态生产者，用户态消费者。内核完成I/O后将结果写入CQ。
- __零拷贝实现__：Barrage将利用 IORING\_OP\_SEND\_ZC 操作码 12。传统 SocketChannel\.write 涉及数据从用户堆内存拷贝到内核Socket缓冲区的过程。IORING\_OP\_SEND\_ZC 允许内核直接读取用户态注册的堆外内存（MemorySegment），直接通过DMA发送到网卡，实现真正的零拷贝 14。
- __回环优化 \(Loopback\)__：针对本地开发测试（Loopback），io\_uring 支持开启 IORING\_SETUP\_SQPOLL 特性。内核会启动一个内核线程轮询SQ，意味着Java层提交请求甚至不需要执行 io\_uring\_enter 系统调用，完全是内存读写操作，极致降低了CPU占用 16。

### 2\.3 编译与分发：GraalVM Native Image

- __选型__：GraalVM Community Edition 25\.0\.1 17。
- __必要性__：为了方便开发者使用，Barrage必须分发为独立的二进制可执行文件，无需用户安装JDK。Native Image支持将Java字节码编译为原生机器码，提供毫秒级启动速度和更低的内存占用 18。
- __挑战与解决__：Native Image对反射支持有限。Barrage将利用Maven插件和GitHub Actions中的Tracing Agent自动生成反射配置文件，确保第三方库的兼容性 19。

### 2\.4 构建与CICD：Maven & GitHub Actions

- __Maven 3\.9\.1__：成熟稳定，能够很好地处理模块化项目（Multi\-module）和Native Image插件配置 21。
- __GitHub Actions__：
	- __自动化测试__：集成Docker Service运行实际的Nginx容器作为靶机，验证压测逻辑。
	- __文档自动化__：利用Action自动翻译 README\.md 为中文，满足全球化推广需求 23。
	- __Javadoc发布__：自动生成并在GitHub Pages发布API文档。

## 3\. 架构设计与模块拆分

为了实现“极强的可扩展性避免近期重构”，Barrage采用 __六边形架构 \(Hexagonal Architecture\)__，将核心业务逻辑（压测调度）与底层适配器（I/O模型、协议实现、CLI界面）分离。

### 3\.1 总体架构图

代码段

graph TD

    CLI[barrage-cli] --> Engine[barrage-engine]
    Engine --> API[barrage-api]
    Engine --> Kernel[barrage-kernel]
    Engine --> Buffers[barrage-buffers]
    Engine --> Protocol[barrage-protocol-http]

    subgraph Core Domain
        Engine
        Buffers
    end

    subgraph Infrastructure
        Kernel(FFM & io_uring bindings)
        CLI(Picocli & GraalVM Config)
    end


### 3\.2 模块拆分详细说明

项目根目录 barrage-parent (pom.xml) 管理所有子模块版本。

#### 3\.2\.1 barrage\-api \(核心接口层\)

- __职责__：定义所有扩展接口，不依赖任何具体实现，无第三方依赖。
- __关键接口__：
	- LoadGenerator：负载发生器接口。
	- Scenario：压测场景定义接口。
	- MetricsCollector：指标收集回调接口。
	- ProtocolAdapter：协议适配器接口（用于未来扩展gRPC, QUIC）。

#### 3\.2\.2 barrage\-kernel \(系统内核层\)

- __职责__：封装所有 Unsafe 和 FFM 操作，提供对 io\_uring 的面向对象封装。这是系统的“脏活累活”层。
- __关键组件__：
	- IoUringRing：管理 SQ/CQ 环形缓冲区的内存映射。
	- NativeLinker：持有 io\_uring\_setup, io\_uring\_submit, io\_uring\_register\_buffers 等原生函数的 MethodHandle 7。
	- Ops：定义 IORING\_OP\_SEND\_ZC, IORING\_OP\_RECV 等操作码常量。

#### 3\.2\.3 barrage\-buffers \(内存管理层\)

- __职责__：实现 0GC 的核心。管理堆外内存池。
- __设计模式__：采用 __Arena Allocator__ 和 __Object Pool__ 结合。
- __关键组件__：
	- OffHeapRingBuffer：针对高并发写入优化的环形缓冲，用于存储待发送的请求数据。
	- FlyweightRequest：享元模式的请求对象，不持有数据，只持有堆外内存的地址指针（long address），避免Java对象分配。

#### 3\.2\.4 barrage\-engine \(调度引擎层\)

- __职责__：连接内核与业务，管理虚拟线程生命周期。
- __关键逻辑__：
	- __Virtual Thread Scheduler__：启动数千个虚拟线程，每个线程代表一个User。
	- __Poller Thread__：一个独立的载体线程（Carrier Thread），负责轮询 io\_uring 的 CQ（完成队列）。当I/O完成时，通过 LockSupport\.unpark\(\) 唤醒对应的虚拟线程 25。

#### 3\.2\.5 barrage\-protocol\-http \(协议扩展层\)

- __职责__：实现HTTP协议的编码与解码，直接操作 MemorySegment。
- __特性__：不使用Netty或Tomcat等重型框架，而是手写轻量级HTTP解析器，直接在堆外内存中解析状态码和Header，避免将字节数组拷贝为Java String 26。

#### 3\.2\.6 barrage\-cli \(交互层\)

- __职责__：解析命令行参数，启动Native Image，渲染实时TUI（文本UI）。
- __依赖__：info\.picocli:picocli。

## 4\. 关键技术实现细节 \(The "Secret Sauce"\)

本章节深入探讨如何利用JDK 25特性满足“零GC”和“零拷贝”的具体代码级策略。

### 4\.1 堆外内存管理与零GC实现

在压测期间（Attack Phase），JVM堆内存分配率必须严格为零。

- 内存布局 \(Memory Layout\)：  
使用 Arena\.ofShared\(\) 分配一块巨大的连续堆外内存（例如 4GB）。这块内存被逻辑切分为多个 Slot，每个虚拟线程绑定一个 Slot。  
Java  
// 伪代码示例：堆外内存分配  
try Arena arena = Arena.ofShared() {  
    MemorySegment masterSegment = arena\.allocate(4L \* 1024 \* 1024 \* 1024); // 4GB  
    // 将masterSegment切片分配给连接池  
}  
  
引用来源：5 提到 Arena API 提供了确定性的资源管理，ofShared 支持多线程访问。
- 享元模式 \(Flyweight Pattern\)：  
Java中的 HttpRequest 对象不再存储 byte body 或 String url。相反，它仅存储 long baseAddress 和 long length。所有的读写操作直接通过 Linker 传递地址给内核。这样，即使有一百万个并发请求，Java堆中也只有一百万个极小的指针对象，且这些对象在压测初始化阶段就预分配好，压测过程中反复复用，不产生新垃圾。
- 无锁指标记录 (Lock\-free Metrics)：  
使用 VarHandle 直接对堆外内存中的计数器进行原子累加（CAS）。  
Java  
// 伪代码：堆外计数器  
private static final VarHandle VH\_LONG = MethodHandles\.lookup\(\)\.findVarHandle\(long\.class\);  
void recordLatency\(long latency\) \{  
    // 直接在堆外内存地址 offset 处原子增加  
    VH\_LONG\.getAndAdd\(offHeapMetricsAddress, offset, 1\);  
\}  
  
这种方式避免了创建 LongAdder 或 AtomicLong 对象带来的开销，且对CPU缓存更加友好。

### 4\.2 io\_uring 零拷贝与回环优化

- IORING\_OP\_SEND\_ZC 实现：  
这是 Barrage 区别于其他压测工具的核心。通过 FFM API，我们构建 io\_uring\_sqe 结构体：
	- opcode: 设置为 IORING\_OP\_SEND\_ZC。
	- addr: 设置为 MemorySegment 的物理地址（通过 MemorySegment\.address\(\) 获取）。
	- flags: 设置 IORING\_RECVSEND\_FIXED\_BUF，表明我们使用的是预注册的缓冲区。

在启动阶段，调用 io\_uring\_register\_buffers 将整个 4GB Arena 注册给内核。这样内核在处理 I/O 时，直接通过索引访问物理页，完全跳过虚拟内存映射和数据拷贝 12。

- SQPOLL \(Submission Queue Polling\)：  
针对用户提到的“回环请求”，我们在 io\_uring\_setup 时传入 IORING\_SETUP\_SQPOLL 标志。这会指示内核启动一个内核线程（Kernel Thread）专门轮询 SQ 环。
	- __效果__：Java端的虚拟线程只需将请求写入内存映射区域（SQ Ring），然后更新尾指针（Tail），__不需要__ 调用 io\_uring\_enter 系统调用来通知内核。
	- __收益__：在 Loopback 场景下，系统调用是主要的性能杀手。消除系统调用后，Barrage 可以跑满 CPU 的内存带宽限制，而非受限于内核上下文切换 16。

### 4\.3 虚拟线程与 Ring Poller 的协同

如何让同步阻塞的虚拟线程与异步的 io\_uring 协同工作？

1. __提交 \(Submit\)__：虚拟线程构造 SQE，写入 Ring，更新 Tail，然后调用 LockSupport\.park\(\) 挂起自己。
2. __轮询 \(Poll\)__：一个独立的平台线程（Carrier Thread）运行 io\_uring\_wait\_cqe（或者在 SQPOLL 模式下忙轮询 CQ Ring）。
3. __唤醒 \(Wakeup\)__：当 Poller 线程发现 CQ Ring 中有新的 CQE（完成事件），它读取 CQE 中的 user\_data 字段。这个字段在提交时被写入了虚拟线程的 ID 或引用地址。Poller 线程通过这个 ID 找到对应的虚拟线程对象，并调用 LockSupport\.unpark\(\)。
4. __恢复 \(Resume\)__：虚拟线程苏醒，检查 CQE 的 res 字段（返回值），如果是正数表示发送/接收成功，继续执行后续逻辑（如读取响应）。

## 5\. MVP版本需求分析与具体TODO

为了快速完成 MVP 并吸引开发者，我们必须聚焦于最核心的“可演示”功能。

### 5\.1 MVP 核心功能列表

1. __基础压测能力__：支持 HTTP/1\.1 GET 请求，固定 Payload。
2. __高性能核心__：集成 io\_uring，支持 loopback 零拷贝。
3. __CLI 界面__：支持 \-c \(并发数\), \-d \(持续时间\), \-u \(目标URL\) 参数，并实时显示 RPS \(Requests Per Second\)。
4. __分发能力__：提供 Linux x86\_64 的 Native Image 二进制包。
5. __文档完备__：中英双语 README，包含架构图和性能对比声明。

### 5\.2 具体 TODO 列表 \(按优先级排序\)

#### Phase 1: 骨架搭建 \(预计 2小时\)

- \[ \] __Project Init__: 创建 Maven 多模块项目结构，配置 JDK 25 和 GraalVM 插件。
- \[ \] __Kernel Binding__: 使用 jextract 或手写 MethodHandle 绑定 liburing 的核心函数 \(io\_uring\_setup, io\_uring\_submit, io\_uring\_get\_sqe, io\_uring\_cqe\_seen\)。
	- *提示*: MVP 可暂时只支持 Linux x64，无需跨平台抽象。

#### Phase 2: 内存与Ring实现 \(预计 3小时\)

- \[ \] __Memory Arena__: 实现基于 Arena\.ofShared\(\) 的内存分配器。
- \[ \] __Ring Buffer封装__: 实现 Java 端的 SQ/CQ 读写逻辑，确保内存布局与 C 结构体 \(struct io\_uring\_sqe\) 严格对齐 10。
- \[ \] __Buffer Registration__: 实现 io\_uring\_register\_buffers 调用。

#### Phase 3: 引擎与协议 \(预计 4小时\)

- \[ \] __Virtual Thread Dispatcher__: 实现基于虚拟线程的请求发生器。
- \[ \] __Poller Loop__: 实现 CQ 轮询线程，并打通 LockSupport\.unpark 唤醒机制。
- \[ \] __HTTP Encoder__: 实现最简 HTTP GET 报文写入堆外内存的逻辑（硬编码 Header 以加速）。

#### Phase 4: 构建与自动化 \(预计 3小时\)

- \[ \] __Docker Build__: 编写多阶段 Dockerfile，Stage 1 构建 Native Image，Stage 2 基于 ubuntu:24\.04 \(含较新 Kernel\) 制作运行时镜像。
- \[ \] __GitHub Actions__: 配置 \.github/workflows/release\.yml，集成自动翻译 Action 23。

## 6\. 具体执行步骤与期望执行时间

假设现在是上午 09:00，以下是“今天之内”的冲刺计划。

__时间段__

__任务阶段__

__具体执行内容__

__关键产出__

__09:00 \- 11:00__

__环境与骨架__

1\. 确认开发机 Linux Kernel > 5\.10。

2\. 安装 JDK 25 EA 版本。

3\. 初始化 Maven 结构，引入 barrage\-kernel 模块。

能够编译通过的空项目，FFM Linker 能够成功调用 uname 或简单的 syscall。

__11:00 \- 14:00__

__内核层攻坚__

1\. 编写 io\_uring 绑定代码。

2\. 实现 IoUringRing 类，完成 setup 和 mmap。

3\. 编写单元测试验证 Ring 初始化成功。

单元测试通过：能够初始化 io\_uring 并读取到 fd。

__14:00 \- 18:00__

__核心引擎实现__

1\. 实现 Poller 线程与虚拟线程的协作机制。

2\. 实现 ZeroCopySocket，封装 IORING\_OP\_SEND\_ZC。

3\. 实现 HTTP 协议的堆外写入。

能够向本地 Nginx 发送请求并收到响应，无报错。

__18:00 \- 20:00__

__CLI 与 聚合__

1\. 集成 Picocli。

2\. 实现 TUI 实时展示 RPS。

3\. 接入堆外计数器，实现 0GC 统计。

可运行的 JAR 包，在本地跑出高 RPS 数据。

__20:00 \- 22:00__

__Native 构建__

1\. 配置 GraalVM Native Maven Plugin。

2\. 运行 Tracing Agent 收集配置。

3\. 执行 Native Image 编译。

target/barrage 二进制文件，启动瞬间完成。

__22:00 \- 24:00__

__文档与发布__

1\. 编写 README\.md \(英文\)。

2\. 配置 GitHub Actions 自动翻译。

3\. Push 代码，触发 Release。

GitHub 仓库上线，包含中文文档和 Release 下载链接。

## 7\. 基础设施配置详解

### 7\.1 Dockerfile \(用于开发与构建\)

为了确保 io\_uring 的兼容性，我们使用 ubuntu:24\.04 作为基础镜像，因为它提供了较新的内核头文件和 liburing。

Dockerfile

\# Stage 1: Build & Native Compile  
FROM ghcr\.io/graalvm/native\-image\-community:25 AS builder  
  
\# 安装必要的依赖  
RUN microdnf install \-y liburing\-devel maven git gcc  
  
WORKDIR /app  
COPY\.\.  
  
\# 编译 Native Image \(跳过测试以节省时间，测试在CI中做\)  
RUN mvn \-Pnative clean package \-DskipTests  
  
\# Stage 2: Runtime Image  
\# 使用 Ubuntu 24\.04 以获得较新的 glibc 和 kernel user\-space api 支持  
FROM ubuntu:24\.04  
  
\# 安装运行时依赖  
RUN apt\-get update && apt\-get install \-y liburing2 && rm \-rf /var/lib/apt/lists/\*  
  
COPY \-\-from=builder /app/barrage\-cli/target/barrage /usr/local/bin/barrage  
  
\# 默认入口  
ENTRYPOINT \["/usr/local/bin/barrage"\]  


### 7\.2 Maven Native Plugin 配置

在 barrage\-cli/pom\.xml 中配置 GraalVM 插件，关键在于参数配置以支持 JDK 25 的预览特性（如果使用了 Preview）。

XML

<plugin>  
    <groupId>org\.graalvm\.buildtools</groupId>  
    <artifactId>native\-maven\-plugin</artifactId>  
    <version>$\{native\.maven\.plugin\.version\}</version>  
    <configuration>  
        <imageName>barrage</imageName>  
        <buildArgs>  
            <buildArg>\-\-enable\-native\-access=ALL\-UNNAMED</buildArg>  
            <buildArg>\-\-no\-fallback</buildArg>  
            </buildArgs>  
        <agent>  
            <enabled>true</enabled>  
        </agent>  
    </configuration>  
</plugin>  


### 7\.3 GitHub Actions 自动翻译配置

为了满足“自动翻译中文README”的需求，我们在 \.github/workflows/release\.yml 中集成翻译步骤 23。

YAML

name: Release & Translate  
  
on:  
  push:  
    tags:  
      \- 'v\*'  
  
jobs:  
  build\-and\-translate:  
    runs\-on: ubuntu\-latest  
    permissions:  
      contents: write  
    steps:  
      \- uses: actions/checkout@v4  
        
      \- name: Set up JDK 25  
        uses: graalvm/setup\-graalvm@v1  
        with:  
          java\-version: '25'  
          distribution: 'graalvm\-community'  
            
      \#\.\.\. 构建步骤省略\.\.\.  
  
      \- name: Translate README to Chinese  
        uses: ikhsan3adi/markdown\-translator@master  
        with:  
          src: README\.md  
          dest: README\_CN\.md  
          lang: zh\-CN  
          \# 注意：实际生产中可能需要配置 API Key，或者使用基于 GPT 的 Action  
            
      \- name: Commit Translated README  
        run: |  
          git config \-\-global user\.name 'Barrage Bot'  
          git config \-\-global user\.email 'bot@barrage\.io'  
          git add README\_CN\.md  
          git commit \-m "docs: auto\-translate readme to chinese"  
          git push  


*注意*: 考虑到翻译质量和速度，建议 MVP 阶段先使用基于规则或免费 API 的翻译 Action，后续可切换至基于 LLM 的翻译工具以获得更地道的中文文档。

## 8\. 总结与展望

Barrage 项目不仅是一个压测工具，更是 JDK 25 与现代 Linux 内核技术结合的最佳实践范本。

- __技术深度__：通过 FFM API 与 io\_uring 的结合，我们证明了 Java 可以在系统级编程领域与 C/C\+\+ 掰手腕。
- __架构优势__：虚拟线程解决了并发编程的复杂性，io\_uring 解决了 I/O 的性能瓶颈，Native Image 解决了分发和启动速度问题。
- __社区吸引力__：这样一个技术栈极其硬核（JDK 25 \+ FFM \+ io\_uring \+ GraalVM）的项目，对于渴望探索 Java 新特性的开发者具有致命的吸引力。

今天的 MVP 发布只是开始。未来，Barrage 将支持分布式压测集群（基于 io\_uring 的网络同步）、支持 TLS 卸载（利用 kTLS）、以及支持更复杂的协议（HTTP/3, gRPC）。按照本执行方案，您完全有能力在 24 小时内引爆开源社区。

__附录：关键数据表__

__特性__

__Barrage \(JDK 25 \+ io\_uring\)__

__传统 JMeter \(BIO/NIO\)__

__传统 Gatling \(Netty/NIO\)__

__I/O 模型__

异步提交 \(Proactor\)

同步阻塞/非阻塞 \(Reactor\)

非阻塞 \(Reactor\)

__系统调用__

极少 \(Batch Submit / SQPOLL\)

频繁 \(read/write/epoll\_ctl\)

频繁 \(read/write/epoll\_ctl\)

__内存拷贝__

__零拷贝 \(Zero\-Copy\)__

1\-2次 \(User <\-> Kernel\)

1次 \(DirectBuffer\)

__GC 开销__

__0 GC \(Off\-Heap\)__

高 \(对象分配频繁\)

中 \(Scala/Netty 优化\)

__并发模型__

虚拟线程 \(百万级\)

平台线程 \(千级\)

Event Loop \+ Akka

\(报告结束\)

#### 引用的著作

1. JDK 25 \- OpenJDK, 访问时间为 一月 7, 2026， [https://openjdk\.org/projects/jdk/25/](https://openjdk.org/projects/jdk/25/)
2. Virtual Threads in Java 21: A Deep Dive into Lightweight Concurrency \- Medium, 访问时间为 一月 7, 2026， [https://medium\.com/@aravindcsebe/virtual\-threads\-in\-java\-21\-a\-deep\-dive\-into\-lightweight\-concurrency\-c1743e7dbe04](https://medium.com/@aravindcsebe/virtual-threads-in-java-21-a-deep-dive-into-lightweight-concurrency-c1743e7dbe04)
3. 7 Key Lessons from Using Java 21 Virtual Threads in Production \- Cashfree Tech, 访问时间为 一月 7, 2026， [https://tech\.cashfree\.com/7\-key\-lessons\-from\-using\-java\-21\-virtual\-threads\-in\-production\-18909a8e5a9b](https://tech.cashfree.com/7-key-lessons-from-using-java-21-virtual-threads-in-production-18909a8e5a9b)
4. Virtual Threads in Java 24: We Ran Real\-World Benchmarks—Curious What You Think, 访问时间为 一月 7, 2026， [https://www\.reddit\.com/r/java/comments/1lfa991/virtual\_threads\_in\_java\_24\_we\_ran\_realworld/](https://www.reddit.com/r/java/comments/1lfa991/virtual_threads_in_java_24_we_ran_realworld/)
5. Async IO with Java and Panama: Unlocking the Power of IO\_uring, 访问时间为 一月 7, 2026， [https://davidvlijmincx\.com/posts/async\-io\-with\-java\-and\-panama/](https://davidvlijmincx.com/posts/async-io-with-java-and-panama/)
6. From JNI to FFM: The future of Java‑native interoperability \- IBM Developer, 访问时间为 一月 7, 2026， [https://developer\.ibm\.com/articles/j\-ffm/](https://developer.ibm.com/articles/j-ffm/)
7. Java FFM \- Foreign Function & Memory Access API \(Project Panama\) \- roray\.dev, 访问时间为 一月 7, 2026， [https://www\.roray\.dev/blog/java\-io\-uring\-ffm/](https://www.roray.dev/blog/java-io-uring-ffm/)
8. 12 Foreign Function and Memory API \- Java \- Oracle Help Center, 访问时间为 一月 7, 2026， [https://docs\.oracle\.com/en/java/javase/25/core/foreign\-function\-and\-memory\-api\.html](https://docs.oracle.com/en/java/javase/25/core/foreign-function-and-memory-api.html)
9. A Step\-by\-Step Guide to Java Garbage Collection Tuning \- Sematext, 访问时间为 一月 7, 2026， [https://sematext\.com/java\-garbage\-collection\-tuning/](https://sematext.com/java-garbage-collection-tuning/)
10. Efficient IO with io\_uring \- kernel\.dk, 访问时间为 一月 7, 2026， [https://kernel\.dk/io\_uring\.pdf](https://kernel.dk/io_uring.pdf)
11. io\_uring \- Wikipedia, 访问时间为 一月 7, 2026， [https://en\.wikipedia\.org/wiki/Io\_uring](https://en.wikipedia.org/wiki/Io_uring)
12. io\_uring\_prep\_send\_zc\(3\) \- Linux manual page \- man7\.org, 访问时间为 一月 7, 2026， [https://man7\.org/linux/man\-pages/man3/io\_uring\_prep\_send\_zc\.3\.html](https://man7.org/linux/man-pages/man3/io_uring_prep_send_zc.3.html)
13. io\_uring zero copy Rx \- The Linux Kernel documentation, 访问时间为 一月 7, 2026， [https://docs\.kernel\.org/networking/iou\-zcrx\.html](https://docs.kernel.org/networking/iou-zcrx.html)
14. Java ZeroCopy I/O optimization for high throughput networking \- IBM Developer, 访问时间为 一月 7, 2026， [https://developer\.ibm\.com/articles/j\-zerocopy/](https://developer.ibm.com/articles/j-zerocopy/)
15. Zero\-Copy I/O: From sendfile to io\_uring – Evolution and Impact on Latency in Distributed Logs \- Codemia, 访问时间为 一月 7, 2026， [https://codemia\.io/blog/path/Zero\-Copy\-IO\-From\-sendfile\-to\-iouring\-\-Evolution\-and\-Impact\-on\-Latency\-in\-Distributed\-Logs](https://codemia.io/blog/path/Zero-Copy-IO-From-sendfile-to-iouring--Evolution-and-Impact-on-Latency-in-Distributed-Logs)
16. I/O Passthru: Upstreaming a flexible and efficient I/O Path in Linux \- USENIX, 访问时间为 一月 7, 2026， [https://www\.usenix\.org/system/files/fast24\-joshi\.pdf](https://www.usenix.org/system/files/fast24-joshi.pdf)
17. Releases · graalvm/graalvm\-ce\-builds \- GitHub, 访问时间为 一月 7, 2026， [https://github\.com/graalvm/graalvm\-ce\-builds/releases/](https://github.com/graalvm/graalvm-ce-builds/releases/)
18. Native Image \- GraalVM, 访问时间为 一月 7, 2026， [https://www\.graalvm\.org/latest/reference\-manual/native\-image/](https://www.graalvm.org/latest/reference-manual/native-image/)
19. Simplifying native\-image generation with Maven plugin and embeddable configuration | by Paul Wögerer | graalvm | Medium, 访问时间为 一月 7, 2026， [https://medium\.com/graalvm/simplifying\-native\-image\-generation\-with\-maven\-plugin\-and\-embeddable\-configuration\-d5b283b92f57](https://medium.com/graalvm/simplifying-native-image-generation-with-maven-plugin-and-embeddable-configuration-d5b283b92f57)
20. GraalVM Native Build Tools \- for Maven \- Luna Labs | Oracle, 访问时间为 一月 7, 2026， [https://luna\.oracle\.com/lab/e5af592b\-3365\-45ce\-b964\-6fd409e5c76f/steps](https://luna.oracle.com/lab/e5af592b-3365-45ce-b964-6fd409e5c76f/steps)
21. Maven archetype quickstart issue with JDK 25? \- Java Community | Help\. Code\. Learn\., 访问时间为 一月 7, 2026， [https://www\.answeroverflow\.com/m/1433689729118896228](https://www.answeroverflow.com/m/1433689729118896228)
22. Guide to Working with Multiple Modules \- Apache Maven, 访问时间为 一月 7, 2026， [https://maven\.apache\.org/guides/mini/guide\-multiple\-modules\.html](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
23. Translate Readme · Actions · GitHub Marketplace, 访问时间为 一月 7, 2026， [https://github\.com/marketplace/actions/translate\-readme](https://github.com/marketplace/actions/translate-readme)
24. Adding io\_uring to Java | Ilya Korennoy, 访问时间为 一月 7, 2026， [https://korennoy\.com/2023/06/09/adding\-io\_uring\-to\-java/](https://korennoy.com/2023/06/09/adding-io_uring-to-java/)
25. JUring \- Bringing io\_uring to Java for file I/O \- Reddit, 访问时间为 一月 7, 2026， [https://www\.reddit\.com/r/java/comments/1i0ak5c/juring\_bringing\_io\_uring\_to\_java\_for\_file\_io/](https://www.reddit.com/r/java/comments/1i0ak5c/juring_bringing_io_uring_to_java_for_file_io/)
26. From On\-Heap to Off\-Heap: Revolutionizing Big Data Processing with Java FFM \- Medium, 访问时间为 一月 7, 2026， [https://medium\.com/@bhavya\.joshi1901/from\-heap\-to\-off\-heap\-revolutionizing\-big\-data\-processing\-with\-java\-ffm\-3bdb60ac4252](https://medium.com/@bhavya.joshi1901/from-heap-to-off-heap-revolutionizing-big-data-processing-with-java-ffm-3bdb60ac4252)
27. AI Translate Action \- GitHub Marketplace, 访问时间为 一月 7, 2026， [https://github\.com/marketplace/actions/ai\-translate\-action](https://github.com/marketplace/actions/ai-translate-action)


