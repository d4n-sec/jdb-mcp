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
Resume the execution of the debugged VM. No parameters.

#### `debug_step_over`
Step over the current line of code. No parameters.

#### `debug_step_into`
Step into the current method call. No parameters.

#### `debug_step_out`
Step out of the current method. No parameters.

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

---

### 4. Inspection & State

#### `debug_list_threads`
List all threads and their current status (Running/Suspended). No parameters.

#### `debug_list_classes`
List loaded classes in the target VM.
- **filter** (string): Optional filter (e.g., `com.example.*` or `Controller`).

#### `debug_list_vars`
List local variables in the current stack frame.
- **threadName** (string): Optional thread name filter.
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

### 6. Miscellaneous

#### `debug_get_output`
Get the latest standard output/error from the debugged process. No parameters.

#### `debug_send_input`
Send input string to the debugged process's stdin.
- **input** (string, **required**): The string to send.

## Demo

![Demo Play](../documents/Demo_Play.gif)
