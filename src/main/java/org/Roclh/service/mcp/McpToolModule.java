package org.Roclh.service.mcp;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Набор MCP-инструментов, который регистрируется в {@link McpToolService}.
 * Модули не знают друг о друге и не зависят от HTTP-слоя.
 */
public interface McpToolModule {

    /** Полные определения для tools/list. */
    ArrayNode definitions();

    boolean supports(String toolName);

    ObjectNode call(String toolName, ObjectNode args, String actor);
}
