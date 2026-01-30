package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class JdiDebugger extends AbstractJdiDebugger {
    private static class SyncStep {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Event event;
    }
    private final Map<ThreadReference, SyncStep> pendingStepFutures = new java.util.concurrent.ConcurrentHashMap<ThreadReference, SyncStep>();

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
            
            lastSuspendedThread = be.thread();

            SyncStep syncStep = pendingStepFutures.remove(be.thread());
            if (syncStep != null) {
                syncStep.event = be;
                syncStep.latch.countDown();
            }

            System.err.println(msg);
            logEvent(msg);
        } else if (event instanceof StepEvent) {
            StepEvent se = (StepEvent) event;
            msg = "Step completed at: " + se.location();
            
            lastSuspendedThread = se.thread();

            SyncStep syncStep = pendingStepFutures.remove(se.thread());
            if (syncStep != null) {
                syncStep.event = se;
                syncStep.latch.countDown();
            }

            System.err.println(msg);
            logEvent(msg);
        } else if (event instanceof WatchpointEvent) {
            WatchpointEvent we = (WatchpointEvent) event;
            msg = (we instanceof ModificationWatchpointEvent ? "Modification" : "Access") +
                  " watchpoint hit at: " + we.location() +
                  " for field: " + we.field().name() +
                  (we instanceof ModificationWatchpointEvent ? " new value: " + ((ModificationWatchpointEvent) we).valueToBe() : "");
            System.err.println(msg);
            logEvent(msg);
        } else if (event instanceof VMDisconnectEvent) {
            running = false;
            msg = "VM Disconnected";
            logEvent(msg);
        }
    }

    @Override
    public Event stepOverAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, StepRequest.STEP_OVER);
    }

    @Override
    public Event stepIntoAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, StepRequest.STEP_INTO);
    }

    @Override
    public Event stepOutAndWait(ThreadReference thread) throws Exception {
        return stepAndWait(thread, StepRequest.STEP_OUT);
    }

    private Event stepAndWait(ThreadReference thread, int depth) throws Exception {
        SyncStep syncStep = new SyncStep();
        pendingStepFutures.put(thread, syncStep);
        
        step(thread, depth);
        vm.resume();
        
        try {
            if (syncStep.latch.await(10, TimeUnit.SECONDS)) {
                return syncStep.event;
            } else {
                pendingStepFutures.remove(thread);
                System.err.println("Step timed out");
                return null;
            }
        } catch (InterruptedException e) {
            pendingStepFutures.remove(thread);
            System.err.println("Step interrupted: " + e);
            return null;
        }
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

    @Override
    public void terminate() {
        running = false;
        if (vm != null) {
            try {
                vm.dispose();
            } catch (Exception e) {
            }
        }
        if (process != null) {
            process.destroy();
        }
    }
}
