package com.jdbmcp;

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class JdiDebugger extends AbstractJdiDebugger {
    private final Map<Long, java.util.concurrent.CompletableFuture<Event>> pendingStepFutures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<com.sun.jdi.request.MethodEntryRequest, String> methodEntryFilters = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<com.sun.jdi.request.MethodExitRequest, String> methodExitFilters = new java.util.concurrent.ConcurrentHashMap<>();

    public void attach(String host, int port) throws Exception {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        AttachingConnector connector = vmm.attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host);
        arguments.get("port").setValue(String.valueOf(port));

        vm = connector.attach(arguments);
        System.err.println("Attached to VM at " + host + ":" + port);
        startEventLoop();
    }

    private void startEventLoop() {
        Thread eventThread = new Thread(() -> {
            EventQueue queue = vm.eventQueue();
            while (running) {
                try {
                    EventSet eventSet = queue.remove();
                    boolean shouldResume = true;
                    for (Event event : eventSet) {
                        if (event instanceof BreakpointEvent || event instanceof StepEvent || event instanceof WatchpointEvent
                                || event instanceof MethodEntryEvent || event instanceof MethodExitEvent) {
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
        });
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
            
            lastSuspendedThread = be.thread();

            // Check if there's a pending step future for this thread and complete it
            java.util.concurrent.CompletableFuture<Event> future = pendingStepFutures.remove(be.thread().uniqueID());
            if (future != null) {
                future.complete(be);
            }

            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        } else if (event instanceof StepEvent) {
            StepEvent se = (StepEvent) event;
            msg = "Step completed at: " + se.location();
            System.err.println(msg);

            lastSuspendedThread = se.thread();

            java.util.concurrent.CompletableFuture<Event> future = pendingStepFutures.remove(se.thread().uniqueID());
            if (future != null) {
                future.complete(se);
            } else {
                System.err.println("Warning: Received StepEvent for thread " + se.thread().name() + " (ID: " + se.thread().uniqueID() + ") but no pending future found.");
            }

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
        } else if (event instanceof MethodEntryEvent) {
            MethodEntryEvent me = (MethodEntryEvent) event;
            Method method = me.method();
            msg = "Method entry: " + method.declaringType().name() + "." + method.name() + " at " + me.location();
            System.err.println(msg);
            String filter = methodEntryFilters.get(me.request());
            boolean matched = (filter == null || filter.isEmpty() || method.name().equals(filter));
            if (matched) {
                eventQueue.offer(msg);
                if (eventListener != null) eventListener.accept(msg);
            } else {
                try { me.thread().resume(); } catch (Exception ignored) {}
            }
        } else if (event instanceof MethodExitEvent) {
            MethodExitEvent mx = (MethodExitEvent) event;
            Method method = mx.method();
            msg = "Method exit: " + method.declaringType().name() + "." + method.name();
            System.err.println(msg);
            String filter = methodExitFilters.get(mx.request());
            boolean matched = (filter == null || filter.isEmpty() || method.name().equals(filter));
            if (matched) {
                eventQueue.offer(msg);
                if (eventListener != null) eventListener.accept(msg);
            } else {
                try { mx.thread().resume(); } catch (Exception ignored) {}
            }
        } else if (event instanceof VMDisconnectEvent) {
            running = false;
            msg = "VM Disconnected";
            eventQueue.offer(msg);
            if (eventListener != null) eventListener.accept(msg);
        }
    }

    @Override
    public com.sun.jdi.event.Event stepOverAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, com.sun.jdi.request.StepRequest.STEP_LINE, com.sun.jdi.request.StepRequest.STEP_OVER);
    }

    @Override
    public com.sun.jdi.event.Event stepIntoAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, com.sun.jdi.request.StepRequest.STEP_LINE, com.sun.jdi.request.StepRequest.STEP_INTO);
    }

    @Override
    public com.sun.jdi.event.Event stepOutAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, com.sun.jdi.request.StepRequest.STEP_LINE, com.sun.jdi.request.StepRequest.STEP_OUT);
    }

    private com.sun.jdi.event.Event stepAndWait(ThreadReference thread, int size, int depth) throws Exception {
        java.util.concurrent.CompletableFuture<com.sun.jdi.event.Event> future = new java.util.concurrent.CompletableFuture<>();
        pendingStepFutures.put(thread.uniqueID(), future);

        com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
        java.util.List<com.sun.jdi.request.StepRequest> steps = erm.stepRequests();
        for (com.sun.jdi.request.StepRequest sr : steps) {
            if (sr.thread().equals(thread)) {
                erm.deleteEventRequest(sr);
            }
        }

        com.sun.jdi.request.StepRequest request = erm.createStepRequest(thread, size, depth);
        request.addCountFilter(1);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();

        vm.resume();

        try {
            return future.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            erm.deleteEventRequest(request);
            pendingStepFutures.remove(thread.uniqueID());
            System.err.println("Step operation timed out after 60 seconds. Thread: " + thread.name() + " (ID: " + thread.uniqueID() + ")");
            return null;
        }
    }

    @Override
    public void terminate() {
        running = false;
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception e) {}
        }
        if (process != null) {
            process.destroy();
        }
    }

    public com.fasterxml.jackson.databind.JsonNode getSource(String className, List<String> sourceRoots) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("className", className);

        List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) throw new Exception("Class not found: " + className);
        
        ReferenceType type = classes.get(0);
        try {
            String sourceName = type.sourceName();
            result.put("sourceName", sourceName);
            
            // Calculate relative path
            String packagePath = "";
            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                packagePath = className.substring(0, lastDot).replace('.', java.io.File.separatorChar);
            }
            
            String relativePath = packagePath.isEmpty() ? sourceName : packagePath + java.io.File.separator + sourceName;
            result.put("relativePath", relativePath);

            if (sourceRoots != null && !sourceRoots.isEmpty()) {
                for (String root : sourceRoots) {
                    java.io.File file = new java.io.File(root, relativePath);
                    if (file.exists() && file.isFile()) {
                        result.put("absolutePath", file.getAbsolutePath());
                        try {
                            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                            result.put("content", content);
                            result.put("status", "found");
                            return result;
                        } catch (java.io.IOException e) {
                            result.put("error", "Failed to read file: " + e.getMessage());
                        }
                    }
                }
                result.put("status", "not_found_in_roots");
            } else {
                result.put("status", "no_roots_provided");
            }
            
        } catch (AbsentInformationException e) {
            result.put("error", "Source information not available (compiled without debug info)");
        }
        return result;
    }

    public com.fasterxml.jackson.databind.node.ObjectNode setMethodBreakpoint(String className, String methodName) throws Exception {
        if (vm == null) throw new Exception("Not attached");
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("className", className);
        result.put("methodName", methodName);
        java.util.List<ReferenceType> classes = vm.classesByName(className);
        if (classes.isEmpty()) {
            result.put("status", "error");
            result.put("reason", "Class not found: " + className);
            return result;
        }
        ReferenceType type = classes.get(0);
        java.util.List<Method> methods = type.methodsByName(methodName);
        if (methods == null || methods.isEmpty()) {
            result.put("status", "error");
            result.put("reason", "Method not found: " + methodName);
            return result;
        }
        boolean set = false;
        java.util.List<String> locations = new java.util.ArrayList<>();
        Exception lastError = null;
        for (Method m : methods) {
            try {
                Location loc = m.location();
                if (loc != null) {
                    if (!isBreakpointAlreadySet(loc)) {
                        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
                        bp.enable();
                        set = true;
                        locations.add(loc.toString());
                    } else {
                        set = true;
                        locations.add(loc.toString() + " (already set)");
                    }
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (set) {
            result.put("status", "success");
            com.fasterxml.jackson.databind.node.ArrayNode locArray = result.putArray("locations");
            for (String loc : locations) locArray.add(loc);
        } else {
            result.put("status", "error");
            String reason = "Failed to set breakpoint at method '" + methodName + "' in class '" + className + "'.";
            if (lastError != null) {
                reason += " Error: " + lastError.getMessage();
            } else {
                reason += " Possibly missing debug info or non-executable method.";
            }
            result.put("reason", reason);
        }
        return result;
    }

    public com.fasterxml.jackson.databind.node.ObjectNode setMethodEntryRequest(String className, String methodName) throws Exception {
        if (vm == null) throw new Exception("Not attached");
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("className", className);
        if (methodName != null) result.put("methodName", methodName);
        com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
        MethodEntryRequest mer = erm.createMethodEntryRequest();
        mer.addClassFilter(className);
        mer.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        mer.enable();
        methodEntryFilters.put(mer, methodName);
        result.put("status", "enabled");
        return result;
    }

    public com.fasterxml.jackson.databind.node.ObjectNode setMethodExitRequest(String className, String methodName) throws Exception {
        if (vm == null) throw new Exception("Not attached");
        com.fasterxml.jackson.databind.node.ObjectNode result = mapper.createObjectNode();
        result.put("className", className);
        if (methodName != null) result.put("methodName", methodName);
        com.sun.jdi.request.EventRequestManager erm = vm.eventRequestManager();
        MethodExitRequest mxr = erm.createMethodExitRequest();
        mxr.addClassFilter(className);
        mxr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        mxr.enable();
        methodExitFilters.put(mxr, methodName);
        result.put("status", "enabled");
        return result;
    }
}
