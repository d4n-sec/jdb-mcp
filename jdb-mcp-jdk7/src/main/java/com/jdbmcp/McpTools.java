package com.jdbmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility class for defining and listing MCP tools.
 * Simplified to support single session and attach mode only.
 */

public class McpTools {

    public static ObjectNode listTools(ObjectMapper mapper) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");

        new ToolRegistry(toolsArray)
            .add("debug_attach", "Attach to a running Java VM via socket", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("host", "string", "The hostname of the remote VM (default: localhost)")
                     .property("port", "integer", "The JDWP port of the remote VM")
                     .required("port");
                }
            })

            .add("debug_set_breakpoint", "Set a breakpoint at a specific line in a class. Returns success status and current thread/stack/class context.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("className", "string", "The fully qualified name of the class")
                     .property("line", "integer", "The line number to set the breakpoint at")
                     .required("className", "line");
                }
            })

            .add("debug_list_breakpoints", "List all breakpoints and watchpoints in the current session. Includes thread/stack/class context for active breakpoints.")

            .add("debug_resume", "Resume the execution of the debugged VM. Returns the state (thread/stack/class) from which it is resuming.")

            .add("debug_continue", "Continue the execution of the debugged VM until the next breakpoint or termination. Returns the state (thread/stack/class) from which it is resuming.")

            .add("debug_step_over", "Step over the current line of code. Returns the new location, stack trace, and local variables.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("threadName", "string", "Optional: The name of the thread to step.")
                     .property("smartStep", "boolean", "Optional: If true, automatically selects the last suspended or most appropriate thread when threadName is missing.");
                }
            })

            .add("debug_step_into", "Step into the current method call. Returns the new location, stack trace, and local variables.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("threadName", "string", "Optional: The name of the thread to step.")
                     .property("smartStep", "boolean", "Optional: If true, automatically selects the last suspended or most appropriate thread when threadName is missing.");
                }
            })

            .add("debug_step_out", "Step out of the current method. Returns the new location, stack trace, and local variables.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("threadName", "string", "Optional: The name of the thread to step.")
                     .property("smartStep", "boolean", "Optional: If true, automatically selects the last suspended or most appropriate thread when threadName is missing.");
                }
            })

            .add("debug_get_stack_trace", "Get the stack trace of the currently suspended thread")

            .add("debug_get_events", "Get the latest debugger events (breakpoints, etc.)")

            .add("debug_get_output", "Get the latest standard output/error from the debugged process")

            .add("debug_list_threads", "List all threads and their current status.")

            .add("debug_list_classes", "List loaded classes in the target VM. Use filter to narrow down results (supports prefix 'com.*', suffix '*.String', or substring).", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("filter", "string", "Optional: Filter classes by name. Supports wildcards (e.g., 'com.example.*', '*.String') or substring match.");
                }
            })

            .add("debug_list_methods", "List methods in a specific class", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("className", "string", "The fully qualified name of the class")
                     .required("className");
                }
            })

            .add("debug_source", "Get the source file name for a specific class", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("className", "string", "The fully qualified name of the class")
                     .required("className");
                }
            })

            .add("debug_list_vars", "List variables in a specific stack frame. Requires threadName. Default shows only local variables. Use scope='ALL' or scope='THIS' to see non-local variables.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("threadName", "string", "The name of the thread to list variables for. Use 'ALL' to list for all threads (not recommended for large apps).")
                     .property("frameIndex", "integer", "The stack frame index (default 0).")
                     .property("scope", "string", "The scope of variables to list: 'LOCAL' (default), 'THIS' (fields of 'this'), 'ALL' (both).")
                     .property("maxDepth", "integer", "Maximum recursion depth for complex objects (default 0, max 10).")
                     .required("threadName");
                }
            })

            .add("debug_get_var", "Get detailed information about a specific variable, optionally with recursion.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("varName", "string", "The name of the variable to inspect")
                     .property("maxDepth", "integer", "Maximum recursion depth for complex objects (default 3, max 10).")
                     .required("varName");
                }
            })

            .add("debug_send_input", "Send input string to the debugged process's stdin", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("input", "string", "The input string to send")
                     .required("input");
                }
            })

            .add("debug_set_var", "Set the value of a local variable in the current stack frame.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("varName", "string", "The name of the variable")
                     .property("value", "string", "The new value for the variable")
                     .property("threadName", "string", "Optional: The name of the thread. If not provided, the first suspended thread is used.")
                     .property("frameIndex", "integer", "Optional: The stack frame index (default 0).")
                     .required("varName", "value");
                }
            })

            .add("debug_set_watchpoint", "Set an access or modification watchpoint for a field in a class.", new Consumer<ToolBuilder>() {
                @Override
                public void accept(ToolBuilder t) {
                    t.property("className", "string", "The fully qualified name of the class")
                     .property("fieldName", "string", "The name of the field")
                     .property("access", "boolean", "Trigger on field access")
                     .property("modification", "boolean", "Trigger on field modification")
                     .required("className", "fieldName");
                }
            })

            .add("debug_detach", "Terminate the current debug session and detach");

        return result;
    }

    /**
     * Helper class to manage tool registration in the tools array.
     */
    private static class ToolRegistry {
        private final ArrayNode toolsArray;

        ToolRegistry(ArrayNode toolsArray) {
            this.toolsArray = toolsArray;
        }

        ToolRegistry add(String name, String description) {
            return add(name, description, null);
        }

        ToolRegistry add(String name, String description, Consumer<ToolBuilder> config) {
            ObjectNode tool = toolsArray.addObject();
            tool.put("name", name);
            tool.put("description", description);
            
            ObjectNode inputSchema = tool.putObject("inputSchema");
            inputSchema.put("type", "object");
            ObjectNode properties = inputSchema.putObject("properties");

            if (config != null) {
                ToolBuilder builder = new ToolBuilder(properties, inputSchema);
                config.accept(builder);
            }
            return this;
        }
    }

    /**
     * Builder class for configuring tool properties and schemas.
     */
    private static class ToolBuilder {
        private final ObjectNode properties;
        private final ObjectNode inputSchema;

        ToolBuilder(ObjectNode properties, ObjectNode inputSchema) {
            this.properties = properties;
            this.inputSchema = inputSchema;
        }

        ToolBuilder property(String name, String type, String description) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type", type);
            if (description != null) {
                prop.put("description", description);
            }
            return this;
        }

        ToolBuilder required(String... names) {
            ArrayNode required = inputSchema.putArray("required");
            for (String name : names) {
                required.add(name);
            }
            return this;
        }
    }
}
