package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class McpServer {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final boolean DEBUG = "True".equalsIgnoreCase(System.getenv("JDB_MCP_DEBUG"));
    
    private static class DebugSession {
        final JdiDebugger debugger;
        final String info;

        DebugSession(JdiDebugger debugger, String info) {
            this.debugger = debugger;
            this.info = info;
        }
    }

    private DebugSession currentSession = null;
    private String transport = "stdio";
    private boolean enableNotifications = true;

    public McpServer() {
    }

    public void start(String[] args) throws Exception {
        parseArgs(args);
        
        if (enableNotifications) {
            System.err.println("Real-time AI notifications enabled.");
        }

        if ("http".equalsIgnoreCase(transport)) {
            startHttpServer();
        } else {
            startStdioServer();
        }
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--transport".equals(args[i]) && i + 1 < args.length) {
                transport = args[++i];
            } else if ("--notifications".equals(args[i]) && i + 1 < args.length) {
                enableNotifications = Boolean.parseBoolean(args[++i]);
            }
        }
    }

    private void startHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new McpHttpHandler());
        server.setExecutor(null);
        server.start();
        System.err.println("MCP Server started on http://localhost:8080 (HTTP)");
    }

    private void startStdioServer() {
        System.err.println("MCP Server started (stdio)");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonNode request = mapper.readTree(line);
                    String response = processJsonRpc(request);
                    if (response != null) {
                        System.out.println(response);
                    }
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    System.err.println("Error processing request: " + errorMsg);
                    try {
                        JsonNode request = mapper.readTree(line);
                        if (request.has("id")) {
                            JsonNode idNode = request.get("id");
                            System.out.println(createError(idNode, -32603, "Internal error: " + errorMsg));
                        }
                    } catch (Exception e2) {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Stdio connection closed: " + e.getMessage());
        }
    }

    private String processJsonRpc(JsonNode request) throws Exception {
        if (DEBUG) {
            System.err.println("[DEBUG] Incoming Request: " + request.toString());
        }
        
        JsonNode idNode = request.has("id") ? request.get("id") : null;
        String method = request.has("method") ? request.get("method").asText() : null;

        if (method == null) {
            if (idNode != null) {
                return createError(idNode, -32600, "Invalid Request: missing method");
            }
            return null; // Ignore invalid notification
        }

        // Handle notifications (requests without ID)
        if (idNode == null) {
            handleNotification(method, request.get("params"));
            return null;
        }

        String responseJson;
        if (method.equals("initialize")) {
            ObjectNode capabilities = mapper.createObjectNode();
            capabilities.putObject("tools");
            capabilities.putObject("logging");
            
            ObjectNode result = mapper.createObjectNode();
            result.put("protocolVersion", "2024-11-05");
            result.set("capabilities", capabilities);
            result.set("serverInfo", mapper.createObjectNode()
                    .put("name", "jdb-mcp-server")
                    .put("version", "1.1.1"));
            
            result.put("instructions", 
                "This is a Java Debugger MCP Server. It supports a single active debug session.\n" +
                "Key workflows:\n" +
                "1. Use 'debug_attach' to connect to a running Java process with JDWP enabled.\n" +
                "2. Breakpoints can be set before or after attaching.\n" +
                "3. Use 'debug_detach' to terminate the current session. A new session can then be started.\n" +
                "4. If an error occurs, the server will attempt to remain stable. You may need to re-attach.");
            
            responseJson = createResponse(idNode, result);
        } else if (method.equals("tools/list")) {
            responseJson = createResponse(idNode, McpTools.listTools(mapper));
        } else if (method.equals("tools/call")) {
            responseJson = handleToolCall(idNode, request.get("params"));
        } else {
            responseJson = createError(idNode, -32601, "Method not found: " + method);
        }

        if (DEBUG) {
            System.err.println("[DEBUG] Outgoing Response: " + responseJson);
        }
        return responseJson;
    }

    private void handleNotification(String method, JsonNode params) {
        if (DEBUG) {
            System.err.println("[DEBUG] Incoming Notification: " + method + (params != null ? " " + params.toString() : ""));
        }
        if ("notifications/initialized".equals(method) || "initialized".equals(method)) {
            System.err.println("Client initialized.");
        }
        // Handle other notifications if needed
    }

    class McpHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    JsonNode request = mapper.readTree(exchange.getRequestBody());
                    String response = processJsonRpc(request);
                    if (response == null) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    byte[] bytes = response.getBytes();
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } catch (Exception e) {
                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                    String error = "{\"error\": \"" + errorMsg + "\"}";
                    exchange.sendResponseHeaders(500, error.length());
                    exchange.getResponseBody().write(error.getBytes());
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private void resetSession(String info) {
        if (currentSession != null) {
            try {
                currentSession.debugger.terminate();
            } catch (Exception e) {
                System.err.println("Error terminating previous session: " + e.getMessage());
            }
        }
        JdiDebugger debugger = new JdiDebugger();
        if (enableNotifications) {
            debugger.setEventListener(msg -> {
                try {
                    sendNotification("notifications/message", mapper.createObjectNode()
                            .put("level", "info")
                            .put("description", "Debugger Event")
                            .put("data", msg));
                } catch (Exception e) {
                }
            });
        }
        currentSession = new DebugSession(debugger, info);
    }

    private JdiDebugger getDebugger() throws Exception {
        if (currentSession == null || currentSession.debugger == null) {
            throw new Exception("No active debug session. Please call debug_attach first.");
        }
        if (!currentSession.debugger.isAlive()) {
            throw new Exception("Debug session is no longer active (VM disconnected). Please re-attach.");
        }
        return currentSession.debugger;
    }

    private void ensureVm(JdiDebugger debugger) throws Exception {
        if (debugger.getVm() == null) {
            throw new Exception("Debugger VM is not initialized. Did you call debug_attach?");
        }
    }

    private String handleStep(JdiDebugger debugger, String type) throws Exception {
        for (com.sun.jdi.ThreadReference thread : debugger.getVm().allThreads()) {
            if (thread.isSuspended()) {
                switch (type) {
                    case "over": debugger.stepOver(thread); break;
                    case "into": debugger.stepInto(thread); break;
                    case "out": debugger.stepOut(thread); break;
                }
                debugger.getVm().resume();
                return "Stepping " + type + " in thread " + thread.name();
            }
        }
        return "No suspended threads found to step.";
    }

    private String handleToolCall(JsonNode idNode, JsonNode params) {
        String name = params.get("name").asText();
        JsonNode arguments = params.get("arguments");
        Object result = null;
        boolean isError = false;

        try {
            switch (name) {
            case "debug_attach": {
                String host = (arguments != null && arguments.has("host")) ? arguments.get("host").asText() : "localhost";
                int port = (arguments != null && arguments.has("port")) ? arguments.get("port").asInt() : -1;
                if (port == -1) throw new Exception("Missing required argument: port");
                
                resetSession("Attached: " + host + ":" + port);
                currentSession.debugger.attach(host, port);
                
                result = "Attached to " + host + ":" + port;
                break;
            }
            case "debug_detach": {
                if (currentSession != null) {
                    currentSession.debugger.terminate();
                    currentSession = null;
                    result = "Session terminated and detached.";
                } else {
                    result = "No active session to detach.";
                }
                break;
            }
            case "debug_set_breakpoint":
                getDebugger().setBreakpoint(arguments.get("className").asText(), arguments.get("line").asInt());
                result = "Breakpoint set";
                break;
            case "debug_list_breakpoints":
                result = getDebugger().listBreakpoints();
                break;
            case "debug_resume":
            case "debug_continue": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                dbg.getVm().resume();
                result = "Resumed";
                break;
            }
            case "debug_step_over": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                result = handleStep(dbg, "over");
                break;
            }
            case "debug_step_into": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                result = handleStep(dbg, "into");
                break;
            }
            case "debug_step_out": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                result = handleStep(dbg, "out");
                break;
            }
            case "debug_get_stack_trace": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                for (com.sun.jdi.ThreadReference thread : dbg.getVm().allThreads()) {
                    if (thread.isSuspended()) {
                        result = dbg.getStackTrace(thread);
                        break;
                    }
                }
                if (result == null) result = "No suspended threads found to get stack trace.";
                break;
            }
            case "debug_get_events": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                java.util.List<String> events = new java.util.ArrayList<>();
                dbg.getEventQueue().drainTo(events);
                result = events.isEmpty() ? "No new events" : String.join("\n", events);
                break;
            }
            case "debug_get_output": {
                JdiDebugger dbg = getDebugger();
                result = dbg.getOutput();
                if (result.toString().isEmpty()) result = "No new output";
                break;
            }
            case "debug_list_threads": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                result = dbg.listThreads();
                break;
            }
            case "debug_list_classes": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                String filter = (arguments != null && arguments.has("filter")) ? arguments.get("filter").asText() : null;
                result = dbg.listClasses(filter);
                break;
            }
            case "debug_list_vars": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                String threadName = (arguments != null && arguments.has("threadName")) ? arguments.get("threadName").asText() : null;
                int maxDepth = (arguments != null && arguments.has("maxDepth")) ? arguments.get("maxDepth").asInt() : 0;
                if (maxDepth > 10) maxDepth = 10;
                JsonNode vars = dbg.getVariables(threadName, maxDepth);
                result = (vars == null || vars.size() == 0) ? "No suspended threads found or no variables visible." : vars;
                break;
            }
            case "debug_get_var": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                String varName = arguments.get("varName").asText();
                int maxDepth = (arguments != null && arguments.has("maxDepth")) ? arguments.get("maxDepth").asInt() : 3;
                if (maxDepth > 10) maxDepth = 10;
                JsonNode var = dbg.getVariable(varName, maxDepth);
                result = var == null ? "Variable '" + varName + "' not found or no suspended threads." : var;
                break;
            }
            case "debug_send_input": {
                JdiDebugger dbg = getDebugger();
                dbg.sendInput(arguments.get("input").asText());
                result = "Input sent";
                break;
            }
            case "debug_set_var": {
                String varName = arguments.get("varName").asText();
                String value = arguments.get("value").asText();
                String threadName = arguments.has("threadName") ? arguments.get("threadName").asText() : null;
                int frameIndex = arguments.has("frameIndex") ? arguments.get("frameIndex").asInt() : 0;
                
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                
                com.sun.jdi.ThreadReference targetThread = null;
                if (threadName != null) {
                    for (com.sun.jdi.ThreadReference thread : dbg.getVm().allThreads()) {
                        if (thread.name().equals(threadName)) {
                            targetThread = thread;
                            break;
                        }
                    }
                    if (targetThread == null) throw new Exception("Thread not found: " + threadName);
                    if (!targetThread.isSuspended()) throw new Exception("Thread '" + threadName + "' is not suspended.");
                    dbg.setVariableValue(targetThread, varName, value, frameIndex);
                    result = "Variable '" + varName + "' set to '" + value + "' in thread '" + threadName + "' at frame " + frameIndex;
                } else {
                    for (com.sun.jdi.ThreadReference thread : dbg.getVm().allThreads()) {
                        if (thread.isSuspended()) {
                            dbg.setVariableValue(thread, varName, value, frameIndex);
                            result = "Variable '" + varName + "' set to '" + value + "' in thread '" + thread.name() + "' at frame " + frameIndex;
                            break;
                        }
                    }
                }
                
                if (result == null) result = "No suspended threads found to set variable.";
                break;
            }
            case "debug_set_watchpoint": {
                JdiDebugger dbg = getDebugger();
                ensureVm(dbg);
                String wpClassName = arguments.get("className").asText();
                String fieldName = arguments.get("fieldName").asText();
                boolean access = arguments.has("access") && arguments.get("access").asBoolean();
                boolean modification = arguments.has("modification") && arguments.get("modification").asBoolean();
                if (!access && !modification) modification = true;
                dbg.setWatchpoint(wpClassName, fieldName, access, modification);
                result = "Watchpoint set for " + wpClassName + "." + fieldName;
                break;
            }
            default:
                throw new Exception("Invalid tool name: " + name);
            }
        } catch (Exception e) {
            result = "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            isError = true;
        }

        ObjectNode response = mapper.createObjectNode();
        ArrayNode content = response.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        
        if (result instanceof JsonNode) {
            textContent.put("text", result.toString());
        } else {
            textContent.put("text", String.valueOf(result));
        }

        if (isError) {
            response.put("isError", true);
        }

        return createResponse(idNode, response);
    }

    private String createResponse(JsonNode idNode, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        }
        response.set("result", result);
        return response.toString();
    }

    private String createError(JsonNode idNode, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        }
        response.set("error", mapper.createObjectNode().put("code", code).put("message", message));
        return response.toString();
    }

    private void sendNotification(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        System.out.println(notification.toString());
    }

    public static void main(String[] args) throws Exception {
        new McpServer().start(args);
    }
}
