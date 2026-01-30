# JDB-MCP 参数指南

本文档详细解析了 JDB-MCP 服务中所有可用工具及其参数。

## 工具概览

### 1. 会话管理

#### `debug_attach`
通过 Socket 附加到正在运行的 Java 虚拟机。
- **host** (string): 远程虚拟机的名称（默认：localhost）。
- **port** (integer, **必填**): 远程虚拟机的 JDWP 端口。

#### `debug_detach`
终止当前调试会话并分离。无参数。

---

### 2. 执行控制

#### `debug_resume` / `debug_continue`
恢复被调试虚拟机的执行。无参数。

#### `debug_step_over`
单步跳过当前行。无参数。

#### `debug_step_into`
进入当前方法调用。无参数。

#### `debug_step_out`
跳出当前方法。无参数。

---

### 3. 断点与观察点

#### `debug_set_breakpoint`
在类的特定行设置断点。
- **className** (string, **必填**): 类的全限定名（例如：`com.example.Main`）。
- **line** (integer, **必填**): 行号。

#### `debug_list_breakpoints`
列出当前会话中的所有断点和观察点。无参数。

#### `debug_set_watchpoint`
为字段设置访问或修改观察点。
- **className** (string, **必填**): 类的全限定名。
- **fieldName** (string, **必填**): 要观察的字段名称。
- **access** (boolean): 在访问字段时触发。
- **modification** (boolean): 在修改字段时触发。

---

### 4. 检查与状态

#### `debug_list_threads`
列出所有线程及其当前状态（Running/Suspended）。无参数。

#### `debug_list_classes`
列出目标虚拟机中已加载的类。
- **filter** (string): 可选过滤器（例如：`com.example.*` 或 `Controller`）。

#### `debug_list_vars`
列出当前栈帧中的本地变量。
- **threadName** (string): 可选的线程名称过滤器。
- **maxDepth** (integer): 复杂对象的最大递归深度（默认 0）。

#### `debug_get_var`
获取特定变量的详细信息。
- **varName** (string, **必填**): 变量名称。
- **maxDepth** (integer): 递归深度（默认 3）。

#### `debug_get_stack_trace`
获取当前暂停线程的堆栈轨迹。无参数。

---

### 5. 变量修改

#### `debug_set_var`
设置本地变量的值。
- **varName** (string, **必填**): 变量名称。
- **value** (string, **必填**): 新值（字符串必须带引号，如 `"new value"`）。
- **threadName** (string): 可选的线程名称。
- **frameIndex** (integer): 可选的栈帧索引（默认 0）。

---

### 6. 其他工具 (计划中 / 仅限 Launch 模式)

#### `debug_get_output`
(计划中) 获取调试进程的最新标准输出/错误。
**注意**: 此工具依赖于 `debug_launch` 功能（本地进程管理），目前正在开发中。当前“Attach 模式”下暂不支持。

#### `debug_send_input`
(计划中) 向调试进程的标准输入发送字符串。
**注意**: 此工具依赖于 `debug_launch` 功能，当前“Attach 模式”下暂不支持。



