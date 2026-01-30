# JDB-MCP Parameter Guide

This document provides a detailed analysis of all available tools and their parameters in the JDB-MCP server.

## Tool Overview

### 1. Session Management

#### `debug_attach`
Attach to a running Java VM via socket.
- **host** (string): The hostname of the remote VM (default: localhost).
- **port** (integer, **required**): The JDWP port of the remote VM.

#### `debug_detach`
Terminate the current debug session and detach. No parameters.

---

### 2. Execution Control

#### `debug_resume` / `debug_continue`
Resume the execution of the debugged VM. No parameters. (Note: `debug_resume` and `debug_continue` are aliases for the same operation).

#### `debug_step_over`
Step over the current line of code.
- **threadName** (string, optional): The name of the thread to step.
- **smartStep** (boolean, optional): If `true`, enables automatic thread selection (e.g. last suspended thread) when `threadName` is missing. Defaults to `false` (requires explicit `threadName` otherwise).

#### `debug_step_into`
Step into the current method call.
- **threadName** (string, optional): The name of the thread to step.
- **smartStep** (boolean, optional): If `true`, enables automatic thread selection (e.g. last suspended thread) when `threadName` is missing. Defaults to `false` (requires explicit `threadName` otherwise).

#### `debug_step_out`
Step out of the current method.
- **threadName** (string, optional): The name of the thread to step.
- **smartStep** (boolean, optional): If `true`, enables automatic thread selection (e.g. last suspended thread) when `threadName` is missing. Defaults to `false` (requires explicit `threadName` otherwise).

---

### 3. Breakpoints & Watchpoints

#### `debug_set_breakpoint`
Set a breakpoint at a specific line in a class.
- **className** (string, **required**): The fully qualified name of the class (e.g., `com.example.Main`).
- **line** (integer, **required**): The line number.

#### `debug_list_breakpoints`
List all breakpoints and watchpoints in the current session. No parameters.

#### `debug_set_watchpoint`
Set an access or modification watchpoint for a field.
- **className** (string, **required**): Fully qualified class name.
- **fieldName** (string, **required**): The name of the field to watch.
- **access** (boolean): Trigger on field access.
- **modification** (boolean): Trigger on field modification.

#### `debug_set_method_breakpoint`
Set a breakpoint at the beginning of a method.
- **className** (string, **required**): Fully qualified class name.
- **methodName** (string, **required**): The name of the method.

#### `debug_set_method_entry`
Suspend execution when a method is entered.
- **className** (string, **required**): Fully qualified class name.
- **methodName** (string, optional): Filter by method name.

#### `debug_set_method_exit`
Suspend execution when a method exits.
- **className** (string, **required**): Fully qualified class name.
- **methodName** (string, optional): Filter by method name.

---

### 4. Inspection & State

#### `debug_list_threads`
List all threads and their current status (Running/Suspended). No parameters.

#### `debug_list_classes`
List loaded classes in the target VM.
- **filter** (string): Optional filter. Supports prefix (e.g., `com.example.*`), suffix (e.g., `*.String`), or substring match.

#### `debug_list_methods`
List methods in a specific class.
- **className** (string, **required**): Fully qualified class name.

#### `debug_source`
Get the source file name and content for a specific class.
- **className** (string, **required**): Fully qualified class name.
- **sourceRoots** (string, optional): Comma-separated list of absolute paths to source root directories.

#### `debug_list_vars`
List local variables in the current stack frame.
- **threadName** (string, **required**): Filter variables by thread name (use 'ALL' for all threads).
- **frameIndex** (integer): Optional stack frame index (default 0).
- **scope** (string): Optional variable scope ('LOCAL', 'THIS', 'ALL'). Default 'LOCAL'.
- **maxDepth** (integer): Maximum recursion depth for complex objects (default 0).

#### `debug_get_var`
Get detailed information about a specific variable.
- **varName** (string, **required**): The variable name.
- **maxDepth** (integer): Recursion depth (default 3).

#### `debug_get_stack_trace`
Get the stack trace of the currently suspended thread. No parameters.

---

### 5. Variable Modification

#### `debug_set_var`
Set the value of a local variable.
- **varName** (string, **required**): The name of the variable.
- **value** (string, **required**): The new value (strings must be quoted like `"new value"`).
- **threadName** (string): Optional thread name.
- **frameIndex** (integer): Optional stack frame index (default 0).

---

### 6. Miscellaneous (Planned / Launch Mode Only)

#### `debug_get_output`
(Planned) Get the latest standard output/error from the debugged process.
**Note**: This tool relies on the `debug_launch` feature (local process management) which is currently under development. It is NOT available in the current "Attach Mode".

#### `debug_send_input`
(Planned) Send input string to the debugged process's stdin.
**Note**: This tool relies on the `debug_launch` feature and is NOT available in "Attach Mode".



