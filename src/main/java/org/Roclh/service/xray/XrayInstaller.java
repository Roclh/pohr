package org.Roclh.service.xray;

import lombok.RequiredArgsConstructor;
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
import java.util.Comparator;

@Slf4j
@Service
@RequiredArgsConstructor
public class XrayInstaller {
    private final XrayVersionRegistry versionRegistry;

    @Value("${xray.home}")
    private String xrayHome;

    @Value("${xray.install.version:25.10.1}")
    private String configuredVersion;

    @Value("${xray.install.download-url-template}")
    private String downloadUrlTemplate;

    @Value("${xray.install.fallback-url-template:}")
    private String fallbackUrlTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public boolean isInstalled() {
        return Files.exists(Path.of(xrayHome, "bin", binaryName()));
    }

    public String installedVersion() {
        return versionRegistry.readInstalledVersion();
    }

    /**
     * Скачивает и распаковывает Xray. Возвращает true, если установлена новая версия.
     */
    public synchronized boolean install(String version) throws IOException, InterruptedException {
        if (version.equals(installedVersion()) && isInstalled()) {
            log.info("Xray {} already installed", version);
            return false;
        }

        Path home = Path.of(xrayHome);
        Path binDir = home.resolve("bin");
        Path tmpZip = home.resolve("xray-" + version + ".zip");
        Path tmpExtract = home.resolve(".tmp-extract");

        Files.createDirectories(binDir);
        Files.createDirectories(home.resolve("config"));

        String url = resolveDownloadUrl(version);
        log.info("Downloading Xray {} from {}", version, url);

        HttpResponse<Path> response = download(url, tmpZip);
        if (response.statusCode() != 200) {
            throw new IOException("Download failed: HTTP " + response.statusCode() + " from " + url);
        }

        if (Files.exists(tmpExtract)) {
            deleteRecursively(tmpExtract);
        }
        Files.createDirectories(tmpExtract);
        unzip(tmpZip, tmpExtract);

        Path extractedBinary = tmpExtract.resolve(binaryName());
        if (!Files.exists(extractedBinary)) {
            throw new IOException("xray binary not found in archive: " + extractedBinary);
        }

        Path targetBinary = binDir.resolve(binaryName());
        Path backupBinary = binDir.resolve(binaryName() + ".bak");

        if (Files.exists(targetBinary)) {
            Files.move(targetBinary, backupBinary, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(extractedBinary, targetBinary, StandardCopyOption.REPLACE_EXISTING);
        targetBinary.toFile().setExecutable(true);

        versionRegistry.writeInstalledVersion(version);

        Files.deleteIfExists(tmpZip);
        deleteRecursively(tmpExtract);

        log.info("Xray {} installed at {}", version, targetBinary);
        return true;
    }

    private String resolveDownloadUrl(String version) {
        String platform = XrayPlatform.current().suffix();
        String primary = downloadUrlTemplate
                .replace("{version}", version)
                .replace("{platform}", platform);
        if (fallbackUrlTemplate == null || fallbackUrlTemplate.isBlank()) {
            return primary;
        }
        // Проверяем доступность primary — если недоступен, берём fallback
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
        return fallbackUrlTemplate.replace("{version}", version);
    }

    private HttpResponse<Path> download(String url, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
    }

    private void unzip(Path zip, Path targetDir) throws IOException {
        Path normalizedTargetDir = targetDir.toAbsolutePath().normalize();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = normalizedTargetDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(normalizedTargetDir)) {
                    throw new IOException("Zip entry is outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    private String binaryName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "xray.exe" : "xray";
    }
}
