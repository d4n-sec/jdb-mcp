package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jdi.*;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequestManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Base class for JDI-based debuggers, containing common logic shared across JDK versions.
 */
public abstract class AbstractJdiDebugger {
    protected static final ObjectMapper mapper = new ObjectMapper();
    protected VirtualMachine vm;
    protected Process process;
    protected final BlockingQueue<String> eventQueue = new LinkedBlockingQueue<>();
    protected final StringBuilder outputBuffer = new StringBuilder();
    protected Consumer<String> eventListener;

    protected final java.util.Map<String, java.util.Set<Integer>> deferredBreakpoints = new java.util.concurrent.ConcurrentHashMap<>();
    protected boolean running = true;
    protected volatile ThreadReference lastSuspendedThread;

    public ThreadReference getLastSuspendedThread() {
        return lastSuspendedThread;
    }

    public void setEventListener(Consumer<String> listener) {
        this.eventListener = listener;
    }

    public BlockingQueue<String> getEventQueue() {
        return eventQueue;
    }

    public String getOutput() {
        synchronized (outputBuffer) {
            String out = outputBuffer.toString();
            outputBuffer.setLength(0);
            return out;
        }
    }

    public VirtualMachine getVm() {
        return vm;
    }

    public boolean isAlive() {
        if (vm == null) return false;
        try {
            vm.allThreads();
            return true;
        } catch (VMDisconnectedException e) {
            return false;
        }
    }

    public ObjectNode setBreakpoint(String className, int line) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.put("className", className);
        result.put("line", line);

        if (vm == null) {
            java.util.Set<Integer> lines = deferredBreakpoints.get(className);
            if (lines == null) {
                lines = new java.util.HashSet<>();
                deferredBreakpoints.put(className, lines);
            }
            lines.add(line);
            result.put("status", "deferred");
            result.put("reason", "Not attached to VM");
            return result;
        }

        // Add current context (first suspended thread) for convenience
        ThreadReference contextThread = null;
        for (ThreadReference thread : vm.allThreads()) {
            if (thread.isSuspended()) {
                contextThread = thread;
                result.set("currentContext", JdiStateMapper.getThreadState(thread, 5, 0));
                break;
            }
        }
        
