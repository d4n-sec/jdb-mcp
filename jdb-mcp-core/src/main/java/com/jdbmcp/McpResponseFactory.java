package com.jdbmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Factory for creating JSON-RPC responses and MCP specific response structures.
 */
public class McpResponseFactory {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String createResponse(JsonNode idNode, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        }
        response.set("result", result);
        return response.toString();
    }

    public static String createError(JsonNode idNode, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (idNode != null) {
            response.set("id", idNode);
        }
        response.set("error", mapper.createObjectNode().put("code", code).put("message", message));
        return response.toString();
    }

    public static String createMcpToolResponse(JsonNode idNode, JsonNode result) {
        ObjectNode responseNode = mapper.createObjectNode();
        ArrayNode content = responseNode.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", result.isTextual() ? result.asText() : result.toString());
        return createResponse(idNode, responseNode);
    }

    public static String createMcpErrorResponse(JsonNode idNode, String message) {
        ObjectNode errorResponse = mapper.createObjectNode();
        ArrayNode content = errorResponse.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");
        textContent.put("text", message);
        errorResponse.put("isError", true);
        return createResponse(idNode, errorResponse);
    }

    public static String createNotification(String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        return notification.toString();
    }
}
