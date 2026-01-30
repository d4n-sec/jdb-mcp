package com.jdbmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jdi.*;

import java.util.Map;

public class JdiValueConverter {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static ObjectNode convertValue(Value value, int maxDepth) {
        return convertValueRecursive(value, 0, maxDepth);
    }

    private static ObjectNode convertValueRecursive(Value value, int currentDepth, int maxDepth) {
        ObjectNode result = mapper.createObjectNode();
        if (value == null) {
            result.put("type", "null");
            result.put("value", "null");
            return result;
        }

        result.put("type", value.type().name());

        if (value instanceof PrimitiveValue) {
            result.put("value", value.toString());
        } else if (value instanceof StringReference) {
            result.put("value", ((StringReference) value).value());
        } else if (value instanceof ArrayReference) {
            ArrayReference array = (ArrayReference) value;
            result.put("length", array.length());
            if (currentDepth < maxDepth) {
                ArrayNode elements = result.putArray("elements");
                int limit = Math.min(array.length(), 100); // Limit array elements for performance
                for (int i = 0; i < limit; i++) {
                    elements.add(convertValueRecursive(array.getValue(i), currentDepth + 1, maxDepth));
                }
                if (array.length() > 100) {
                    result.put("note", "Truncated to first 100 elements");
                }
            } else {
                result.put("value", "[Array of length " + array.length() + "]");
            }
        } else if (value instanceof ObjectReference) {
            ObjectReference obj = (ObjectReference) value;
            result.put("id", obj.uniqueID());
            if (currentDepth < maxDepth) {
                ObjectNode fields = result.putObject("fields");
                ReferenceType type = obj.referenceType();
                try {
                    Map<Field, Value> fieldValues = obj.getValues(type.allFields());
                    for (Map.Entry<Field, Value> entry : fieldValues.entrySet()) {
                        fields.set(entry.getKey().name(), convertValueRecursive(entry.getValue(), currentDepth + 1, maxDepth));
                    }
                } catch (Exception e) {
                    result.put("error", "Failed to access fields: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
                }
            } else {
                result.put("value", "[Object " + obj.referenceType().name() + "]");
            }
        } else {
            result.put("value", value.toString());
        }

        return result;
    }

    public static Value parseValue(VirtualMachine vm, String value, Type targetType) {
        if (targetType instanceof BooleanType) return vm.mirrorOf(Boolean.parseBoolean(value));
        if (targetType instanceof ByteType) return vm.mirrorOf(Byte.parseByte(value));
        if (targetType instanceof CharType) return vm.mirrorOf(value.length() > 0 ? value.charAt(0) : '\0');
        if (targetType instanceof ShortType) return vm.mirrorOf(Short.parseShort(value));
        if (targetType instanceof IntegerType) return vm.mirrorOf(Integer.parseInt(value));
        if (targetType instanceof LongType) return vm.mirrorOf(Long.parseLong(value));
        if (targetType instanceof FloatType) return vm.mirrorOf(Float.parseFloat(value));
        if (targetType instanceof DoubleType) return vm.mirrorOf(Double.parseDouble(value));
        if (targetType instanceof ReferenceType && targetType.name().equals("java.lang.String")) {
            return vm.mirrorOf(value);
        }
        // For other reference types, we might need more complex logic or just return null for now
        return null;
    }
}
