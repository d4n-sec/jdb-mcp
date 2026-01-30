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
                        JsonNode requestNode = mapper.readTree(line);
                        if (requestNode.has("id")) {
                            JsonNode idNode = requestNode.get("id");
                            System.out.println(McpResponseFactory.createError(idNode, -32603, "Internal error: " + errorMsg));
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
                return McpResponseFactory.createError(idNode, -32600, "Invalid Request: missing method");
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
            
            responseJson = McpResponseFactory.createResponse(idNode, result);
        } else if (method.equals("tools/list")) {
            responseJson = McpResponseFactory.createResponse(idNode, McpTools.listTools(mapper));
        } else if (method.equals("tools/call")) {
            responseJson = handleToolCall(idNode, request.get("params"));
        } else {
            responseJson = McpResponseFactory.createError(idNode, -32601, "Method not found: " + method);
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
            debugger.setEventListener(new Consumer<String>() {
                @Override
                public void accept(String msg) {
                    try {
                        String notification = McpResponseFactory.createNotification("notifications/message", mapper.createObjectNode()
                                .put("level", "info")
                                .put("description", "Debugger Event")
                                .put("data", msg));
                        System.out.println(notification);
                    } catch (Exception e) {
                    }
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

    private String handleToolCall(JsonNode idNode, JsonNode params) {
        String name = params.get("name").asText();
        JsonNode arguments = params.get("arguments");
        
        try {
            JsonNode result;
            if ("debug_attach".equals(name)) {
                String host = (arguments != null && arguments.has("host")) ? arguments.get("host").asText() : "localhost";
                int port = (arguments != null && arguments.has("port")) ? arguments.get("port").asInt() : -1;
                if (port == -1) throw new Exception("Missing required argument: port");
                
                resetSession("Attached: " + host + ":" + port);
                currentSession.debugger.attach(host, port);
                
                ObjectNode attachResult = mapper.createObjectNode();
                attachResult.put("message", "Attached to " + host + ":" + port);
                attachResult.set("vmState", JdiStateMapper.getVmState(currentSession.debugger.getVm()));
                result = attachResult;
            } else if ("debug_detach".equals(name)) {
                if (currentSession != null) {
                    currentSession.debugger.terminate();
                    currentSession = null;
                    result = mapper.valueToTree("Session terminated and detached.");
                } else {
                    result = mapper.valueToTree("No active session to detach.");
                }
            } else {
                JdiDebugger dbg = getDebugger();
                result = McpToolExecutor.execute(name, arguments, dbg);
            }
            
            return McpResponseFactory.createMcpToolResponse(idNode, result);
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            return McpResponseFactory.createMcpErrorResponse(idNode, "Error: " + errorMsg);
        }
    }

    public static void main(String[] args) throws Exception {
        new McpServer().start(args);
    }
}
