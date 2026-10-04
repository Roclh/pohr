package org.Roclh.service;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.dto.ScriptInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ClientScriptService {

    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");
    private static final Set<String> ALLOWED_EXT = Set.of("bat", "cmd", "ps1", "sh", "txt");
    private static final String BUNDLED_PATTERN = "classpath*:scripts/*";
    private static final String BUNDLED_PREFIX = "classpath:scripts/";
    private static final Charset CP866 = Charset.forName("Cp866");
    private static final int HISTORY_LIMIT = 10;

    @Value("${pohr.scripts.home}")
    private String scriptsHome;

    private final ResourcePatternResolver resourceResolver;

    public ClientScriptService(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    public void seed() {
        Path home = homePath();
        try {
            Files.createDirectories(home);
            Resource[] bundled = resourceResolver.getResources(BUNDLED_PATTERN);
            for (Resource r : bundled) {
                String name = r.getFilename();
                if (name == null || !isSafeName(name)) continue;
                Path target = home.resolve(name);
                if (Files.exists(target)) {
                    log.debug("Script '{}' already exists, skipping seed", name);
                    continue;
                }
                Files.write(target, r.getInputStream().readAllBytes());
                log.info("Seeded script '{}' from bundled resources", name);
            }
        } catch (IOException e) {
            log.error("Failed to seed scripts into {}: {}", home, e.getMessage());
        }
    }

    public List<ScriptInfo> list() {
        Path home = homePath();
        if (!Files.exists(home)) return new ArrayList<>();
        try (var stream = Files.list(home)) {
            return new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::toInfo)
                    .toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean exists(String name) {
        return Files.exists(resolve(name));
    }

    public String read(String name) throws IOException {
        return Files.readString(resolve(name), StandardCharsets.UTF_8);
    }

    public void write(String name, String content) throws IOException {
        Path path = resolve(name);
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            backup(path);
        }
        String normalized = normalizeLineEndings(name, content);
        Files.writeString(path, normalized, StandardCharsets.UTF_8);
        log.info("Script '{}' saved ({} bytes)", name, normalized.length());
    }

    /** .sh — LF; .bat/.cmd/.ps1 — CRLF; остальное — как есть. */
    private String normalizeLineEndings(String name, String content) {
        String lower = name.toLowerCase();
        String lf = content.replace("\r\n", "\n").replace("\r", "\n");
        if (lower.endsWith(".sh")) {
            return lf;
        }
        if (lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")) {
            return lf.replace("\n", "\r\n");
        }
        return content;
    }

    public void reset(String name) throws IOException {
        Resource bundled = resourceResolver.getResource(BUNDLED_PREFIX + name);
        if (!bundled.exists()) {
            throw new IllegalArgumentException("No bundled version of '" + name + "'");
        }
        Path path = resolve(name);
        if (Files.exists(path)) backup(path);
        Files.write(path, bundled.getInputStream().readAllBytes());
        log.info("Script '{}' reset to bundled version", name);
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
        log.info("Script '{}' deleted", name);
    }

    public boolean isBundled(String name) {
        return resourceResolver.getResource(BUNDLED_PREFIX + name).exists();
    }

    public boolean matchesBundled(String name) {
        try {
            Resource bundled = resourceResolver.getResource(BUNDLED_PREFIX + name);
            if (!bundled.exists()) return false;
            Path path = resolve(name);
            if (!Files.exists(path)) return false;
            return Arrays.equals(
                    bundled.getInputStream().readAllBytes(),
                    Files.readAllBytes(path));
        } catch (IOException e) {
            return false;
        }
    }

    /** Подставляет {{VAR}} плейсхолдеры в содержимое скрипта. */
    public String render(String name, Map<String, String> vars) throws IOException {
        String raw = read(name);
        for (var e : vars.entrySet()) {
            raw = raw.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return raw;
    }

    public byte[] renderBytes(String name, Map<String, String> vars) throws IOException {
        String rendered = render(name, vars);
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            return rendered.getBytes(CP866);
        }
        if (name.endsWith(".ps1")) {
            // PS 5.1 читает UTF-8 только при наличии BOM
            return ("\uFEFF" + rendered).getBytes(StandardCharsets.UTF_8);
        }
        return rendered.getBytes(StandardCharsets.UTF_8);
    }

    private Path homePath() {
        return Path.of(scriptsHome).toAbsolutePath().normalize();
    }

    private Path resolve(String name) {
        if (!isSafeName(name)) {
            throw new IllegalArgumentException("Invalid script name: " + name);
        }
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("Unsupported extension: ." + ext);
        }
        Path home = homePath();
        Path resolved = home.resolve(name).normalize();
        if (!resolved.startsWith(home)) {
            throw new IllegalArgumentException("Path traversal detected");
        }
        return resolved;
    }

    private boolean isSafeName(String name) {
        return SAFE_NAME.matcher(name).matches() && name.contains(".");
    }

    private void backup(Path path) throws IOException {
        Path hist = path.getParent().resolve(".history");
        Files.createDirectories(hist);
        String ts = Instant.now().toString().replace(":", "-");
        Path bak = hist.resolve(path.getFileName() + "." + ts + ".bak");
        Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING);

        try (var stream = Files.list(hist)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().startsWith(path.getFileName() + "."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (int i = HISTORY_LIMIT; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        }
    }

    private ScriptInfo toInfo(Path p) {
        try {
            String name = p.getFileName().toString();
            return new ScriptInfo(
                    name,
                    Files.size(p),
                    Files.getLastModifiedTime(p).toInstant(),
                    isBundled(name),
                    matchesBundled(name)
            );
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}