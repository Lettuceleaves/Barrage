# Barrage 火力网压测器

 <img src="./docs/logo.png" width="50" height="50" style="border-radius: 50%; vertical-align: middle;" alt="图标">

[![Maven Central](https://img.shields.io/maven-central/v/com.github.LettuceLeaves/barrage.svg)](https://search.maven.org/artifact/com.github.LettuceLeaves/barrage)
[![License](https://img.shields.io/github/license/LettuceLeaves/barrage.svg)](https://github.com/LettuceLeaves/barrage/blob/main/LICENSE)

## 项目简介

**Barrage (火力网)** 是一个基于 Java 25 构建的下一代高性能 HTTP 压测引擎。

不同于传统的基于 Netty 或许多基于 Go 的压测工具，Barrage 彻底摒弃了 JVM 的 NIO 层，直接利用 Java FFM (Foreign Function & Memory API) 与 Linux 内核的 **`io_uring`** 异步 I/O 接口交互。通过采用 **Thread-Per-Core** 架构和全链路堆外内存管理，Barrage 实现了真正的 **Zero-GC (零垃圾回收)** 运行时的目标，旨在榨干硬件的每一分性能，提供极致稳定、低延迟的高并发压力测试能力。

> 🚀 **核心目标**: 在单机提供百万级 QPS 的 HTTP 压测能力，同时保持极低的尾部延迟。

## 核心特性

* **极致性能架构**:
    * **Java FFM + io_uring**: 绕过 JDK NIO，直接进行系统调用，减少内核态/用户态切换。
    * **Zero-GC 设计**: 核心路径全部使用 `MemorySegment` 和 `Arena` 管理堆外内存，压测过程中无 Java 对象分配，杜绝 GC 停顿。
    * **Thread-Per-Core 模型**: 每个 CPU 核心绑定独立的 Worker 线程和 io_uring 环，无锁竞争。
* **高效内存管理**:
    * **请求批处理 (Request Batching)**: 预组装批量请求模版，极大减少 `send` 系统调用次数。
    * **零拷贝解析 (Zero-Copy Scanning)**: 采用 SWAR 算法直接在接收缓冲区扫描 HTTP 响应，无需将数据拷贝到 Java 堆。
* **灵活的数据源**: 支持从本地文件加载或控制台交互式构建 HTTP 请求模版。
* **内置基准服务端**: 提供一个同样基于 io_uring 的高性能 Echo Server 用于闭环基准测试。

## 环境要求 (Prerequisites)

由于深度依赖 Linux 内核特性和前沿 Java API，运行 Barrage 需要满足以下条件：

* **操作系统**: **Linux Only**。推荐 Kernel **5.10+** (以获得完整的 `IORING_OP_SEND/RECV` 支持，最佳性能推荐 6.x 内核)。
    * *注意: 不支持 Windows, macOS 或 WSL1。WSL2 需要确保内核版本符合要求。*
* **JDK**: **Java 22 或更高版本**。
    * 必须启用预览特性: `--enable-preview --source 22` (编译时) 和 `--enable-preview` (运行时)。
* **硬件**: 推荐多核 CPU 环境，并预留足够的内存用于堆外分配。

## 快速开始

### 1. 克隆仓库

 ```bash
 git clone https://github.com/Lettuceleaves/Barrage.git
 cd barrage
 ```

### 2. 构建项目（推荐使用容器化开发环境）

使用 Maven 进行编译和打包。确保你的 Maven 使用的是 JDK 22+。

 ```bash
 # 编译并运行单元测试
 mvn clean package
 ```

 ```bash
 mkdir -p tmp
 echo -e "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" > tmp/http_request.txt
 ```

### 3. 运行压测

目前 Barrage 提供交互式 CLI 界面。运行 target 目录下的 jar 包：

 ```bash
 # 需要启用预览特性，并允许本地内存访问
 java --enable-preview --enable-native-access=ALL-UNNAMED -jar target/barrage-1.0-SNAPSHOT.jar
 ```

启动后，程序将引导你完成以下步骤：
1.  **选择数据源**: 选择使用文件文件还是通过控制台手动输入 HTTP 请求头。
2.  **加载模版**: 引擎将加载数据并预热 JIT 编译器。
3.  **自动压测**: 程序会自动启动内置的基准服务端，并启动客户端引擎开始压测。
4.  **实时监控**: 控制台将每秒输出当前的平均 QPS。

 ```text
/opt/graalvm-25/bin/java -javaagent:/.jbdevcontainer/JetBrains/RemoteDev/dist/1e3d8e0aa22b3_idea-2025.3.1.1/lib/idea_rt.jar=41331 -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /IdeaProjects/Barrage/barrage-cli/target/classes:/IdeaProjects/Barrage/barrage-engine/target/classes:/IdeaProjects/Barrage/barrage-kernel/target/classes:/IdeaProjects/Barrage/barrage-protocol/target/classes:/root/.m2/repository/com/github/spotbugs/spotbugs-annotations/4.8.6/spotbugs-annotations-4.8.6.jar:/root/.m2/repository/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.jar com.barrage.cli.Main
==============================================
   Barrage Kernel: True Zero-GC IoUring Engine
   Version: 1.0 | Java 22+ FFM API
   Config: 8 Server Threads, 8 Client Threads
==============================================

[Data Source Selection]
1. FILE    (Path: tmp/http_request.txt)
2. CONSOLE (Manual Input)
Please select [1-2]: 2
>>> Loading HTTP Template from: ConsoleDataSource

=============================================
   HTTP REQUEST BUILDER (Press ENTER for Default)
=============================================
Enter Method  [GET]: 
Enter Host    [127.0.0.1]: 
Enter Port    [8080]: 
Enter Path    [/]: 
Enter Body    []: 
>>> HTTP Message built and loaded into MemoryArena.
WARNING: A restricted method in java.lang.foreign.SymbolLookup has been called
WARNING: java.lang.foreign.SymbolLookup::libraryLookup has been called by com.barrage.kernel.io.NativeSocket$NativeLib in an unnamed module (file:/IdeaProjects/Barrage/barrage-kernel/target/classes/)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

[ServerEngine] Listening on 8080 (FD: 10)
[ServerEngine] Initializing 8 worker threads (Thread-Per-Core)...
>>> Warming up (2 seconds) to stabilize JIT/AOT runtime...
>>> Starting Client Load Generator...
[ClientEngine] Pre-filled batch request: 16 requests, total 16384 bytes
[ClientEngine] Starting 8 workers...
>>> [1s] Avg QPS: 1785 k/s (Total: 1785592)
>>> [2s] Avg QPS: 2261 k/s (Total: 4522247)
>>> [3s] Avg QPS: 2430 k/s (Total: 7291086)
>>> [4s] Avg QPS: 2434 k/s (Total: 9737548)
>>> [5s] Avg QPS: 2423 k/s (Total: 12115074)
>>> [6s] Avg QPS: 2398 k/s (Total: 14391895)
>>> [7s] Avg QPS: 2365 k/s (Total: 16557798)
>>> [8s] Avg QPS: 2363 k/s (Total: 18905366)
>>> [9s] Avg QPS: 2361 k/s (Total: 21255623)
>>> [10s] Avg QPS: 2354 k/s (Total: 23541033)
>>> [11s] Avg QPS: 2327 k/s (Total: 25602846)
>>> [12s] Avg QPS: 2300 k/s (Total: 27610597)
...
 ```
## 容器化开发支持 (Dev Containers)

为了方便开发者在不同操作系统（尤其是 Windows/macOS）上构建开发环境，本项目内置了标准的 **Dev Container** 配置。该环境已预装 Graalvm-ce-25、Maven 以及必要的 Linux 构建工具链，确保了开发环境的一致性。

### 支持的 IDE
* **VS Code**: 需安装 [Dev Containers](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) 扩展。
* **IntelliJ IDEA**: Ultimate 版原生支持，或社区版安装相应插件。

### 使用步骤
1.  确保本地已安装 **Docker**。
2.  在 IDE 中打开项目根目录。
    * **VS Code**: 点击右下角弹出的 "Reopen in Container" 提示，或通过命令面板 (`F1`) 选择 `Dev Containers: Reopen in Container`。
    * **IDEA**: 点击项目视图顶部的 "Dev Container" 图标或根据提示构建并连接到容器。

> ⚠️ **注意**: 由于本项目强依赖 `io_uring`，Windows 用户请确保 Docker Desktop 使用 **WSL 2** 后端；macOS 用户需确保 Docker Desktop 为最新版本（Linux VM 内核需支持 io_uring）。


## 配置指南

目前的配置主要通过修改 `com.barrage.kernel.config.GlobalConfig` 类中的静态参数来实现调优。在未来的版本中将支持配置文件或更丰富的 CLI 参数。

关键参数说明：

| 参数名 | 默认值  | 说明 | 调优建议 |
 | :--- |:-----| :--- | :--- |
| `serverThreads` | 1    | 内置基准服务端的 Worker 线程数。 | 建议设置为 CPU 核心数的一半。 |
| `clientThreads` | 1    | 压测客户端的 Worker 线程数。 | 建议设置为 CPU 核心数的一半。 |
| `connsPerClient` | 1    | 每个客户端线程维护的长连接数。 | 增加此值可提高并发度，但会增加内存开销。 |
| `inFlight` | 16   | HTTP 流水线 (Pipelining) 深度。决定未收到响应前连续发送的请求数。 | 针对高延迟网络增加此值以填满 BDP。 |
| `queueDepth` | 4096 | io_uring 提交/完成队列的深度。必须是 2 的幂。 | 保持较大值以减少系统调用频率。 |
| `batchSize` | 16   | 系统调用批处理大小。 | 增大此值可摊薄 syscall 开销，但可能会略微增加延迟。 |

## 技术栈

* **核心**: Java 22+ (Project Panama / FFM API)
* **内核接口**: Linux kernel 5.10+ (`io_uring`, `libc`)
* **构建工具**: Maven
* **代码质量**: SpotBugs (配置了严格的 Zero-GC 检查规则)

## 许可证

本项目采用 MIT 许可证，详情请参阅 [LICENSE](LICENSE) 文件。