package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;


@Slf4j
@Service
public class XrayAssetService {

    private static final String GEOSITE_URL =
            "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat";
    private static final String GEOIP_URL =
            "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat";

    @Value("${xray.home}")
    private String xrayHome;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Идемпотентно. Тихо пропускает уже скачанные файлы. */
    public void ensureAssets() {
        Path configDir = Path.of(xrayHome, "config");
        try {
            Files.createDirectories(configDir);
            downloadIfMissing(GEOSITE_URL, configDir.resolve("geosite.dat"));
            downloadIfMissing(GEOIP_URL, configDir.resolve("geoip.dat"));
        } catch (Exception e) {
            // Не валим приложение: без geoip/geosite Xray упадёт, но старт
            // не должен блокироваться из-за недоступности GitHub.
            log.error("Failed to ensure Xray geo assets: {}", e.getMessage(), e);
        }
    }

    private void downloadIfMissing(String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target) && Files.size(target) > 0) {
            log.debug("Asset present: {} ({} bytes)", target, Files.size(target));
            return;
        }
        log.info("Downloading Xray asset: {}", url);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed: HTTP " + resp.statusCode() + " from " + url);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded {} ({} bytes)", target.getFileName(), Files.size(target));
    }
}