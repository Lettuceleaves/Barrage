# Barrage Kernel - 高性能 I/O 核心

`barrage-kernel` 是 Barrage 压测器的核心组件，基于 **JDK 25 (Project Panama FFM API)** 实现，提供了对 Linux `io_uring` 的极致封装。

## 核心特性

- **极致零拷贝 (Zero-Copy)**: 支持 `IORING_OP_SEND_ZC`，结合预注册缓冲区（Registered Buffers），数据从用户态直接下发到网卡驱动，无需内核拷贝。
- **零系统调用 (Zero-Syscall)**: 通过 `IORING_SETUP_SQPOLL` 模式，由内核线程主动轮询提交队列，在持续压测过程中实现真正的用户态“零中断”提交。
- **零 GC 开销**: 核心热路径完全基于堆外内存（FFM `MemorySegment`）和 Arena 内存模型实现，压测过程中不产生任何 JVM 堆对象。

## 性能表现

以下测试基于 `socketpair` (AF_UNIX) 环境，模拟 4KB 负载的持续发送，测试量级为 1,000,000 次迭代。

| 模式 | 吞吐量 (MB/s) | 吞吐量 (Gbps) | 备注 |
| :--- | :--- | :--- | :--- |
| **Base** (标准 SEND, 无 SQPOLL) | 6028 | ~47 | 基础 I/O 环性能 |
| **SQPOLL** (标准 SEND + SQPOLL) | 6132 | ~48 | 减少系统调用开销 |
| **Turbo** (SEND_ZC + SQPOLL) | **6220** | **~50** | **零拷贝 + 零系统调用 (全血状态)** |

> [!NOTE]
> 在 50 Gbps 的极限吞吐量下，`barrage-kernel` 表现出了卓越的稳定性，且由于 `SQPOLL` 的存在，宿主机 CPU 的 `sy` (System) 占比极低，能将更多资源留给上层业务逻辑。

## 运行基准测试

如果您想在本地复现性能数据，可以运行：

```bash
mvn test -Dtest=IoUringBenchmark
```

## 开发者说明

该模块目前包含：
- `NativeConstants`: 封装所有 Linux 系统调用常量。
- `IoUring`: 高性能环形队列管理，支持高级 `io_uring` 特性。
- `MemoryArena`: 零 GC 的堆外内存管理工具。
