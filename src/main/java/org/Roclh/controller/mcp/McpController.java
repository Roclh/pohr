package org.Roclh.controller.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.mcp.McpInstructionProvider;
import org.Roclh.service.mcp.McpToolService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * MCP-эндпоинт (Streamable HTTP, JSON-RPC 2.0).
 *
 * <p>DSH (dsh-mcp-client) сначала присылает {@code server/discover} для
 * переговоров о версии протокола. Мы отвечаем -32601 — это штатный сигнал
 * «работаем по legacy-рукопожатию 2025-06-18»; после него клиент делает
 * обычный initialize и дальше ходит tools/list + tools/call.</p>
 *
 * <p>Состояния нет: на initialize не выдаём Mcp-Session-Id, GET/DELETE
 * отвечают 405. Этого достаточно для DSH и не требует хранить сессии.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private final McpToolService toolService;
    private final McpInstructionProvider instructions;

    @PostMapping(value = {"/mcp", "/mcp/"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(@RequestBody String body, Authentication authentication) {
        String actor = authentication == null ? "unknown" : authentication.getName();

        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            log.debug("MCP parse error: {}", e.getMessage());
            return json(error(null, -32700, "Parse error"));
        }

        if (root.isArray()) {
            ArrayNode responses = JSON.createArrayNode();
            for (JsonNode message : root) {
                if (!message.isObject()) {
                    responses.add(error(null, -32600, "Invalid Request"));
                    continue;
                }
                JsonNode response = process(message, actor);
                if (response != null) {
                    responses.add(response);
                }
            }
            if (responses.isEmpty()) {
                return accepted();
            }
            return json(responses);
        }

        if (!root.isObject()) {
            return json(error(null, -32600, "Invalid Request"));
        }

        JsonNode response = process(root, actor);
        return response == null ? accepted() : json(response);
    }

    /** DSH открывает GET SSE только после notifications/initialized. 405 = стрим не нужен. */
    @GetMapping({"/mcp", "/mcp/"})
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "POST")
                .build();
    }

    @DeleteMapping({"/mcp", "/mcp/"})
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "POST")
                .build();
    }

    private JsonNode process(JsonNode message, String actor) {
        if (!message.hasNonNull("method")) {
            // Ответ клиента на наш запрос: сервер клиенту запросов не шлёт.
            return null;
        }

        String method = message.get("method").asText();
        JsonNode id = message.get("id");
        boolean notification = id == null || id.isNull();

        if (notification) {
            // notifications/initialized, notifications/cancelled и прочие.
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> result(id, initializeResult(message));
                case "ping" -> result(id, JSON.createObjectNode());
                case "tools/list" -> {
                    ObjectNode payload = JSON.createObjectNode();
                    payload.set("tools", toolService.toolDefinitions());
                    yield result(id, payload);
                }
                case "tools/call" -> result(id, callTool(message, actor));
                case "resources/list" -> {
                    ObjectNode payload = JSON.createObjectNode();
                    payload.putArray("resources");
                    yield result(id, payload);
                }
                case "resources/templates/list" -> {
                    ObjectNode payload = JSON.createObjectNode();
                    payload.putArray("resourceTemplates");
                    yield result(id, payload);
                }
                case "prompts/list" -> {
                    ObjectNode payload = JSON.createObjectNode();
                    payload.putArray("prompts");
                    yield result(id, payload);
                }
                case "logging/setLevel" -> result(id, JSON.createObjectNode());
                case "server/discover" -> error(id, -32601,
                        "server/discover is not supported; use the 2025-06-18 initialize handshake");
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.warn("MCP method '{}' failed: {}", method, e.getMessage(), e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private ObjectNode callTool(JsonNode message, String actor) {
        JsonNode params = message.path("params");
        String toolName = params.path("name").isTextual() ? params.path("name").asText() : "";
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("tools/call без params.name");
        }
        JsonNode argumentsNode = params.get("arguments");
        ObjectNode arguments = argumentsNode != null && argumentsNode.isObject()
                ? (ObjectNode) argumentsNode
                : JSON.createObjectNode();
        return toolService.call(toolName, arguments, actor);
    }

    private ObjectNode initializeResult(JsonNode message) {
        String requested = message.path("params").path("protocolVersion").asText("");
        String protocolVersion = requested.matches("\\d{4}-\\d{2}-\\d{2}")
                ? requested
                : DEFAULT_PROTOCOL_VERSION;

        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", protocolVersion);

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", toolService.serverName());
        serverInfo.put("version", "1.0.0");
        serverInfo.put("title", "Pohr scripts");

        result.put("instructions", instructions.instructions());
        return result;
    }

    private static ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        response.set("result", payload);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null || id.isNull()) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private static ResponseEntity<String> json(JsonNode node) {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                .body(JSON.writeValueAsString(node));
    }

    private static ResponseEntity<String> accepted() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
