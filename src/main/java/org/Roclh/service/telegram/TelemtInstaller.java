package org.Roclh.service.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemtInstaller {

    private final TelemtVersionRegistry versionRegistry;

    @Value("${xray.home}")
    private String xrayHome;

    @Value("${pohr.telegram.install.download-url-template}")
    private String downloadUrlTemplate;

    @Value("${pohr.telegram.install.fallback-url-template:}")
    private String fallbackUrlTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Path binary() {
        String name = System.getProperty("os.name").toLowerCase().contains("win")
                ? "telemt.exe" : "telemt";
        return Path.of(xrayHome, "bin", name);
    }

    public boolean isInstalled() {
        return Files.exists(binary());
    }

    public String installedVersion() {
        return versionRegistry.readInstalledVersion();
    }

    public synchronized boolean install(String version) throws IOException, InterruptedException {
        if (version.equals(installedVersion()) && isInstalled()) {
            log.info("telemt {} already installed", version);
            return false;
        }

        Path home = Path.of(xrayHome);
        Path binDir = home.resolve("bin");
        Files.createDirectories(binDir);

        String url = resolveDownloadUrl(version);
        log.info("Downloading telemt {} from {}", version, url);

        Path tmp = home.resolve("telemt-" + version + ".tar.gz");
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<Path> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("Download failed: HTTP " + resp.statusCode() + " from " + url);
        }

        Path extractDir = home.resolve(".telemt-extract");
        deleteRecursively(extractDir);
        Files.createDirectories(extractDir);

        Process tar = new ProcessBuilder(
                "tar", "-xzf", tmp.toString(), "-C", extractDir.toString())
                .redirectErrorStream(true).start();
        String tarOut = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!tar.waitFor(60, TimeUnit.SECONDS) || tar.exitValue() != 0) {
            Files.deleteIfExists(tmp);
            deleteRecursively(extractDir);
            throw new IOException("tar failed: " + tarOut);
        }
        Files.deleteIfExists(tmp);

        Path extractedBinary = findBinary(extractDir);
        if (extractedBinary == null) {
            deleteRecursively(extractDir);
            throw new IOException("Binary 'telemt' not found in archive");
        }

        Path target = binary();
        Path backup = binDir.resolve(target.getFileName() + ".bak");
        if (Files.exists(target)) {
            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(extractedBinary, target, StandardCopyOption.REPLACE_EXISTING);
        target.toFile().setExecutable(true);
        deleteRecursively(extractDir);

        versionRegistry.writeInstalledVersion(version);
        log.info("telemt {} installed at {}", version, target);
        return true;
    }

    private Path findBinary(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("telemt"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    public String generateSecretn() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return HexFormat.of().formatHex(key);
    }

    private String resolveDownloadUrl(String version) {
        String platform = TelemtPlatform.current().suffix();
        String primary = downloadUrlTemplate
                .replace("{version}", version)
                .replace("{platform}", platform);
        if (fallbackUrlTemplate == null || fallbackUrlTemplate.isBlank()) {
            return primary;
        }
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(primary))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<Void> r = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() < 400) return primary;
        } catch (Exception e) {
            log.warn("Primary URL {} unreachable: {}", primary, e.getMessage());
        }
        return fallbackUrlTemplate
                .replace("{version}", version)
                .replace("{platform}", platform);
    }
}