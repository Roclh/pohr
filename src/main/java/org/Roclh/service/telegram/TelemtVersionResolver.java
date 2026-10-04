package org.Roclh.service.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class TelemtVersionResolver {

    @Value("${pohr.telegram.install.latest-api:https://api.github.com/repos/telemt/telemt/releases/latest}")
    private String latestApi;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String resolveLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(latestApi))
                    .header("Accept", "application/vnd.github+json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("GitHub API returned HTTP {} for latest telemt release", response.statusCode());
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(response.body());
            String tag = root.path("tag_name").asString();
            if (tag.startsWith("v")) tag = tag.substring(1);
            return tag;
        } catch (Exception e) {
            log.warn("Failed to resolve latest telemt version: {}", e.getMessage());
            return null;
        }
    }
}
