package org.Roclh.controller.admin.edge;

import org.Roclh.model.dto.edge.EdgeConfigFileDto;
import org.Roclh.model.dto.edge.EdgeConfigFilesDto;
import org.Roclh.model.dto.edge.EdgeMapDto;
import org.Roclh.model.dto.edge.EdgePreviewDto;
import org.Roclh.model.dto.edge.EdgeSniRowDto;
import org.Roclh.model.dto.edge.EdgeStatusDto;
import org.Roclh.model.dto.edge.EdgeWarningDto;
import org.Roclh.service.edge.EdgeVisibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест шаблонов: проверяет, что Thymeleaf реально рендерит новую карту SNI
 * и diff, а не только возвращает имя view. Фрагменты layout рендерятся как есть,
 * security-атрибуты без диалекта остаются обычными атрибутами.
 */
class EdgeTemplatesRenderTest {

    private TemplateEngine engine;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine springEngine = new SpringTemplateEngine();
        springEngine.setTemplateResolver(resolver);
        springEngine.setMessageResolver(new org.thymeleaf.messageresolver.IMessageResolver() {
            @Override
            public String getName() {
                return "test";
            }

            @Override
            public Integer getOrder() {
                return 0;
            }

            @Override
            public String resolveMessage(org.thymeleaf.context.ITemplateContext context,
                                         Class<?> origin, String key, Object[] messageParameters) {
                return key;
            }

            @Override
            public String createAbsentMessageRepresentation(org.thymeleaf.context.ITemplateContext context,
                                                            Class<?> origin, String key,
                                                            Object[] messageParameters) {
                return "??" + key + "??";
            }
        });
        engine = springEngine;
    }

    @Test
    @DisplayName("edge.html рендерит карту SNI, предупреждения и конфиги")
    void edgePageRenders() {
        EdgeSniRowDto row = new EdgeSniRowDto("htmlacademy.ru", "127.0.0.1:8443",
                EdgeVisibilityService.TYPE_REALITY, false, List.of("104.21.32.1"),
                List.of(new EdgeWarningDto(EdgeVisibilityService.WARN_DNS_MISMATCH, "WARN",
                        "htmlacademy.ru", "104.21.32.1")));
        EdgeSniRowDto fallback = new EdgeSniRowDto("default", "127.0.0.1:8444",
                EdgeVisibilityService.TYPE_FALLBACK, false, List.of(), List.of());
        EdgeMapDto map = new EdgeMapDto("45.131.43.6", "pohr-roclh.xyz", "pohr-roclh.xyz",
                List.of(row), fallback,
                List.of(new EdgeWarningDto(EdgeVisibilityService.WARN_SITE_WITHOUT_ROUTE, "WARN",
                        "pohr-roclh.xyz", "fallback")));

        EdgeConfigFileDto nginx = new EdgeConfigFileDto("nginx.conf", "/app/xray/edge/nginx.conf",
                true, "stream {}\n", EdgeVisibilityService.sha256("stream {}\n"),
                "stream {}\n", EdgeVisibilityService.sha256("stream {}\n"), true);
        EdgeConfigFileDto caddy = new EdgeConfigFileDto("Caddyfile", "/app/xray/edge/Caddyfile",
                true, "pohr-roclh.xyz {}\n", EdgeVisibilityService.sha256("pohr-roclh.xyz {}\n"),
                "pohr-roclh.xyz {}\n", EdgeVisibilityService.sha256("pohr-roclh.xyz {}\n"), true);

        WebContext context = webContext();
        context.setVariable("successMessage", null);
        context.setVariable("errorMessage", null);
        context.setVariable("status", new EdgeStatusDto(true, 1L, null, true, 2L, null,
                true, true, 443, 80, 8444, 1, 1));
        context.setVariable("nginxLogs", List.of());
        context.setVariable("caddyLogs", List.of());
        context.setVariable("edgeMap", map);
        context.setVariable("configs", new EdgeConfigFilesDto(nginx, caddy));
        context.setVariable("configList", List.of(nginx, caddy));

        String html = engine.process("admin/edge/edge", context);

        assertThat(html)
                .contains("htmlacademy.ru")
                .contains("127.0.0.1:8443")
                .contains("edge-type-reality")
                .contains("edge-warning-list")
                .contains("stream {}")
                .contains("pohr-roclh.xyz {}")
                .contains("cfg-nginx.conf");
    }

    @Test
    @DisplayName("edge-preview.html рендерит unified diff")
    void previewPageRendersDiff() {
        EdgeConfigFileDto nginx = new EdgeConfigFileDto("nginx.conf", "/app/xray/edge/nginx.conf",
                true, "stream { new; }\n", EdgeVisibilityService.sha256("stream { new; }\n"),
                "stream {}\n", EdgeVisibilityService.sha256("stream {}\n"), false);
        EdgeConfigFileDto caddy = new EdgeConfigFileDto("Caddyfile", "/app/xray/edge/Caddyfile",
                true, "caddy\n", EdgeVisibilityService.sha256("caddy\n"),
                "caddy\n", EdgeVisibilityService.sha256("caddy\n"), true);

        WebContext context = webContext();
        context.setVariable("preview", new EdgePreviewDto(nginx, caddy,
                "--- nginx.conf (disk)\n+++ nginx.conf (generated)\n+stream { new; }\n", ""));
        context.setVariable("previewList", List.of(nginx, caddy));

        String html = engine.process("admin/edge/edge-preview", context);

        assertThat(html)
                .contains("+stream { new; }")
                .contains("nginx.conf")
                .contains("Caddyfile");
    }

    private static WebContext webContext() {
        var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
        var exchange = application.buildExchange(new MockHttpServletRequest(),
                new MockHttpServletResponse());
        return new WebContext(exchange, Locale.ENGLISH);
    }
}
