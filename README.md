# JDB-MCP: Java Debugger MCP Server

[中文版](./README-zh.md)

JDB-MCP is a Model Context Protocol (MCP) server based on the Java Debug Interface (JDI). It enables AI agents (such as Claude, Cline, Trae) to perform deep runtime debugging of Java applications.

## Key Capabilities

- **Multi-transport Support**: Supports both `stdio` (standard VSCode integration) and `http` (for remote/curl debugging).
- **Full-featured Debugging (Attach Mode Only)**:
    - Attach to running processes (`debug_attach`) - Supports any Java application (SpringBoot, Maven, Gradle, etc.) with JDWP enabled.
    - Breakpoint management (`debug_set_breakpoint`, `debug_set_watchpoint`)
    - Flow control (Step Over, Step Into, Step Out, Resume)
    - State inspection (Stack trace inspection, deep structured variable traversal)
    - Thread & Class inspection (`debug_list_threads`, `debug_list_classes`)
    - Dynamic modification (Change variable values at runtime)
- **Manual Debug Mode Required**: The target Java application must be started manually with JDWP options enabled.
- **Multi-session Management**: Supports debugging multiple sessions simultaneously. Every tool call requires an explicit `sessionId`.
- **Real-time Awareness**: Leveraging MCP Notifications, AI agents are immediately notified when a breakpoint is hit.

## Installation & Build

### Prerequisites
- JDK 17+ (with JDI module)

### Build fatJAR
```bash
# Build the standalone JAR (requires maven-assembly-plugin in pom.xml)
mvn clean compile assembly:single
```

## Configuration & Usage

### Startup Options
- `--transport <stdio|http>`: Set transport mode (default: `stdio`).
- `--notifications <true|false>`: Enable real-time AI notifications (default: `true`).

For detailed tool parameter analysis, please refer to the [Parameter Guide](./documents/PARAMETER_GUIDE.md).

### Usage in VSCode (Cline/Claude Dev)
Add the following configuration to your MCP settings:

```json
{
  "mcpServers": {
    "jdb-debugger": {
      "command": "java",
      "args": [
        "-jar",
        "path/to/jdb-mcp/target/jdb-mcp-1.0-SNAPSHOT-jar-with-dependencies.jar"
      ]
    }
  }
}
```

### Multi-session & Explicit ID
JDB-MCP enforces **Explicit ID Calling**. 
1. `debug_attach` returns a `sessionId`.
2. All subsequent calls (e.g., `debug_resume`, `debug_list_vars`) **must** include this `sessionId`.

### How to Debug (Attach Mode)
**Important**: You must manually start your target Java application with JDWP enabled.

1. Start your project:
   ```bash
   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar your-app.jar
   ```
2. In the AI chat, request: `Attach to localhost:5005 with sessionId 'my-app' and debug...`

## Tool List
- `debug_attach`: Attach to an existing debug port via Socket. Returns a `sessionId`.
- `debug_list_threads`: List all threads and their status.
- `debug_list_classes`: List loaded classes with optional filtering.
- `debug_list_sessions`: List all active debug sessions.
- `debug_kill_session`: Terminate a session by ID.
- `debug_list_vars`: List variables (supports `threadName` filter).
- `debug_get_var`: Get detailed info for a specific variable.
- `debug_set_var`: Modify runtime variables (supports `threadName` and `frameIndex`).
- ...and more (see `tools/list`).

## TODO List

- [ ] **Implement `debug_launch`**: Enable "out-of-the-box" experience by allowing users to launch and debug Java programs directly through the MCP.
- [ ] **Multi-session Support**: Refactor to allow one MCP instance to manage and debug multiple target programs simultaneously.
- [ ] **To be added**: More features based on community feedback.

## License
Open-sourced under the [MIT License](LICENSE).
