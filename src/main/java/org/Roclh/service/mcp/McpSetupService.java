package org.Roclh.service.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Генерирует установщик для DeepSeek Harness.
 *
 * <p>Основной артефакт — .bat, потому что двойной клик по .ps1 открывает
 * редактор, а не запускает скрипт. .bat сам запрашивает UAC, декодирует
 * встроенный .ps1 через certutil, запускает его и всегда оставляет окно
 * открытым до нажатия Enter.</p>
 *
 * <p>Скрипт содержит MCP-URL и Bearer-токен, поэтому отдаётся только
 * авторизованному админу и только по POST. .ps1 пишется в UTF-8 с BOM
 * и CRLF: иначе Windows PowerShell 5.1 прочитает кириллицу как cp1251.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpSetupService {

    private static final int B64_LINE_LENGTH = 100;

    private final McpInstructionProvider instructions;

    /** .ps1 — для запуска из уже открытого PowerShell и для тестов. */
    public String renderPs1(String mcpUrl, String token, String pohrUrl) {
        validate(mcpUrl, token);

        String template = loadTemplate("mcp/dsh-setup.ps1.tpl");
        String rulesBase64 = Base64.getEncoder()
                .encodeToString(instructions.instructions().getBytes(StandardCharsets.UTF_8));

        String script = template
                .replace("{{MCP_URL}}", mcpUrl)
                .replace("{{TOKEN}}", token)
                .replace("{{RULES_B64}}", rulesBase64)
                .replace("{{GENERATED_AT}}", Instant.now().toString())
                .replace("{{POHR_URL}}", psLiteral(pohrUrl));

        script = script.replace("\r\n", "\n").replace("\n", "\r\n");
        return "\uFEFF" + script;
    }

    /** .bat — установщик по умолчанию: двойной клик, UAC, окно не закрывается. */
    public String renderBat(String mcpUrl, String token, String pohrUrl) {
        String ps1 = renderPs1(mcpUrl, token, pohrUrl);
        String base64 = Base64.getEncoder()
                .encodeToString(ps1.getBytes(StandardCharsets.UTF_8));

        String template = loadTemplate("mcp/dsh-setup.bat.tpl");
        String bat = template
                .replace("{{B64_LINES}}", base64Lines(base64))
                .replace("{{GENERATED_AT}}", Instant.now().toString())
                .replace("{{POHR_URL}}", pohrUrl == null ? "" : pohrUrl);

        return bat.replace("\r\n", "\n").replace("\n", "\r\n");
    }

    /** Совместимость со старым вызовом. */
    public String render(String mcpUrl, String token, String pohrUrl) {
        return renderPs1(mcpUrl, token, pohrUrl);
    }

    private static void validate(String mcpUrl, String token) {
        if (mcpUrl == null || !mcpUrl.matches("^https?://[^\\s\"'$]+$")) {
            throw new IllegalArgumentException("Некорректный MCP URL: " + mcpUrl);
        }
        if (token == null || !token.matches("^pohr_[A-Za-z0-9_-]+$")) {
            throw new IllegalArgumentException("Некорректный MCP-токен");
        }
    }

    private static String base64Lines(String base64) {
        StringBuilder lines = new StringBuilder(base64.length() + 128);
        for (int offset = 0; offset < base64.length(); offset += B64_LINE_LENGTH) {
            String chunk = base64.substring(offset, Math.min(offset + B64_LINE_LENGTH, base64.length()));
            lines.append(offset == 0 ? "> \"%POHR_TMP_B64%\" echo " : ">> \"%POHR_TMP_B64%\" echo ")
                    .append(chunk)
                    .append("\r\n");
        }
        return lines.toString();
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load MCP setup template {}", path, e);
            throw new IllegalStateException("Установщик недоступен: " + e.getMessage(), e);
        }
    }

    /** Экранирование для одинарной строки PowerShell. */
    private static String psLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
