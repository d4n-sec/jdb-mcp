package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jdi.*;
import java.util.List;
import java.util.Map;

/**
 * Utility class for mapping JDI objects to JSON nodes.
 */
public class JdiStateMapper {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static JsonNode getThreadState(ThreadReference thread, int maxStackDepth, int maxVarDepth) {
        ObjectNode state = mapper.createObjectNode();
        state.put("threadName", thread.name());
        state.put("status", thread.isSuspended() ? "suspended" : "running");

        if (thread.isSuspended()) {
            try {
                List<StackFrame> frames = thread.frames();
                if (!frames.isEmpty()) {
                    StackFrame topFrame = frames.get(0);
                    Location loc = topFrame.location();
                    state.put("location", loc.toString());
                    state.put("className", loc.declaringType().name());
                    state.put("method", loc.method().name());
                    state.put("line", loc.lineNumber());

                            // Check if at breakpoint
                            try {
                                VirtualMachine vm = thread.virtualMachine();
                                for (com.sun.jdi.request.BreakpointRequest bp : vm.eventRequestManager().breakpointRequests()) {
                                    if (bp.isEnabled() && bp.location().equals(loc)) {
                                        state.put("atBreakpoint", true);
                                        break;
                                    }
                                }
                            } catch (Exception e) {}

                            ArrayNode stackNode = state.putArray("stackTrace");
                    for (int i = 0; i < frames.size() && i < maxStackDepth; i++) {
                        stackNode.add(frames.get(i).location().toString());
                    }

                    if (maxVarDepth >= 0) {
                        ObjectNode varsNode = state.putObject("variables");
                        try {
                            List<LocalVariable> visibleVars = topFrame.visibleVariables();
                            if (visibleVars != null) {
                                Map<LocalVariable, Value> values = topFrame.getValues(visibleVars);
                                for (Map.Entry<LocalVariable, Value> entry : values.entrySet()) {
                                    varsNode.set(entry.getKey().name(), JdiValueConverter.convertValue(entry.getValue(), maxVarDepth));
                                }
                            }
                        } catch (AbsentInformationException e) {
                            state.put("varsNote", "No debug info");
                        }
                    }
                }
            } catch (Exception e) {
                state.put("error", "Failed to retrieve state: " + e.getMessage());
            }
        }
        return state;
    }

    public static JsonNode getVmState(VirtualMachine vm) {
        ObjectNode vmState = mapper.createObjectNode();
        if (vm == null) {
            vmState.put("connected", false);
            return vmState;
        }
        vmState.put("connected", true);
        
        ArrayNode threadsNode = vmState.putArray("threads");
        boolean anySuspended = false;
        for (ThreadReference thread : vm.allThreads()) {
            threadsNode.add(getThreadState(thread, 1, -1));
            if (thread.isSuspended()) anySuspended = true;
        }
        vmState.put("isSuspended", anySuspended);
        return vmState;
    }
}
