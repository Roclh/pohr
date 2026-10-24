package org.Roclh.service.mcp;

import org.Roclh.repository.UserRepository;
import org.Roclh.repository.script.ScriptVersionRepository;
import org.Roclh.service.edge.CaddyProcessManager;
import org.Roclh.service.edge.NginxProcessManager;
import org.Roclh.service.metric.MetricsService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.script.ScriptCatalogService;
import org.Roclh.service.script.ScriptErrorReportService;
import org.Roclh.service.script.ScriptVersionService;
import org.Roclh.service.telegram.TelemtProcessManager;
import org.Roclh.service.traffic.ClientTrafficService;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayConfigService;
import org.Roclh.service.xray.XrayConfigValidator;
import org.Roclh.service.xray.XrayInstaller;
import org.Roclh.service.xray.XrayProcessManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Проверяет, что модули MCP собираются в единый tools/list и не ломают
 * существующие инструменты скриптов.
 */
class McpToolRegistryTest {

    @Test
    @DisplayName("tools/list объединяет скрипты, Xray, отчёты и диагностику")
    void registersAllModules() {
        McpXrayConfigTools xrayTools = new McpXrayConfigTools(
                mock(XrayConfigService.class),
                mock(XrayConfigMaterializer.class),
                mock(XrayConfigValidator.class),
                mock(XrayProcessManager.class),
                mock(XrayInstaller.class));

        McpScriptReportTools reportTools = new McpScriptReportTools(
                mock(ScriptErrorReportService.class),
                mock(ScriptCatalogService.class),
                mock(UserRepository.class));

        McpDiagnosticsTools diagnostics = new McpDiagnosticsTools(
                mock(XrayProcessManager.class),
                mock(TelemtProcessManager.class),
                mock(NginxProcessManager.class),
                mock(CaddyProcessManager.class),
                mock(XrayInstaller.class),
                mock(MetricsService.class),
                mock(EuNodeService.class),
                mock(ClientTrafficService.class),
                mock(XrayConfigService.class));

        McpToolService service = new McpToolService(
                mock(ScriptCatalogService.class),
                mock(ScriptVersionService.class),
                mock(ScriptVersionRepository.class),
                mock(McpInstructionProvider.class),
                List.of(xrayTools, reportTools, diagnostics));

        ArrayNode tools = service.toolDefinitions();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < tools.size(); i++) {
            ObjectNode tool = (ObjectNode) tools.get(i);
            names.add(tool.get("name").asText());
            assertThat(tool.get("inputSchema").get("type").asText()).isEqualTo("object");
        }

        assertThat(tools.size()).isEqualTo(31);
        assertThat(names).contains(
                "scripts_list",
                "xray_configs_list",
                "xray_config_read",
                "xray_config_validate",
                "xray_config_create",
                "xray_config_update",
                "xray_config_activate",
                "xray_config_apply",
                "xray_status",
                "script_reports_list",
                "script_reports_read",
                "script_reports_set_status",
                "script_reports_stats",
                "server_status",
                "logs_tail",
                "traffic_overview",
                "nodes_list");
    }
}
