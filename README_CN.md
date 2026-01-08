# Barrage 火力网压测器

[![CI](https://github.com/<owner>/<repo>/actions/workflows/ci.yml/badge.svg)](https://github.com/<owner>/<repo>/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.<owner>/barrage.svg)](https://search.maven.org/artifact/com.github.<owner>/barrage)
[![License](https://img.shields.io/github/license/<owner>/<repo>.svg)](https://github.com/<owner>/<repo>/blob/main/LICENSE)

## 项目简介
Barrage 是基于 Java 构建的高性能、稳定的压测工具，旨在帮助开发者对网络服务进行高并发压力测试。

## 预期功能特性
- 支持自定义并发线程数和请求速率
- 多协议支持（HTTP、HTTPS）
- 丰富的统计信息与实时监控
- 可通过 Maven Central 直接获取依赖

## 技术栈

- **语言**: Java 11+
- **构建工具**: Maven
- **持续集成**: GitHub Actions (ci.yml)
- **代码质量**: SpotBugs, Checkstyle
- **依赖管理**: Maven Central

## 快速开始
```bash
# 克隆仓库
git clone https://github.com/<owner>/<repo>.git
cd barrage

# 使用 Maven 构建
mvn clean package

# 运行示例压测
java -jar target/barrage-cli.jar -u http://example.com -c 100 -r 10
```

## 构建与发布
```bash
# 编译
mvn compile

# 运行单元测试
mvn test

# 打包
mvn package
```

## 贡献指南
欢迎提交 Pull Request！在提交前请确保代码通过所有单元测试并符合项目的代码风格。

## 许可证
本项目采用 MIT 许可证，详情请参阅 [LICENSE](https://github.com/<owner>/<repo>/blob/main/LICENSE) 文件。