package org.Roclh.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Статический контент edge-сайтов. Заглушка лежит в classpath (`site/index.html`)
 * и один раз копируется в ${pohr.edge.home}/site. Существующий index.html не
 * перетирается — позже сюда кладётся настоящий сайт (Т05).
 */
@Slf4j
@Service
public class EdgeSiteContentService {

    static final String PLACEHOLDER_RESOURCE = "site/index.html";

    private final String edgeHome;

    public EdgeSiteContentService(@Value("${pohr.edge.home}") String edgeHome) {
        this.edgeHome = edgeHome;
    }

    public Path siteRoot() {
        return Path.of(edgeHome, "site");
    }

    /** Идемпотентно. Ошибка копирования не валит edge: сайт просто отдаст 404. */
    public synchronized void ensurePlaceholder() {
        Path index = siteRoot().resolve("index.html");
        if (Files.exists(index)) {
            return;
        }
        try (InputStream in = new ClassPathResource(PLACEHOLDER_RESOURCE).getInputStream()) {
            Files.createDirectories(index.getParent());
            Files.copy(in, index, StandardCopyOption.REPLACE_EXISTING);
            log.info("Edge placeholder installed: {}", index);
        } catch (IOException e) {
            log.error("Failed to install edge placeholder at {}: {}", index, e.getMessage(), e);
        }
    }
}
