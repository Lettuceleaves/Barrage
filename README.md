# Barrage Network Stress Tester

[![CI](https://github.com/<owner>/<repo>/actions/workflows/ci.yml/badge.svg)](https://github.com/<owner>/<repo>/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.<owner>/barrage.svg)](https://search.maven.org/artifact/com.github.<owner>/barrage)
[![License](https://img.shields.io/github/license/<owner>/<repo>.svg)](https://github.com/<owner>/<repo>/blob/main/LICENSE)

## Project Introduction
Barrage is a high-performance, stable stress testing tool built on Java, designed to help developers perform high-concurrency stress tests on network services.

## Anticipated Features
- Supports custom concurrent threads and request rate
- Multi-protocol support (HTTP, HTTPS)
- Rich statistics and real-time monitoring
- Dependencies can be directly obtained via Maven Central

## Technology Stack

- **Language**: Java 11+
- **Build Tool**: Maven
- **Continuous Integration**: GitHub Actions (ci.yml)
- **Code Quality**: SpotBugs, Checkstyle
- **Dependency Management**: Maven Central

## Quick Start
```bash
# Clone the repository
git clone https://github.com/<owner>/<repo>.git
cd barrage

# Build with Maven
mvn clean package

# Run example stress test
java -jar target/barrage-cli.jar -u http://example.com -c 100 -r 10
```

## Build and Publish
```bash
# Compile
mvn compile

# Run unit tests
mvn test

# Package
mvn package
```

## Contribution Guide
Pull Requests are welcome! Before submitting, please ensure the code passes all unit tests and conforms to the project's coding style.

## License
This project uses the MIT License. For details, please refer to the [LICENSE](https://github.com/<owner>/<repo>/blob/main/LICENSE) file.