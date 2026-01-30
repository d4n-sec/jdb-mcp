package com.jdbmcp;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class JdiDebugger {
    private VirtualMachine vm;
    private Process process;
    private boolean running = true;
    private final java.util.concurrent.BlockingQueue<String> eventQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final StringBuilder outputBuffer = new StringBuilder();
    private com.jdbmcp.Consumer<String> eventListener;
    private final Map<String, Set<Integer>> deferredBreakpoints = new java.util.concurrent.ConcurrentHashMap<>();

    public void setEventListener(com.jdbmcp.Consumer<String> listener) {
        this.eventListener = listener;
    }

    public String getOutput() {
        synchronized (outputBuffer) {
            String out = outputBuffer.toString();
            outputBuffer.setLength(0);
            return out;
        }
    }

    public java.util.concurrent.BlockingQueue<String> getEventQueue() {
        return eventQueue;
    }

    /**
     * Attach to a running VM via socket.
     */
    public void attach(String host, int port) throws Exception {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector connector = null;
        for (AttachingConnector c : vmm.attachingConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) {
                connector = c;
                break;
            }
        }
        if (connector == null) {
            throw new RuntimeException("SocketAttach connector not found");
        }

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host);
        arguments.get("port").setValue(String.valueOf(port));

        vm = connector.attach(arguments);
        System.err.println("Attached to VM at " + host + ":" + port);
        startEventLoop();
    }

    private void startEventLoop() {
        Thread eventThread = new Thread(new Runnable() {
            @Override
            public void run() {
                EventQueue queue = vm.eventQueue();
                while (running) {
                try {
                    EventSet eventSet = queue.remove();
                    boolean shouldResume = true;
                    for (Event event : eventSet) {
                        if (event instanceof BreakpointEvent || event instanceof StepEvent || event instanceof WatchpointEvent) {
                            shouldResume = false;
                        }
                        handleEvent(event);
                    }
                    if (shouldResume) {
                        eventSet.resume();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (VMDisconnectedException e) {
                    System.err.println("VM Disconnected");
                    break;
                }
            }
        }});
        eventThread.setDaemon(true);
        eventThread.start();
    }

    private void handleEvent(Event event) {
        String msg = "";
        if (event instanceof ClassPrepareEvent) {
            ClassPrepareEvent cpe = (ClassPrepareEvent) event;
            ReferenceType type = cpe.referenceType();
            String className = type.name();
            Set<Integer> lines = deferredBreakpoints.remove(className);
            if (lines != null) {
                for (int line : lines) {
                    try {
                        List<Location> locations = type.locationsOfLine(line);
                        if (!locations.isEmpty()) {
                            Location loc = locations.get(0);
                            if (!isBreakpointAlreadySet(loc)) {
                                BreakpointRequest bpReq = vm.eventRequestManager().createBreakpointRequest(loc);
                                bpReq.enable();
                                System.err.println("Set deferred breakpoint in " + className + " at line " + line);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to set deferred breakpoint in " + className + " at line " + line + ": " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                }
            }
        } else if (event instanceof BreakpointEvent) {
            BreakpointEvent be = (BreakpointEvent) event;
            msg = "Breakpoint hit at: " + be.location();
            System.err.println(msg);
            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        } else if (event instanceof StepEvent) {
            StepEvent se = (StepEvent) event;
            msg = "Step completed at: " + se.location();
            System.err.println(msg);
            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        } else if (event instanceof WatchpointEvent) {
            WatchpointEvent we = (WatchpointEvent) event;
            msg = (we instanceof ModificationWatchpointEvent ? "Modification" : "Access") +
                  " watchpoint hit at: " + we.location() +
                  " for field: " + we.field().name() +
                  (we instanceof ModificationWatchpointEvent ? " new value: " + ((ModificationWatchpointEvent) we).valueToBe() : "");
            System.err.println(msg);
            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        } else if (event instanceof VMDisconnectEvent) {
            running = false;
            msg = "VM Disconnected";
            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        }
    }

    private boolean isBreakpointAlreadySet(Location loc) {
        if (vm == null) return false;
        List<BreakpointRequest> requests = vm.eventRequestManager().breakpointRequests();
        for (BreakpointRequest req : requests) {
            if (req.location().equals(loc)) {
                return true;
            }
        }
        return false;
    }

    public void setBreakpoint(String className, int line) throws Exception {
        if (vm == null) {
            System.err.println("VM not started yet. Adding deferred breakpoint for " + className + ":" + line);
            Set<Integer> set = deferredBreakpoints.get(className);
            if (set == null) {
                set = new java.util.HashSet<>();
                deferredBreakpoints.put(className, set);
            }
            set.add(line);
            return;
        }
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) {
            Set<Integer> lines = deferredBreakpoints.get(className);
            if (lines == null) {
                lines = new java.util.HashSet<>();
                deferredBreakpoints.put(className, lines);
            }
            if (!lines.contains(line)) {
                System.err.println("Class " + className + " not loaded yet. Adding deferred breakpoint.");
                lines.add(line);
                
                // Check if a ClassPrepareRequest already exists for this class
                boolean exists = false;
                for (ClassPrepareRequest r : vm.eventRequestManager().classPrepareRequests()) {
                    if (r.getProperty("className") != null && r.getProperty("className").equals(className)) {
                        exists = true;
                        break;
                    }
                }
                
                if (!exists) {
                    ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
                    cpr.addClassFilter(className);
                    cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    cpr.putProperty("className", className);
                    cpr.enable();
                }
            }
        } else {
            for (ReferenceType type : classes) {
                List<Location> locations = type.locationsOfLine(line);
                if (!locations.isEmpty()) {
                    Location loc = locations.get(0);
                    if (!isBreakpointAlreadySet(loc)) {
                        BreakpointRequest bpReq = vm.eventRequestManager().createBreakpointRequest(loc);
                        bpReq.enable();
                        System.err.println("Set breakpoint in " + className + " at line " + line);
                    } else {
                        System.err.println("Breakpoint already exists in " + className + " at line " + line);
                    }
                }
            }
        }
    }

    public com.fasterxml.jackson.databind.JsonNode listBreakpoints() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode result = mapper.createArrayNode();

        // 1. Active Breakpoints
        if (vm != null) {
            List<BreakpointRequest> bps = vm.eventRequestManager().breakpointRequests();
            for (BreakpointRequest bp : bps) {
                com.fasterxml.jackson.databind.node.ObjectNode node = result.addObject();
                node.put("type", "breakpoint");
                node.put("status", bp.isEnabled() ? "enabled" : "disabled");
                try {
                    node.put("location", bp.location().toString());
                    node.put("className", bp.location().declaringType().name());
                    node.put("line", bp.location().lineNumber());
                } catch (Exception e) {
                    node.put("location", "unknown");
                }
            }

            // 2. Active Watchpoints
            List<AccessWatchpointRequest> awps = vm.eventRequestManager().accessWatchpointRequests();
            for (AccessWatchpointRequest awp : awps) {
                com.fasterxml.jackson.databind.node.ObjectNode node = result.addObject();
                node.put("type", "access_watchpoint");
                node.put("status", awp.isEnabled() ? "enabled" : "disabled");
                node.put("className", awp.field().declaringType().name());
                node.put("field", awp.field().name());
            }
            List<ModificationWatchpointRequest> mwps = vm.eventRequestManager().modificationWatchpointRequests();
            for (ModificationWatchpointRequest mwp : mwps) {
                com.fasterxml.jackson.databind.node.ObjectNode node = result.addObject();
                node.put("type", "modification_watchpoint");
                node.put("status", mwp.isEnabled() ? "enabled" : "disabled");
                node.put("className", mwp.field().declaringType().name());
                node.put("field", mwp.field().name());
            }
        }

        // 3. Deferred Breakpoints
        for (Map.Entry<String, Set<Integer>> entry : deferredBreakpoints.entrySet()) {
            String className = entry.getKey();
            for (Integer line : entry.getValue()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = result.addObject();
                node.put("type", "breakpoint");
                node.put("status", "deferred");
                node.put("className", className);
                node.put("line", line);
            }
        }

        return result;
    }

    public void setWatchpoint(String className, String fieldName, boolean access, boolean modification) throws Exception {
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) throw new RuntimeException("Class not found: " + className);
        
        for (ReferenceType type : classes) {
            Field field = type.fieldByName(fieldName);
            if (field == null) continue;
            
            if (access && vm.canWatchFieldAccess()) {
                // Check if access watchpoint already exists
                boolean exists = false;
                for (AccessWatchpointRequest r : vm.eventRequestManager().accessWatchpointRequests()) {
                    if (r.field().equals(field)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    AccessWatchpointRequest awp = vm.eventRequestManager().createAccessWatchpointRequest(field);
                    awp.enable();
                    System.err.println("Set access watchpoint for " + className + "." + fieldName);
                }
            }
            if (modification && vm.canWatchFieldModification()) {
                // Check if modification watchpoint already exists
                boolean exists = false;
                for (ModificationWatchpointRequest r : vm.eventRequestManager().modificationWatchpointRequests()) {
                    if (r.field().equals(field)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    ModificationWatchpointRequest mwp = vm.eventRequestManager().createModificationWatchpointRequest(field);
                    mwp.enable();
                    System.err.println("Set modification watchpoint for " + className + "." + fieldName);
                }
            }
        }
    }

    public void stepOver(ThreadReference thread) {
        step(thread, StepRequest.STEP_OVER);
    }

    public void stepInto(ThreadReference thread) {
        step(thread, StepRequest.STEP_INTO);
    }

    public void stepOut(ThreadReference thread) {
        step(thread, StepRequest.STEP_OUT);
    }

    private void step(ThreadReference thread, int depth) {
        List<StepRequest> requests = vm.eventRequestManager().stepRequests();
        for (StepRequest req : requests) {
            if (req.thread().equals(thread)) {
                vm.eventRequestManager().deleteEventRequest(req);
            }
        }
        StepRequest request = vm.eventRequestManager().createStepRequest(thread, StepRequest.STEP_LINE, depth);
        request.addCountFilter(1);
        request.enable();
    }

    public String getStackTrace(ThreadReference thread) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("Stack Trace for Thread: ").append(thread.name()).append("\n");
        List<StackFrame> frames = thread.frames();
        for (int i = 0; i < frames.size(); i++) {
            StackFrame frame = frames.get(i);
            sb.append("  Frame ").append(i).append(": ").append(frame.location()).append("\n");
        }
        return sb.toString();
    }

    public void setVariableValue(ThreadReference thread, String varName, String newValue, int frameIndex) throws Exception {
        List<StackFrame> frames = thread.frames();
        if (frames.isEmpty()) throw new RuntimeException("No stack frames available");
        if (frameIndex < 0 || frameIndex >= frames.size()) {
            throw new RuntimeException("Invalid frame index: " + frameIndex + ". Thread has " + frames.size() + " frames.");
        }
        
        StackFrame frame = frames.get(frameIndex);
        LocalVariable var;
        try {
            var = frame.visibleVariableByName(varName);
        } catch (AbsentInformationException e) {
            throw new RuntimeException("No debug information available to find variable: " + varName);
        }

        if (var == null) throw new RuntimeException("Variable '" + varName + "' not found in current frame");
        
        Type varType;
        try {
            varType = var.type();
        } catch (ClassNotLoadedException e) {
            throw new RuntimeException("Class not loaded for variable type: " + e.className());
        }

        Value value = JdiValueConverter.parseValue(vm, newValue, varType);
        if (value == null) throw new RuntimeException("Unsupported type (" + varType.name() + ") or failed to parse value for variable: " + varName);
        
        try {
            frame.setValue(var, value);
        } catch (InvalidTypeException e) {
            throw new RuntimeException("Type mismatch: " + e.getMessage());
        } catch (ClassNotLoadedException e) {
            throw new RuntimeException("Class not loaded: " + e.className());
        }
    }

    public VirtualMachine getVm() {
        return vm;
    }

    public void sendInput(String input) throws Exception {
        if (process != null) {
            java.io.OutputStream os = process.getOutputStream();
            os.write((input + "\n").getBytes());
            os.flush();
        }
    }

    public com.fasterxml.jackson.databind.JsonNode listClasses(String filter) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode result = mapper.createArrayNode();
        if (vm == null) return result;

        List<ReferenceType> classes = vm.allClasses();
        int count = 0;
        int limit = 1000; // Limit to avoid huge responses

        for (ReferenceType type : classes) {
            String name = type.name();
            if (filter == null || name.contains(filter) || (filter.endsWith("*") && name.startsWith(filter.substring(0, filter.length() - 1)))) {
                result.add(name);
                count++;
                if (count >= limit) break;
            }
        }
        return result;
    }

    public com.fasterxml.jackson.databind.JsonNode listThreads() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode result = mapper.createArrayNode();
        if (vm == null) return result;

        for (ThreadReference thread : vm.allThreads()) {
            com.fasterxml.jackson.databind.node.ObjectNode node = result.addObject();
            node.put("name", thread.name());
            node.put("status", thread.isSuspended() ? "suspended" : "running");
            if (thread.isSuspended()) {
                try {
                    List<StackFrame> frames = thread.frames();
                    if (!frames.isEmpty()) {
                        node.put("location", frames.get(0).location().toString());
                    }
                } catch (Exception e) {
                    node.put("location", "unknown");
                }
            }
        }
        return result;
    }

    public com.fasterxml.jackson.databind.JsonNode getVariables(String threadName, int maxDepth) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode threadsNode = mapper.createArrayNode();

        for (ThreadReference thread : vm.allThreads()) {
            if (threadName != null && !thread.name().equals(threadName)) {
                continue;
            }
            if (thread.isSuspended()) {
                com.fasterxml.jackson.databind.node.ObjectNode threadNode = threadsNode.addObject();
                threadNode.put("thread", thread.name());
                com.fasterxml.jackson.databind.node.ArrayNode framesNode = threadNode.putArray("frames");
                
                List<StackFrame> frames = thread.frames();
                if (!frames.isEmpty()) {
                    StackFrame frame = frames.get(0);
                    com.fasterxml.jackson.databind.node.ObjectNode frameNode = framesNode.addObject();
                    frameNode.put("location", frame.location().toString());
                    com.fasterxml.jackson.databind.node.ObjectNode varsNode = frameNode.putObject("variables");
                    
                    try {
                        List<LocalVariable> visibleVars = frame.visibleVariables();
                        if (visibleVars != null && !visibleVars.isEmpty()) {
                            Map<LocalVariable, Value> vars = frame.getValues(visibleVars);
                            for (Map.Entry<LocalVariable, Value> entry : vars.entrySet()) {
                                varsNode.set(entry.getKey().name(), JdiValueConverter.convertValue(entry.getValue(), maxDepth));
                            }
                        }
                    } catch (AbsentInformationException e) {
                        frameNode.put("note", "No debug info available");
                    } catch (Exception e) {
                        frameNode.put("note", "Error accessing variables: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                    }
                }
            }
        }
        return threadsNode;
    }

    public com.fasterxml.jackson.databind.JsonNode getVariable(String varName, int maxDepth) throws Exception {
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

    public Process getProcess() {
        return process;
    }

    public boolean isAlive() {
        return running && vm != null;
    }

    public void terminate() {
        running = false;
        if (vm != null) {
            try {
                vm.exit(0);
            } catch (Exception e) {
            }
        }
        if (process != null) {
            process.destroy();
        }
    }
}
