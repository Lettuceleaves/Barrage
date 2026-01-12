# Barrage

 <img src="./docs/logo.png" width="50" height="50" style="border-radius: 50%; vertical-align: middle;" alt="Icon">

[![Maven Central](https://img.shields.io/maven-central/v/com.github.LettuceLeaves/barrage.svg)](https://search.maven.org/artifact/com.github.LettuceLeaves/barrage)
[![License](https://img.shields.io/github/license/LettuceLeaves/barrage.svg)](https://github.com/LettuceLeaves/barrage/blob/main/LICENSE)

## Introduction

**Barrage** is a next-generation high-performance HTTP benchmarking engine built on Java 25.

Unlike traditional load testing tools based on Netty or many Go-based alternatives, Barrage completely bypasses the JVM's NIO layer. It interacts directly with the Linux kernel's **`io_uring`** asynchronous I/O interface using the Java FFM (Foreign Function & Memory API). By adopting a **Thread-Per-Core** architecture and full-chain off-heap memory management, Barrage achieves a true **Zero-GC (Zero Garbage Collection)** runtime. Its goal is to squeeze every bit of performance out of the hardware, providing ultra-stable, low-latency, and high-concurrency stress testing capabilities.

> 🚀 **Core Goal**: To provide million-level QPS for HTTP benchmarking on a single machine while maintaining extremely low tail latency.

## Core Features

* **Extreme Performance Architecture**:
    * **Java FFM + io_uring**: Bypasses JDK NIO to perform direct system calls, reducing kernel/user space context switches.
    * **Zero-GC Design**: Critical paths exclusively use `MemorySegment` and `Arena` to manage off-heap memory. No Java objects are allocated during the benchmarking process, eliminating GC pauses.
    * **Thread-Per-Core Model**: Each CPU core is bound to an independent Worker thread and an io_uring ring, ensuring no lock contention.
* **Efficient Memory Management**:
    * **Request Batching**: Pre-assembles batch request templates, drastically reducing the number of `send` system calls.
    * **Zero-Copy Scanning**: Uses the SWAR algorithm to scan HTTP responses directly in the receive buffer, without copying data to the Java heap.
* **Flexible Data Sources**: Supports loading HTTP request templates from local files or building them interactively via the console.
* **Built-in Benchmark Server**: Provides a high-performance Echo Server, also based on io_uring, for closed-loop benchmark testing.

## Prerequisites

Due to deep dependencies on Linux kernel features and cutting-edge Java APIs, running Barrage requires the following conditions:

* **Operating System**: **Linux Only**. Kernel **5.10+** is recommended (to obtain full `IORING_OP_SEND/RECV` support; 6.x kernel is recommended for best performance).
    * *Note: Windows, macOS, or WSL1 are NOT supported. For WSL2, ensure the kernel version meets the requirements.*
* **JDK**: **Java 22 or higher**.
    * Preview features must be enabled: `--enable-preview --source 22` (at compile time) and `--enable-preview` (at runtime).
* **Hardware**: A multi-core CPU environment is recommended, with sufficient memory reserved for off-heap allocation.

## Quick Start

### 1. Clone the Repository

 ```bash
 git clone https://github.com/Lettuceleaves/Barrage.git
 cd barrage
 ```

### 2. Build the Project

Use Maven to compile and package. Ensure your Maven is using JDK 22+.

 ```bash
 # Compile and run unit tests
 mvn clean package
 ```

### 3. Prepare Test Data (Optional)

If you choose to use a file as the data source, you can prepare a simple HTTP request file:

 ```bash
 mkdir -p tmp
 echo -e "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" > tmp/http_request.txt
 ```

### 4. Run the Benchmark

Barrage currently provides an interactive CLI. Run the jar package located in the target directory:

 ```bash
 # Enable preview features and allow native memory access
 java --enable-preview --enable-native-access=ALL-UNNAMED -jar target/barrage-1.0-SNAPSHOT.jar
 ```

After starting, the program will guide you through the following steps:
1.  **Select Data Source**: Choose between using a file or manually entering HTTP headers via the console.
2.  **Load Template**: The engine loads the data and warms up the JIT compiler.
3.  **Auto Benchmark**: The program automatically starts the built-in benchmark server and launches the client engine to begin stress testing.
4.  **Real-time Monitoring**: The console outputs the current average QPS every second.

 ```text
 /opt/graalvm-25/bin/java ... com.barrage.cli.Main
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
 ... (Warnings about restricted methods) ...

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

## Containerized Development (Dev Containers)

To facilitate setting up the development environment across different operating systems (especially Windows/macOS), this project includes standard **Dev Container** configurations. The environment comes pre-installed with Graalvm-ce-25, Maven, and the necessary Linux build toolchain, ensuring consistency.

### Supported IDEs
* **VS Code**: Requires the [Dev Containers](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension.
* **IntelliJ IDEA**: Supported natively in the Ultimate edition, or via plugin in the Community edition.

### Usage Steps
1.  Ensure **Docker** is installed locally.
2.  Open the project root directory in your IDE.
    * **VS Code**: Click the "Reopen in Container" notification at the bottom right, or use the Command Palette (`F1`) to select `Dev Containers: Reopen in Container`.
    * **IDEA**: Click the "Dev Container" icon at the top of the project view or follow the prompts to build and connect to the container.

> ⚠️ **Note**: Since this project heavily relies on `io_uring`, Windows users must ensure Docker Desktop uses the **WSL 2** backend; macOS users must ensure Docker Desktop is up to date (the Linux VM kernel must support io_uring).

## Configuration Guide

Current configuration is managed by modifying static parameters in the `com.barrage.kernel.config.GlobalConfig` class. Future versions will support configuration files or richer CLI arguments.

Key Parameters:

| Parameter | Default | Description | Tuning Suggestion |
 | :--- |:--------| :--- | :--- |
| `serverThreads` | 1       | Number of Worker threads for the built-in benchmark server. | Recommended to set to half the number of CPU cores. |
| `clientThreads` | 1       | Number of Worker threads for the load testing client. | Recommended to set to half the number of CPU cores. |
| `connsPerClient` | 1       | Number of persistent connections maintained by each client thread. | Increasing this can improve concurrency but increases memory overhead. |
| `inFlight` | 16      | HTTP Pipelining depth. Determines how many requests to send continuously before receiving a response. | Increase this value to fill the BDP on high-latency networks. |
| `queueDepth` | 4096    | Depth of the io_uring Submission/Completion Queues. Must be a power of 2. | Keep a large value to reduce the frequency of system calls. |
| `batchSize` | 16      | System call batch size. | Increasing this can amortize syscall overhead but may slightly increase latency. |

## Tech Stack

* **Core**: Java 22+ (Project Panama / FFM API)
* **Kernel Interface**: Linux kernel 5.10+ (`io_uring`, `libc`)
* **Build Tool**: Maven
* **Code Quality**: SpotBugs (configured with strict Zero-GC check rules)

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.