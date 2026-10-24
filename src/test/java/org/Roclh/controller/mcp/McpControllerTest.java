package org.Roclh.controller.mcp;

import org.Roclh.repository.script.ScriptVersionRepository;
import org.Roclh.service.mcp.McpInstructionProvider;
import org.Roclh.service.mcp.McpToolService;
import org.Roclh.service.script.ScriptCatalogService;
import org.Roclh.service.script.ScriptVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет MCP-рукопожатие DSH: probe server/discover должен давать -32601,
 * после чего клиент уходит на legacy initialize; tools/list и tools/call
 * возвращают JSON-RPC result, а notifications дают 202.
 */
class McpControllerTest {

    private final ObjectMapper json = new ObjectMapper();
    private McpToolService toolService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        toolService = mock(McpToolService.class);
        McpInstructionProvider instructions = mock(McpInstructionProvider.class);
        when(instructions.instructions()).thenReturn("Pohr rules");

        ArrayNode tools = json.createArrayNode();
        ObjectNode tool = tools.addObject();
        tool.put("name", "scripts_list");
        tool.put("description", "list");
        tool.set("inputSchema", json.createObjectNode().put("type", "object"));
        when(toolService.toolDefinitions()).thenReturn(tools);
        when(toolService.serverName()).thenReturn("pohr");

        mockMvc = MockMvcBuilders
                .standaloneSetup(new McpController(toolService, instructions))
                .build();
    }

    @Test
    @DisplayName("initialize отвечает legacy-версией, capabilities и instructions")
    void initialize() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize",
                                 "params":{"protocolVersion":"2025-06-18","capabilities":{},
                                 "clientInfo":{"name":"dsh-mcp-client","version":"0.0.1"}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("pohr"))
                .andExpect(jsonPath("$.result.instructions").value("Pohr rules"));
    }

    @Test
    @DisplayName("server/discover отвечает -32601 — DSH переходит на legacy initialize")
    void discoverFallsBackToLegacy() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":0,"method":"server/discover",
                                 "params":{"_meta":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(0))
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    @DisplayName("notifications/initialized получает 202 без тела")
    void initializedNotification() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("tools/list отдаёт инструменты, tools/call — результат")
    void tools() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[0].name").value("scripts_list"));

        ObjectNode result = json.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "ok");
        result.put("isError", false);
        when(toolService.call(eq("scripts_list"), any(), eq("unknown"))).thenReturn(result);

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                                 "params":{"name":"scripts_list","arguments":{}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value("ok"))
                .andExpect(jsonPath("$.result.isError").value(false));
    }

    @Test
    @DisplayName("GET /mcp отвечает 405 — SSE-стрим не нужен")
    void getIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Все inputSchema инструментов — валидный JSON Schema")
    void toolSchemasAreValidJson() {
        McpToolService real = new McpToolService(
                mock(ScriptCatalogService.class),
                mock(ScriptVersionService.class),
                mock(ScriptVersionRepository.class),
                mock(McpInstructionProvider.class),
                List.of());

        ArrayNode tools = real.toolDefinitions();
        assertThat(tools.size()).isEqualTo(15);
        for (int i = 0; i < tools.size(); i++) {
            ObjectNode tool = (ObjectNode) tools.get(i);
            assertThat(tool.get("name").asText()).isNotBlank();
            assertThat(tool.get("description").asText()).isNotBlank();
            assertThat(tool.get("inputSchema").get("type").asText()).isEqualTo("object");
        }
    }
}
