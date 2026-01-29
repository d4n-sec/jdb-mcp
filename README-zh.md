# JDB-MCP: Java Debugger MCP Server

[English](./README.md)

JDB-MCP 是一个基于 Java 调试接口 (JDI) 实现的 Model Context Protocol (MCP) 服务器。它允许 AI 智能体（如 Claude, Cline, Trae）直接对 Java 应用程序进行深度的运行时调试。

## 核心能力

- **多模式传输**: 支持 `stdio` (VSCode 标准集成) 和 `http` (远程/curl 调试) 传输模式。
- **全功能调试 (仅限 Attach 模式)**:
    - 附加到运行中的进程 (`debug_attach`) - 支持任何开启了 JDWP 的 Java 应用 (SpringBoot, Maven, Gradle 等)。
    - 断点管理 (`debug_set_breakpoint`, `debug_set_watchpoint`)
    - 流程控制 (单步跳过、进入、跳出、恢复运行)
    - 状态检查 (查看堆栈、结构化变量深度遍历)
    - 线程与类检查 (`debug_list_threads`, `debug_list_classes`)
    - 动态修改 (运行时修改变量值)
- **极简设计**: 目前专注于单会话调试，无需管理复杂的 `sessionId`，开箱即用。
- **需手动开启调试模式**: 目标 Java 程序必须由用户手动启动并开启 JDWP 调试选项。
- **实时感知**: 通过 MCP Notifications 机制，AI 能在断点命中的瞬间感知程序状态。

## 安装与构建

### 前置要求
- JDK 17+ (需要 JDI 模块)

### 构建 fatJAR (推荐)
```bash
# 构建包含所有依赖的独立 JAR 包 (需要 pom.xml 中的 maven-assembly-plugin)
mvn clean compile assembly:single
```

## 配置使用

### 启动选项 (Startup Options)
- `--transport <stdio|http>`: 设置传输模式 (默认: `stdio`)。
- `--notifications <true|false>`: 是否开启 AI 实时通知 (默认: `true`)。

有关工具参数的详细解析，请参阅 [参数指南](./documents/PARAMETER_GUIDE_ZH.md)。

### 在 VSCode (Cline/Claude Dev) 中使用
将以下配置添加到您的 MCP 设置中：

```json
{
  "mcpServers": {
    "jdb-debugger": {
      "command": "java",
      "args": [
        "-jar",
        "path/to/jdb-mcp.jar"
      ]
    }
  }
}
```

### 如何开始调试 (Attach 模式)
**重要**: 您必须手动启动目标 Java 应用程序并开启 JDWP。

1. 启动您的项目：
   ```bash
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar your-app.jar
   ```
2. 在 AI 对话中要求：`附加到 localhost:5005 并开始调试...`

## 工具列表 (Tools)
- `debug_attach`: 通过 Socket 附加到现有的调试端口。
- `debug_list_threads`: 列出所有线程及其状态。
- `debug_list_classes`: 列出已加载的类，支持过滤。
- `debug_list_vars`: 获取变量信息（支持 `threadName` 过滤）。
- `debug_get_var`: 获取特定变量的详细信息。
- `debug_set_var`: 修改运行时变量（支持 `threadName` 和 `frameIndex`）。
- ...以及更多（详见 `tools/list`）。

## TODO List

- [ ] **实现 `debug_launch` 功能**：提供“开箱即用”的体验，允许用户直接通过 MCP 启动并调试 Java 程序。
- [ ] **实现 `debug_calc` 功能**：支持在调试上下文中执行任意 Java 表达式（Expression Evaluation）。
- [ ] **多会话支持**：重构代码以支持一个 MCP 实例同时管理和调试多个目标程序。
- [ ] **待补充**：根据社区反馈持续添加新功能。

## 许可证
基于 [MIT License](LICENSE) 开源。
