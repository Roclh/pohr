package org.Roclh.service.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сайт-легенда публикуется из образа: заглушка «coming soon» должна замещаться
 * живыми страницами, а не переживать обновление в volume.
 */
class EdgeSiteContentServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Все объявленные файлы сайта есть в classpath")
    void everyDeclaredResourceExists() {
        for (String name : EdgeSiteContentService.SITE_RESOURCES) {
            assertThat(new ClassPathResource(EdgeSiteContentService.RESOURCE_PREFIX + name).exists())
                    .as("classpath resource %s", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("ensureSiteContent публикует полный набор и заменяет старую заглушку")
    void publishesFullSiteAndReplacesPlaceholder() throws Exception {
        Path site = tempDir.resolve("site");
        Files.createDirectories(site);
        Path index = site.resolve("index.html");
        Files.writeString(index, "<title>Roclh — coming soon</title>", StandardCharsets.UTF_8);

        EdgeSiteContentService service = new EdgeSiteContentService(tempDir.toString(), true);

        assertThat(service.ensureSiteContent())
                .isEqualTo(EdgeSiteContentService.SITE_RESOURCES.size());

        String html = Files.readString(index, StandardCharsets.UTF_8);
        assertThat(html).contains("монтаж Reels").doesNotContain("coming soon").doesNotContain("noindex");
        assertThat(site.resolve("404.html")).exists();
        assertThat(site.resolve("robots.txt")).exists();
        assertThat(site.resolve("sitemap.xml")).exists();
        assertThat(site.resolve("favicon.svg")).exists();
        assertThat(site.resolve("assets/site.css")).exists();
        assertThat(service.siteRoot()).isEqualTo(site);
    }

    @Test
    @DisplayName("Повторный вызов ничего не перезаписывает")
    void secondRunIsNoop() {
        EdgeSiteContentService service = new EdgeSiteContentService(tempDir.toString(), true);
        service.ensureSiteContent();

        assertThat(service.ensureSiteContent()).isZero();
    }

    @Test
    @DisplayName("site-sync=false не трогает существующие файлы в volume")
    void syncDisabledKeepsExistingFiles() throws Exception {
        Path site = tempDir.resolve("site");
        Files.createDirectories(site);
        Path index = site.resolve("index.html");
        Files.writeString(index, "edited-in-volume", StandardCharsets.UTF_8);

        EdgeSiteContentService service = new EdgeSiteContentService(tempDir.toString(), false);
        service.ensureSiteContent();

        assertThat(Files.readString(index, StandardCharsets.UTF_8)).isEqualTo("edited-in-volume");
        assertThat(site.resolve("404.html")).exists();
    }

    @Test
    @DisplayName("HTML сайта не содержит бренда панели и VPN-терминов")
    void pagesHaveNoPanelFingerprint() throws Exception {
        EdgeSiteContentService service = new EdgeSiteContentService(tempDir.toString(), true);
        service.ensureSiteContent();

        List<String> forbidden = List.of("pohr", "vpn", "xray", "reality", "login", "sign in",
                "подписк", "панел");
        try (var files = Files.walk(service.siteRoot())) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".html"))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase();
                for (String word : forbidden) {
                    assertThat(text)
                            .as("%s contains '%s'", file.getFileName(), word)
                            .doesNotContain(word);
                }
            }
        }
    }

    @Test
    @DisplayName("robots.txt и sitemap.xml ссылаются на публичный домен")
    void robotsAndSitemapPointToPublicSite() throws Exception {
        EdgeSiteContentService service = new EdgeSiteContentService(tempDir.toString(), true);
        service.ensureSiteContent();

        assertThat(Files.readString(service.siteRoot().resolve("robots.txt"), StandardCharsets.UTF_8))
                .contains("Sitemap: https://pohr-roclh.xyz/sitemap.xml")
                .contains("Allow: /");
        assertThat(Files.readString(service.siteRoot().resolve("sitemap.xml"), StandardCharsets.UTF_8))
                .contains("https://pohr-roclh.xyz/work.html");
    }
}
