# Barrage Load Tester

 <img src="./docs/logo.png" width="50" height="50" style="border-radius: 50%; vertical-align: middle;" alt="Icon">

[![Maven Central](https://img.shields.io/maven-central/v/com.github.LettuceLeaves/barrage.svg)](https://search.maven.org/artifact/com.github.LettuceLeaves/barrage)
[![License](https://img.shields.io/github/license/LettuceLeaves/barrage.svg)](https://github.com/LettuceLeaves/barrage/blob/main/LICENSE)

## Project Introduction

**Barrage** is a next-generation high-performance HTTP load testing engine built on Java 25.

Unlike traditional Netty-based or many Go-based load testing tools, Barrage completely abandons the JVM's NIO layer, directly leveraging Java FFM (Foreign Function & Memory API) to interact with the Linux kernel's **`io_uring`** asynchronous I/O interface. By adopting a **Thread-Per-Core** architecture and full-link off-heap memory management, Barrage achieves the goal of a truly **Zero-GC (Zero Garbage Collection)** runtime, aiming to squeeze every bit of hardware performance, providing ultra-stable, low-latency, high-concurrency stress testing capabilities.

> 🚀 **Core Goal**: To provide millions of QPS HTTP load testing capability on a single machine, while maintaining extremely low tail latency.

## Core Features

*   **Extreme Performance Architecture**:
    *   **Java FFM + io_uring**: Bypasses JDK NIO, performs direct system calls, reducing kernel/user-mode switching.
    *   **Zero-GC Design**: All core paths use `MemorySegment` and `Arena` for off-heap memory management, with no Java object allocation during load testing, eliminating GC pauses.
    *   **Thread-Per-Core Model**: Each CPU core binds an independent Worker thread and io_uring ring, with no lock contention.
*   **Efficient Memory Management**:
    *   **Request Batching**: Pre-assembles batch request templates, greatly reducing the number of `send` system calls.
    *   **Zero-Copy Scanning**: Uses SWAR algorithm to directly scan HTTP responses in the receive buffer, eliminating the need to copy data to the Java heap.
*   **Flexible Data Sources**: Supports loading from local files or interactively building HTTP request templates from the console.
*   **Built-in Benchmark Server**: Provides a high-performance Echo Server, also based on io_uring, for closed-loop benchmarking.

## Environment Requirements (Prerequisites)

Due to deep reliance on Linux kernel features and cutting-edge Java APIs, Barrage requires the following conditions to run:

*   **Operating System**: **Linux Only**. Kernel **5.10+** is recommended (for full `IORING_OP_SEND/RECV` support; Kernel 6.x is recommended for optimal performance).
    *   *Note: Windows, macOS, or WSL1 are not supported. WSL2 requires the kernel version to meet the requirements.*
*   **JDK**: **Java 22 or higher**.
    *   Preview features must be enabled: `--enable-preview --source 22` (at compile time) and `--enable-preview` (at runtime).
*   **Hardware**: A multi-core CPU environment is recommended, with sufficient memory reserved for off-heap allocation.

## Quick Start

```
# docker
docker run -it --privileged -p 8088:8080 --name barrage lettuceleaves/barrage:v1.0.1
docker run -it --privileged -p 8088:8080 -v /path/to/your/config:/app/config --name barrage lettuceleaves/barrage:v1.0.1
```

## Development Environment

### 1. Clone the repository

 ```bash
 git clone https://github.com/Lettuceleaves/Barrage.git
 cd barrage
 ```

### 2. Build the Project (Containerized Development Environment Recommended)

Use Maven for compilation and packaging. Ensure your Maven uses JDK 22+.

 ```bash
 # Compile and run unit tests
 mvn clean package
 ```

 ```bash
 mkdir -p tmp
 echo -e "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" > tmp/http_request.txt
 ```

### 3. Run the Load Test

Currently, Barrage provides an interactive CLI interface. Run the jar package in the target directory:

 ```bash
 # Preview features must be enabled, and native memory access must be allowed
 java --enable-preview --enable-native-access=ALL-UNNAMED -jar target/barrage-1.0-SNAPSHOT.jar
 ```

After startup, the program will guide you through the following steps:
1.  **Select Data Source**: Choose whether to use a file or manually enter HTTP request headers via the console.
2.  **Load Template**: The engine will load data and warm up the JIT compiler.
3.  **Automatic Load Test**: The program will automatically start the built-in benchmark server and initiate the client engine to begin load testing.
4.  **Real-time Monitoring**: The console will output the current average QPS every second.

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
## Containerized Development Support (Dev Containers)

To facilitate developers in setting up development environments on different operating systems (especially Windows/macOS), this project includes standard **Dev Container** configurations. This environment comes pre-installed with Graalvm-ce-25, Maven, and the necessary Linux build toolchain, ensuring consistency across development environments.

### Supported IDEs
*   **VS Code**: Requires the [Dev Containers](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers) extension.
*   **IntelliJ IDEA**: Ultimate edition has native support, or install the corresponding plugin for the community edition.

### Usage Steps
1.  Ensure **Docker** is installed locally.
2.  Open the project root directory in your IDE.
    *   **VS Code**: Click the "Reopen in Container" prompt that appears in the bottom right, or select `Dev Containers: Reopen in Container` via the command palette (`F1`).
    *   **IDEA**: Click the "Dev Container" icon at the top of the project view or follow the prompts to build and connect to the container.

> ⚠️ **Note**: As this project heavily relies on `io_uring`, Windows users should ensure Docker Desktop uses the **WSL 2** backend; macOS users should ensure Docker Desktop is the latest version (the Linux VM kernel must support io_uring).

## Configuration Guide

Current configuration primarily relies on modifying static parameters within the `com.barrage.kernel.config.GlobalConfig` class for tuning. Future versions will support configuration files or more extensive CLI parameters.

Key parameter descriptions:

| Parameter Name | Default Value | Description | Tuning Suggestion |
 | :--- |:-----| :--- | :--- |
| `serverThreads` | 1 | Number of Worker threads for the built-in benchmark server. | Recommended to set to half of the CPU core count. |
| `clientThreads` | 1 | Number of Worker threads for the load testing client. | Recommended to set to half of the CPU core count. |
| `connsPerClient` | 1 | Number of long connections maintained by each client thread. | Increasing this value can improve concurrency but will increase memory overhead. |
| `inFlight` | 16 | HTTP Pipelining depth. Determines the number of consecutive requests sent before receiving a response. | Increase this value for high-latency networks to fill the BDP. |
| `queueDepth` | 4096 | Depth of the io_uring submission/completion queue. Must be a power of 2. | Keep a large value to reduce system call frequency. |
| `batchSize` | 16 | System call batch processing size. | Increasing this value can amortize syscall overhead but might slightly increase latency. |

## Technology Stack

*   **Core**: Java 22+ (Project Panama / FFM API)
*   **Kernel Interface**: Linux kernel 5.10+ (`io_uring`, `libc`)
*   **Build Tool**: Maven
*   **Code Quality**: SpotBugs (configured with strict Zero-GC checking rules)

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.