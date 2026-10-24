package org.Roclh.service.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Общие помощники MCP-модулей: описания инструментов, конверт ответа и
 * разбор аргументов. Держим в одном месте, чтобы новые модули не копировали
 * формат content[]/isError.
 */
public final class McpToolSupport {

    public static final ObjectMapper JSON = new ObjectMapper();

    private McpToolSupport() {
    }

    public static ObjectNode tool(String name, String description, String schema) {
        ObjectNode tool = JSON.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", JSON.readTree(schema));
        return tool;
    }

    public static ObjectNode ok(JsonNode payload) {
        return result(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload), false);
    }

    public static ObjectNode okText(String text) {
        return result(text, false);
    }

    public static ObjectNode fail(String text) {
        return result(text, true);
    }

    public static ObjectNode result(String text, boolean error) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode content = out.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text == null ? "" : text);
        out.put("isError", error);
        return out;
    }

    public static String reqText(ObjectNode args, String key) {
        String value = optText(args, key);
        if (value == null) {
            throw new IllegalArgumentException("Параметр '" + key + "' обязателен");
        }
        return value;
    }

    public static String optText(ObjectNode args, String key) {
        if (args == null || !args.has(key)) return null;
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) return "";
        String value = node.asText();
        return value == null ? "" : value;
    }

    public static boolean optBool(ObjectNode args, String key, boolean defaultValue) {
        if (args == null || !args.has(key)) return defaultValue;
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return defaultValue;
        if (node.isBoolean()) return node.asBoolean();
        String value = node.asText();
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    public static int optInt(ObjectNode args, String key, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (args != null && args.has(key)) {
            JsonNode node = args.get(key);
            if (node != null && !node.isNull()) {
                if (node.isNumber()) {
                    value = node.asInt();
                } else {
                    try {
                        value = Integer.parseInt(node.asText().trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Параметр '" + key + "' должен быть числом");
                    }
                }
            }
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Параметр '" + key + "' должен быть в диапазоне "
                    + min + ".." + max);
        }
        return value;
    }

    public static UUID optUuid(ObjectNode args, String key) {
        String value = optText(args, key);
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный UUID в параметре '" + key + "': " + value);
        }
    }

    public static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
