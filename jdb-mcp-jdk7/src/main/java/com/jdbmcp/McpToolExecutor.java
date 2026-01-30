package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.BreakpointEvent;
import java.util.List;

/**
 * Executes MCP tools by delegating to JdiDebugger and formatting the results for JDK7.
 */
public class McpToolExecutor {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static JsonNode execute(String name, JsonNode arguments, JdiDebugger debugger) throws Exception {
        if ("debug_set_breakpoint".equals(name)) {
            String className = arguments.get("className").asText();
            int line = arguments.get("line").asInt();
            try {
                ObjectNode bpResult = debugger.setBreakpoint(className, line);
                bpResult.set("vmState", JdiStateMapper.getVmState(debugger.getVm()));
                return bpResult;
            } catch (Exception e) {
                ObjectNode errorResult = mapper.createObjectNode();
                errorResult.put("status", "error");
                errorResult.put("message", "Failed to set breakpoint: " + e.getMessage());
                errorResult.set("vmState", JdiStateMapper.getVmState(debugger.getVm()));
                return errorResult;
            }
        } else if ("debug_list_breakpoints".equals(name)) {
            ObjectNode lbResult = mapper.createObjectNode();
            lbResult.set("breakpoints", debugger.listBreakpoints());
            lbResult.set("vmState", JdiStateMapper.getVmState(debugger.getVm()));
            return lbResult;
        } else if ("debug_resume".equals(name) || "debug_continue".equals(name)) {
            return debugger.resume();
        } else if ("debug_step_over".equals(name)) {
            return handleStep(debugger, "over", arguments);
        } else if ("debug_step_into".equals(name)) {
            return handleStep(debugger, "into", arguments);
        } else if ("debug_step_out".equals(name)) {
            return handleStep(debugger, "out", arguments);
        } else if ("debug_list_threads".equals(name)) {
            return debugger.listThreads();
        } else if ("debug_list_vars".equals(name)) {
            return debugger.getVariables(
                arguments.has("threadName") ? arguments.get("threadName").asText() : null,
                arguments.has("frameIndex") ? arguments.get("frameIndex").asInt() : 0,
                arguments.has("scope") ? arguments.get("scope").asText() : "LOCAL",
                arguments.has("maxDepth") ? arguments.get("maxDepth").asInt() : 1
            );
        } else if ("debug_set_var".equals(name)) {
            String varName = arguments.get("varName").asText();
            String value = arguments.get("value").asText();
            String threadName = arguments.has("threadName") ? arguments.get("threadName").asText() : null;
            int frameIndex = arguments.has("frameIndex") ? arguments.get("frameIndex").asInt() : 0;
            
            ensureVm(debugger);
            ThreadReference targetThread = null;
            if (threadName != null) {
                for (ThreadReference thread : debugger.getVm().allThreads()) {
                    if (thread.name().equals(threadName)) {
                        targetThread = thread;
                        break;
                    }
                }
                if (targetThread == null) throw new Exception("Thread not found: " + threadName);
                if (!targetThread.isSuspended()) throw new Exception("Thread '" + threadName + "' is not suspended.");
                debugger.setVariableValue(targetThread, varName, value, frameIndex);
            } else {
                for (ThreadReference thread : debugger.getVm().allThreads()) {
                    if (thread.isSuspended()) {
                        targetThread = thread;
                        debugger.setVariableValue(thread, varName, value, frameIndex);
                        break;
                    }
                }
            }
            
            if (targetThread != null) {
                ObjectNode setVarResult = mapper.createObjectNode();
                setVarResult.put("message", "Variable '" + varName + "' set to '" + value + "'");
                setVarResult.set("state", JdiStateMapper.getThreadState(targetThread, 1, 1));
                return setVarResult;
            } else {
                return mapper.valueToTree("No suspended threads found to set variable.");
            }
        } else if ("debug_list_classes".equals(name)) {
            return debugger.listClasses(arguments.has("filter") ? arguments.get("filter").asText() : null);
        } else if ("debug_list_methods".equals(name)) {
            return debugger.listMethods(arguments.get("className").asText());
        } else if ("debug_source".equals(name)) {
            return mapper.valueToTree(debugger.getSource(arguments.get("className").asText()));
        } else if ("debug_get_stack_trace".equals(name)) {
            ensureVm(debugger);
            for (ThreadReference thread : debugger.getVm().allThreads()) {
                if (thread.isSuspended()) {
                    return debugger.getStackTrace(thread);
                }
            }
            return mapper.valueToTree("No suspended threads found to get stack trace.");
        } else if ("debug_get_events".equals(name)) {
            ensureVm(debugger);
            java.util.List<String> events = new java.util.ArrayList<String>();
            debugger.getEventQueue().drainTo(events);
            if (events.isEmpty()) {
                return mapper.valueToTree("No new events");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < events.size(); i++) {
                    if (i > 0) sb.append("\n");
                    sb.append(events.get(i));
                }
                return mapper.valueToTree(sb.toString());
            }
        } else if ("debug_get_output".equals(name)) {
            String output = debugger.getOutput().toString();
            return mapper.valueToTree(output.isEmpty() ? "No new output" : output);
        } else if ("debug_get_var".equals(name)) {
            String varName = arguments.get("varName").asText();
            int maxDepth = arguments.has("maxDepth") ? arguments.get("maxDepth").asInt() : 3;
            JsonNode var = debugger.getVariable(varName, maxDepth);
            return var == null ? mapper.valueToTree("Variable '" + varName + "' not found or no suspended threads.") : var;
        } else if ("debug_send_input".equals(name)) {
            debugger.sendInput(arguments.get("input").asText());
            return mapper.valueToTree("Input sent");
        } else if ("debug_set_watchpoint".equals(name)) {
            ensureVm(debugger);
            String wpClassName = arguments.get("className").asText();
            String fieldName = arguments.get("fieldName").asText();
            boolean access = arguments.has("access") && arguments.get("access").asBoolean();
            boolean modification = arguments.has("modification") && arguments.get("modification").asBoolean();
            if (!access && !modification) modification = true;
            debugger.setWatchpoint(wpClassName, fieldName, access, modification);
            return mapper.valueToTree("Watchpoint set for " + wpClassName + "." + fieldName);
        } else {
            throw new Exception("Unknown tool: " + name);
        }
    }

    private static JsonNode handleStep(JdiDebugger debugger, String type, JsonNode arguments) throws Exception {
        ensureVm(debugger);
        
        ThreadReference targetThread = null;

        // Strategy 0: Explicit thread selection from arguments (Precise Targeting)
        if (arguments != null && arguments.has("threadName")) {
            String threadName = arguments.get("threadName").asText();
            for (ThreadReference thread : debugger.getVm().allThreads()) {
                if (thread.name().equals(threadName)) {
                    targetThread = thread;
                    break;
                }
            }
            if (targetThread == null) {
                throw new Exception("Specified thread not found: " + threadName);
            }
            if (!targetThread.isSuspended()) {
                throw new Exception("Specified thread '" + threadName + "' is not suspended.");
            }
        }

        // Strategy 1: Prioritize the last suspended thread (e.g. from breakpoint)
        if (targetThread == null) {
            boolean smartStep = false;
            if (arguments != null && arguments.has("smartStep")) {
                smartStep = arguments.get("smartStep").asBoolean();
            }
            
            if (!smartStep) {
                throw new Exception("Thread not specified. Provide 'threadName' or set 'smartStep' to true for automatic thread selection.");
            }

            ThreadReference lastSuspended = debugger.getLastSuspendedThread();
            if (lastSuspended != null && lastSuspended.isSuspended()) {
                targetThread = lastSuspended;
            }
        }

        // Strategy 2: If no last suspended thread, look for any suspended NON-SYSTEM thread
        if (targetThread == null) {
            for (ThreadReference thread : debugger.getVm().allThreads()) {
                if (thread.isSuspended()) {
                    // Simple heuristic: ignore "Reference Handler", "Finalizer", "Signal Dispatcher", "Common-Cleaner"
                    String name = thread.name();
                    if (!name.equals("Reference Handler") && 
                        !name.equals("Finalizer") && 
                        !name.equals("Signal Dispatcher") && 
                        !name.equals("Common-Cleaner")) {
                        targetThread = thread;
                        break;
                    }
                }
            }
        }

        // Strategy 3: Fallback to ANY suspended thread if no "interesting" thread found
        if (targetThread == null) {
            for (ThreadReference thread : debugger.getVm().allThreads()) {
                if (thread.isSuspended()) {
                    targetThread = thread;
                    break;
                }
            }
        }

        if (targetThread != null) {
            Event event = null;
            if ("over".equals(type)) {
                event = debugger.stepOverAndWait(targetThread);
            } else if ("into".equals(type)) {
                event = debugger.stepIntoAndWait(targetThread);
            } else if ("out".equals(type)) {
                event = debugger.stepOutAndWait(targetThread);
            }
            
            if (event == null) {
                throw new Exception("Step operation timed out or failed.");
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("status", "completed");
            if (event instanceof BreakpointEvent) {
                    result.put("reason", "breakpoint_hit");
            } else {
                    result.put("reason", "step_completed");
            }
            result.set("state", JdiStateMapper.getThreadState(targetThread, 5, 1));
            return result;
        }
        
        throw new Exception("No suspended threads found to step.");
    }

    private static void ensureVm(JdiDebugger debugger) throws Exception {
        if (debugger.getVm() == null) {
            throw new Exception("Debugger not attached. Call debug_attach first.");
        }
    }
}
