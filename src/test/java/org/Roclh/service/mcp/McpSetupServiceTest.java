package org.Roclh.service.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSetupServiceTest {

    @Test
    @DisplayName("Установщик содержит URL, токен, правила и не содержит незаполненных плейсхолдеров")
    void rendersInstaller() {
        McpInstructionProvider instructions = mock(McpInstructionProvider.class);
        when(instructions.instructions()).thenReturn("# Pohr rules\nНе трогай чужие файлы.");

        McpSetupService service = new McpSetupService(instructions);
        String script = service.render(
                "https://pohr.example.com/mcp",
                "pohr_abc123",
                "https://pohr.example.com");

        assertThat(script).startsWith("\uFEFF");
        assertThat(script).contains("serverName: pohr");
        assertThat(script).contains("$McpUrl");
        assertThat(script).contains("'https://pohr.example.com/mcp'");
        assertThat(script).contains("$Token");
        assertThat(script).contains("'pohr_abc123'");
        assertThat(script).contains("url: \"$McpUrl\"");
        assertThat(script).contains("Authorization: \"Bearer $Token\"");
        assertThat(script).contains("# >>> pohr-mcp >>>");
        assertThat(script).contains("<!-- pohr-mcp:begin -->");
        assertThat(script).contains("$NoPause");
        assertThat(script).contains("Read-Host");
        assertThat(script).doesNotContain("{{");
        assertThat(script).contains("\r\n");
    }

    @Test
    @DisplayName("Установщик .bat содержит UAC, распаковку .ps1 и паузу")
    void rendersBatWrapper() {
        McpInstructionProvider instructions = mock(McpInstructionProvider.class);
        when(instructions.instructions()).thenReturn("# Pohr rules");

        McpSetupService service = new McpSetupService(instructions);
        String bat = service.renderBat(
                "https://pohr.example.com/mcp",
                "pohr_abc123",
                "https://pohr.example.com");

        assertThat(bat).startsWith("@echo off");
        assertThat(bat).contains("Requesting administrator rights");
        assertThat(bat).contains("Start-Process -FilePath $env:POHR_SELF");
        assertThat(bat).contains("ArgumentList '-NoPause'");
        assertThat(bat).contains("certutil -decode");
        assertThat(bat).contains("> \"%POHR_TMP_B64%\" echo ");
        assertThat(bat).contains("-NoPause");
        assertThat(bat).contains("pause");
        assertThat(bat).contains("POHR_DSH_HOME");
        assertThat(bat).doesNotContain("{{");
        assertThat(bat).contains("\r\n");
    }
}
