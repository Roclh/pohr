package org.Roclh.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Статический контент edge-сайтов: сайт-легенда лежит в classpath (`site/**`)
 * и синхронизируется в {@code ${pohr.edge.home}/site}.
 *
 * <p>Раньше здесь была только заглушка «coming soon», которая не перетиралась:
 * volume с ней переживал обновление образа, и снаружи оставался мёртвый сайт с
 * {@code noindex}. Теперь набор файлов публикуется из образа (файлы пишутся,
 * только если содержимое отличается), а {@code pohr.edge.site-sync=false}
 * возвращает старое поведение «не трогать существующее».</p>
 */
@Slf4j
@Service
public class EdgeSiteContentService {

    /** Явный список: в native-image каталог classpath не перечислить. */
    static final List<String> SITE_RESOURCES = List.of(
            "index.html",
            "work.html",
            "about.html",
            "services.html",
            "contact.html",
            "privacy.html",
            "terms.html",
            "404.html",
            "robots.txt",
            "sitemap.xml",
            "favicon.svg",
            "assets/site.css");

    static final String RESOURCE_PREFIX = "site/";

    private final String edgeHome;
    private final boolean sync;

    public EdgeSiteContentService(@Value("${pohr.edge.home}") String edgeHome,
                                  @Value("${pohr.edge.site-sync:true}") boolean sync) {
        this.edgeHome = edgeHome;
        this.sync = sync;
    }

    public Path siteRoot() {
        return Path.of(edgeHome, "site");
    }

    /**
     * Идемпотентно. Ошибка копирования не валит edge: сайт просто отдаст 404.
     *
     * @return сколько файлов записано
     */
    public synchronized int ensureSiteContent() {
        Path root = siteRoot();
        int written = 0;
        for (String name : SITE_RESOURCES) {
            Path target = root.resolve(name);
            try (InputStream in = new ClassPathResource(RESOURCE_PREFIX + name).getInputStream()) {
                byte[] bytes = in.readAllBytes();
                if (Files.exists(target)) {
                    if (!sync) {
                        continue;
                    }
                    if (Arrays.equals(Files.readAllBytes(target), bytes)) {
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
                written++;
                log.info("Edge site file installed: {}", target);
            } catch (IOException e) {
                log.error("Failed to install edge site file {}: {}", target, e.getMessage(), e);
            }
        }
        return written;
    }
}
