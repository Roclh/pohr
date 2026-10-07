package org.Roclh.service.version;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
public abstract class GitHubLatestVersionResolver {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** URL GitHub API: https://api.github.com/repos/OWNER/REPO/releases/latest */
    protected abstract String latestApiUrl();

    /** Отображаемое имя для логов: "Xray", "telemt". */
    protected abstract String serviceName();

    /** Возвращает последнюю версию без префикса v, или null при ошибке. */
    public String resolveLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(latestApiUrl()))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GitHub API returned HTTP {} for latest {} release",
                        response.statusCode(), serviceName());
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(response.body());
            String tag = root.path("tag_name").asString();
            if (tag.startsWith("v")) tag = tag.substring(1);
            return tag;
        } catch (Exception e) {
            log.warn("Failed to resolve latest {} version: {}", serviceName(), e.getMessage());
            return null;
        }
    }
}