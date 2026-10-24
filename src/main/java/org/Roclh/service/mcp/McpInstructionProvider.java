package org.Roclh.service.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Тексты, которые MCP отдаёт агенту:
 * <ul>
 *   <li>{@link #instructions()} — короткие правила, попадают в initialize.instructions,
 *       а DSH добавляет их в системный промпт;</li>
 *   <li>{@link #contract()} — полный scripts.md, доступен как инструмент pohr_rules.</li>
 * </ul>
 *
 * <p>Полный контракт копируется в classpath из корневого scripts.md на этапе
 * processResources, чтобы не держать вторую копию в репозитории.</p>
 */
@Slf4j
@Service
public class McpInstructionProvider {

    private static final String FALLBACK_RULES = """
            # Pohr MCP

            Используй инструменты mcp__pohr__* для чтения, создания и правки
            клиентских скриптов Pohr. Перед правкой прочитай scripts_read и
            scripts_history, после правки проверь scripts_history.
            Никогда не меняй скрипты напрямую через файловые инструменты.
            """;

    private final String agentRules;
    private final String contract;

    public McpInstructionProvider() {
        this.agentRules = load("mcp/agent-rules.md", FALLBACK_RULES);
        this.contract = load("mcp/scripts-contract.md", agentRules);
    }

    /** Правила для системного промпта (initialize.instructions). */
    public String instructions() {
        return agentRules;
    }

    /** Полный контракт scripts.md для инструмента pohr_rules. */
    public String contract() {
        return contract;
    }

    public String rulesAndContract() {
        if (contract == null || contract.isBlank() || contract.equals(agentRules)) {
            return agentRules;
        }
        return contract + "\n\n---\n\n" + agentRules;
    }

    private static String load(String path, String fallback) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("MCP resource {} not found, using fallback", path);
                return fallback;
            }
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to read MCP resource {}: {}", path, e.getMessage());
            return fallback;
        }
    }
}
