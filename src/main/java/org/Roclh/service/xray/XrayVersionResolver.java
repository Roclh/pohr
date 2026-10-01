package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class XrayVersionResolver {

    private static final String LATEST_API = "https://api.github.com/repos/XTLS/Xray-core/releases/latest";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Возвращает последнюю версию без префикса "v", например "25.10.15".
     * При ошибке возвращает null — вызывающий код решает, что делать.
     */
    public String resolveLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST_API))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GitHub API returned HTTP {} for latest release", response.statusCode());
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(response.body());
            String tag = root.path("tag_name").asText();
            if (tag.startsWith("v")) tag = tag.substring(1);
            return tag;
        } catch (Exception e) {
            log.warn("Failed to resolve latest Xray version: {}", e.getMessage());
            return null;
        }
    }
}