        // Add current VM state context
        result.set("vmState", JdiStateMapper.getVmState(vm));

        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) {
            java.util.Set<Integer> lines = deferredBreakpoints.get(className);
            if (lines == null) {
                lines = new java.util.HashSet<>();
                deferredBreakpoints.put(className, lines);
            }
            lines.add(line);
            // Create ClassPrepareRequest to set it when loaded
            com.sun.jdi.request.ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
            cpr.addClassFilter(className);
            cpr.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_ALL);
            cpr.enable();
            
            result.put("status", "deferred");
            result.put("reason", "Class '" + className + "' not loaded yet. Breakpoint will be set when class is loaded.");
            if (contextThread != null) {
                result.put("contextInfo", "Attempted to set breakpoint from thread '" + contextThread.name() + "' at " + contextThread.frame(0).location());
            }
            return result;
        }
        
        boolean set = false;
        List<String> locations = new java.util.ArrayList<>();
        Exception lastError = null;
        for (ReferenceType type : classes) {
            try {
                List<Location> locs = type.locationsOfLine(line);
                if (!locs.isEmpty()) {
                    for (Location loc : locs) {
                        if (!isBreakpointAlreadySet(loc)) {
                            BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
                            bp.enable();
                            set = true;
                            locations.add(loc.toString());
                        } else {
                            set = true; // Already set
                            locations.add(loc.toString() + " (already set)");
                        }
                    }
                }
            } catch (AbsentInformationException e) {
                result.put("warning", "Absent line information for class: " + type.name());
                lastError = e;
            } catch (Exception e) {
                lastError = e;
            }
        }

        if (set) {
            result.put("status", "success");
            ArrayNode locArray = result.putArray("locations");
            for (String loc : locations) locArray.add(loc);
        } else {
            result.put("status", "error");
            String reason = "No executable code found at line " + line + " in class " + className + ".";
            if (lastError != null) {
                reason += " Error: " + lastError.getMessage();
            } else {
                reason += " This might be an interface, an empty line, or a comment.";
            }
            result.put("reason", reason);
            if (contextThread != null) {
                try {
                    result.put("errorContext", "Failed while thread '" + contextThread.name() + "' was at " + contextThread.frame(0).location());
                } catch (Exception e) {}
            }
        }
        return result;
    }

    public JsonNode resume() throws Exception {
        if (vm == null) throw new Exception("Not attached");
        
        ObjectNode result = mapper.createObjectNode();
        ArrayNode resumingThreads = result.putArray("resumingFrom");
        
        for (ThreadReference thread : vm.allThreads()) {
            if (thread.isSuspended()) {
                ObjectNode threadInfo = (ObjectNode) JdiStateMapper.getThreadState(thread, 1, 0);
                
                // Add breakpoint info if any
                try {
                    if (!thread.frames().isEmpty()) {
                        Location loc = thread.frame(0).location();
                        for (BreakpointRequest bp : vm.eventRequestManager().breakpointRequests()) {
                            if (bp.isEnabled() && bp.location().equals(loc)) {
                                threadInfo.put("atBreakpoint", bp.location().toString());
                                break;
                            }
                        }
                    }
                } catch (Exception e) {}
                
                resumingThreads.add(threadInfo);
            }
        }
        
        vm.resume();
        result.put("message", "VM resumed");
        result.set("vmState", JdiStateMapper.getVmState(vm));
        return result;
    }

    public JsonNode listBreakpoints() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode bpList = result.putArray("activeBreakpoints");
        if (vm != null) {
            for (BreakpointRequest bp : vm.eventRequestManager().breakpointRequests()) {
                ObjectNode node = bpList.addObject();
                node.put("type", "breakpoint");
                node.put("location", bp.location().toString());
                node.put("className", bp.location().declaringType().name());
                node.put("line", bp.location().lineNumber());
                node.put("enabled", bp.isEnabled());
                
                // Find threads suspended at this breakpoint
                ArrayNode threadsAtBp = node.putArray("suspendedThreads");
                for (ThreadReference thread : vm.allThreads()) {
                    if (thread.isSuspended()) {
                        try {
                            if (!thread.frames().isEmpty() && thread.frame(0).location().equals(bp.location())) {
                                threadsAtBp.add(JdiStateMapper.getThreadState(thread, 1, -1));
                            }
                        } catch (Exception e) {}
                    }
                }
            }
            // Add watchpoints
            ArrayNode wpList = result.putArray("watchpoints");
            for (com.sun.jdi.request.AccessWatchpointRequest wp : vm.eventRequestManager().accessWatchpointRequests()) {
                ObjectNode node = wpList.addObject();
                node.put("type", "access_watchpoint");
                node.put("field", wp.field().toString());
                node.put("enabled", wp.isEnabled());
            }
            for (com.sun.jdi.request.ModificationWatchpointRequest wp : vm.eventRequestManager().modificationWatchpointRequests()) {
                ObjectNode node = wpList.addObject();
                node.put("type", "modification_watchpoint");
                node.put("field", wp.field().toString());
                node.put("enabled", wp.isEnabled());
            }
        }
        
        // Add deferred breakpoints
        ObjectNode deferred = result.putObject("deferredBreakpoints");
        for (java.util.Map.Entry<String, java.util.Set<Integer>> entry : deferredBreakpoints.entrySet()) {
            ArrayNode lines = deferred.putArray(entry.getKey());
            for (Integer line : entry.getValue()) lines.add(line);
        }
        
        return result;
    }

    public JsonNode listThreads() {
        ArrayNode result = mapper.createArrayNode();
        if (vm != null) {
            for (ThreadReference thread : vm.allThreads()) {
                result.add(JdiStateMapper.getThreadState(thread, 1, -1));
            }
        }
        return result;
    }

    public JsonNode listClasses(String filter) {
        ArrayNode result = mapper.createArrayNode();
        if (vm != null) {
            List<ReferenceType> classes = vm.allClasses();
            int count = 0;
            for (ReferenceType type : classes) {
                boolean match = false;
                if (filter == null) {
                    match = true;
                } else {
                    String name = type.name();
                    if (filter.endsWith("*")) {
                        String prefix = filter.substring(0, filter.length() - 1);
                        if (name.startsWith(prefix)) match = true;
                    } else if (filter.startsWith("*")) {
                        String suffix = filter.substring(1);
                        if (name.endsWith(suffix)) match = true;
                    } else {
                        if (name.contains(filter)) match = true;
                    }
                }

                if (match) {
                    result.add(type.name());
                    if (++count > 1000) break;
                }
            }
        }
        return result;
    }

    public JsonNode listMethods(String className) throws Exception {
        if (vm == null) throw new Exception("Not attached");
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) throw new Exception("Class not found: " + className);
        
        ArrayNode result = mapper.createArrayNode();
        for (Method method : classes.get(0).methods()) {
            result.add(method.name() + method.signature());
        }
        return result;
    }

    public JsonNode getVariables(String threadName, int frameIndex, String scope, int maxDepth) throws Exception {
        ArrayNode threadsNode = mapper.createArrayNode();
        boolean listAllThreads = "ALL".equalsIgnoreCase(threadName);
        
        if (threadName == null && !listAllThreads) {
            throw new Exception("threadName is required.");
        }

        for (ThreadReference thread : vm.allThreads()) {
            if (!listAllThreads && !thread.name().equals(threadName)) {
                continue;
            }
            if (thread.isSuspended()) {
                ObjectNode threadNode = threadsNode.addObject();
                threadNode.put("thread", thread.name());
                ArrayNode framesNode = threadNode.putArray("frames");
                
                try {
                    List<StackFrame> frames = thread.frames();
                    if (frameIndex < frames.size()) {
                        StackFrame frame = frames.get(frameIndex);
                        ObjectNode frameNode = framesNode.addObject();
                        frameNode.put("location", frame.location().toString());
                        frameNode.put("frameIndex", frameIndex);
                        ObjectNode varsNode = frameNode.putObject("variables");
                        
                        boolean showLocals = "LOCAL".equalsIgnoreCase(scope) || "ALL".equalsIgnoreCase(scope);
                        boolean showThis = "THIS".equalsIgnoreCase(scope) || "ALL".equalsIgnoreCase(scope);

                        // Local variables
                        if (showLocals) {
                             try {
                                List<LocalVariable> visibleVars = frame.visibleVariables();
                                if (visibleVars != null && !visibleVars.isEmpty()) {
                                    Map<LocalVariable, Value> vars = frame.getValues(visibleVars);
                                    for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
                                        varsNode.set(entry.getKey().name(), JdiValueConverter.convertValue(entry.getValue(), maxDepth));
                                    }
                                }
                            } catch (AbsentInformationException e) {
                                frameNode.put("note", "No debug info available for locals");
                            }
                        }
                        
                        // 'this' fields
                        if (showThis) {
                             ObjectReference thisObj = frame.thisObject();
                             if (thisObj != null) {
                                 ReferenceType type = thisObj.referenceType();
                                 varsNode.set("this", JdiValueConverter.convertValue(thisObj, maxDepth));
                                 // Optionally list fields directly if needed, but showing 'this' object structure is usually enough if recursion works.
                                 // However, JdiValueConverter might limit depth. Let's explicitly list fields as "this.fieldName" for better visibility if maxDepth is low.
                                 // Actually, let's trust JdiValueConverter for 'this' object, but maybe user wants flat list.
                                 // Let's just return 'this' object.
                             }
                        }
                    } else {
                        threadNode.put("error", "Frame index " + frameIndex + " out of bounds.");
                    }
                } catch (Exception e) {
                    threadNode.put("error", "Error accessing frames: " + e.getMessage());
                }
            } else if (!listAllThreads) {
                 throw new Exception("Thread '" + threadName + "' is not suspended.");
            }
        }
        
        if (threadsNode.isEmpty() && !listAllThreads) {
             throw new Exception("Thread '" + threadName + "' not found.");
        }
        
        return threadsNode;
    }

    public JsonNode getVariable(String varName, int maxDepth) throws Exception {
        for (ThreadReference thread : vm.allThreads()) {
            if (thread.isSuspended()) {
                List<StackFrame> frames = thread.frames();
                if (!frames.isEmpty()) {
                    StackFrame frame = frames.get(0);
                    try {
                        LocalVariable var = frame.visibleVariableByName(varName);
                        if (var != null) {
                            Value val = frame.getValue(var);
                            return JdiValueConverter.convertValue(val, maxDepth);
                        }
                    } catch (AbsentInformationException e) {
                    } catch (Exception e) {
                        System.err.println("Error getting variable " + varName + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                }
            }
        }
        return null;
    }

    public void setVariableValue(ThreadReference thread, String varName, String value, int frameIndex) throws Exception {
        StackFrame frame = thread.frame(frameIndex);
        LocalVariable var = frame.visibleVariableByName(varName);
        if (var == null) throw new Exception("Variable not found: " + varName);
        
        Value newValue = createValue(value, var.typeName());
        frame.setValue(var, newValue);
    }

    private Value createValue(String value, String type) {
        if ("int".equals(type) || "java.lang.Integer".equals(type)) return vm.mirrorOf(Integer.parseInt(value));
        if ("long".equals(type) || "java.lang.Long".equals(type)) return vm.mirrorOf(Long.parseLong(value));
        if ("boolean".equals(type) || "java.lang.Boolean".equals(type)) return vm.mirrorOf(Boolean.parseBoolean(value));
        if ("double".equals(type) || "java.lang.Double".equals(type)) return vm.mirrorOf(Double.parseDouble(value));
        if ("float".equals(type) || "java.lang.Float".equals(type)) return vm.mirrorOf(Float.parseFloat(value));
        if ("java.lang.String".equals(type)) return vm.mirrorOf(value);
        throw new RuntimeException("Unsupported type for set: " + type);
    }

    public JsonNode getStackTrace(ThreadReference thread) throws Exception {
        ObjectNode result = mapper.createObjectNode();
        result.set("threadInfo", JdiStateMapper.getThreadState(thread, 1, -1));
        
        ArrayNode framesNode = result.putArray("stackTrace");
        for (StackFrame frame : thread.frames()) {
            Location loc = frame.location();
            ObjectNode frameNode = framesNode.addObject();
            frameNode.put("location", loc.toString());
            frameNode.put("className", loc.declaringType().name());
            frameNode.put("method", loc.method().name());
            frameNode.put("line", loc.lineNumber());
            try {
                frameNode.put("source", loc.sourceName());
            } catch (AbsentInformationException e) {
                frameNode.put("source", "unknown");
            }
        }
        return result;
    }

    public String getSource(String className) throws Exception {
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) throw new Exception("Class not found: " + className);
        try {
            return classes.get(0).sourceName();
        } catch (AbsentInformationException e) {
            return "Source information not available";
        }
    }

    public void sendInput(String input) {
        if (process != null) {
            try {
                process.getOutputStream().write((input + "\n").getBytes());
                process.getOutputStream().flush();
            } catch (java.io.IOException e) {
                System.err.println("Error sending input: " + e.getMessage());
            }
        }
    }

    public void setWatchpoint(String className, String fieldName, boolean access, boolean modification) throws Exception {
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) throw new Exception("Class not found: " + className);
        ReferenceType type = classes.get(0);
        Field field = type.fieldByName(fieldName);
        if (field == null) throw new Exception("Field not found: " + fieldName);

        if (access) {
            com.sun.jdi.request.AccessWatchpointRequest awp = vm.eventRequestManager().createAccessWatchpointRequest(field);
            awp.enable();
        }
        if (modification) {
            com.sun.jdi.request.ModificationWatchpointRequest mwp = vm.eventRequestManager().createModificationWatchpointRequest(field);
            mwp.enable();
        }
    }

    protected boolean isBreakpointAlreadySet(Location loc) {
        if (vm == null) return false;
        List<BreakpointRequest> requests = vm.eventRequestManager().breakpointRequests();
        for (BreakpointRequest req : requests) {
            if (req.location().equals(loc)) {
                return true;
            }
        }
        return false;
    }

    public abstract void terminate();

    public abstract com.sun.jdi.event.Event stepOverAndWait(ThreadReference thread) throws Exception;

    public abstract com.sun.jdi.event.Event stepIntoAndWait(ThreadReference thread) throws Exception;

    public abstract com.sun.jdi.event.Event stepOutAndWait(ThreadReference thread) throws Exception;
    
    protected void logEvent(String msg) {
        eventQueue.offer(msg);
        if (eventListener != null) {
            eventListener.accept(msg);
        }
    }
}
