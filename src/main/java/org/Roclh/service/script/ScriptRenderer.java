package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Резолвит плейсхолдеры {{...}} в содержимом скрипта перед отдачей клиенту.
 * Не пишет ничего на диск.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptRenderer {

    private static final Charset CP866 = Charset.forName("Cp866");

    public record RenderContext(String baseUrl, String token, String version) {
        public String subscriptionUrl() { return baseUrl + "/sub/" + token; }
        public String rulesUrl()        { return baseUrl + "/sub/" + token + "/rules.json"; }
        public String scriptsBaseUrl()  { return baseUrl + "/sub/" + token + "/scripts"; }
    }

    public String render(String content, RenderContext ctx) {
        if (content == null) return "";

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("BASE_URL",         ctx.baseUrl());
        vars.put("TOKEN",            ctx.token());
        vars.put("SUBSCRIPTION_URL", ctx.subscriptionUrl());
        vars.put("RULES_URL",        ctx.rulesUrl());
        vars.put("SCRIPTS_BASE_URL", ctx.scriptsBaseUrl());
        vars.put("__VERSION__",      ctx.version() == null ? "" : ctx.version());

        // BC-алиасы на старые плейсхолдеры
        vars.put("PS1_URL",   ctx.scriptsBaseUrl() + "/v2rayn-setup.ps1/raw");
        vars.put("UTILS_URL", ctx.scriptsBaseUrl() + "/pohr-utils.ps1/raw");

        String result = content;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String placeholder = "{{" + e.getKey() + "}}";
            String value = e.getValue() == null ? "" : e.getValue();
            result = result.replace(placeholder, value);
        }
        return result;
    }

    public byte[] renderBytes(String name, String content, RenderContext ctx) {
        String rendered = render(content, ctx);
        return encode(name, rendered);
    }

    private byte[] encode(String name, String content) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return content.getBytes(CP866);
        }
        if (lower.endsWith(".ps1")) {
            // PS 5.1 читает UTF-8 только при наличии BOM
            return ("\uFEFF" + content).getBytes(StandardCharsets.UTF_8);
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }
}