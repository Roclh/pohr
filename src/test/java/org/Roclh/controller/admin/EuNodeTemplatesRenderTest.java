package org.Roclh.controller.admin;

import org.Roclh.model.EuNode;
import org.Roclh.model.EuNodeProfileHistory;
import org.Roclh.model.NodeStatus;
import org.Roclh.model.dto.EuNodeDto;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeProfileValidator;
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

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест карточки «Reality / SNI»: шаблон должен рендерить форму профиля,
 * эффективные значения, историю и результат проверки.
 */
class EuNodeTemplatesRenderTest {

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
    @DisplayName("eu-node-detail.html рендерит карточку профиля, preview и историю")
    void detailPageRendersProfileCard() {
        EuNode node = node();
        EuNodeProfileHistory history = EuNodeProfileHistory.builder()
                .id(UUID.randomUUID())
                .nodeId(node.getId())
                .sni("old.pohr-roclh.xyz")
                .target("old.pohr-roclh.xyz:443")
                .fingerprint("safari")
                .createdAt(Instant.parse("2026-11-13T10:00:00Z"))
                .createdBy("admin")
                .build();

        WebContext context = webContext();
        context.setVariable("node", EuNodeDto.from(node));
        context.setVariable("rawNode", node);
        context.setVariable("profileForm", new EuNodeProfileForm(node.getSni(), node.getTarget(), node.getFingerprint()));
        context.setVariable("effective", new EuConfigService.EffectiveProfile(
                "eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox", true, true, true));
        context.setVariable("history", List.of(history));
        context.setVariable("validation", new EuNodeProfileValidator.ValidationResult(
                true, List.of(), List.of("TLS-проверка пройдена: TLS 1.3"), null));

        String html = engine.process("admin/eu/eu-node-detail", context);

        assertThat(html)
                .contains("name=\"sni\"")
                .contains("eu.pohr-roclh.xyz")
                .contains("name=\"target\"")
                .contains("eu.pohr-roclh.xyz:443")
                .contains("/admin/nodes/" + node.getId() + "/profile/validate")
                .contains("/admin/nodes/" + node.getId() + "/profile/preview")
                .contains("old.pohr-roclh.xyz")
                .contains("TLS-проверка пройдена");
    }

    private static EuNode node() {
        return EuNode.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("eu-poland-1")
                .host("78.17.145.160")
                .port(8443)
                .realityPublicKey("public-key")
                .realityShortId("aabbccdd")
                .tunnelUuid(UUID.randomUUID())
                .nodeSecret("secret")
                .status(NodeStatus.HEALTHY)
                .sni("eu.pohr-roclh.xyz")
                .target("eu.pohr-roclh.xyz:443")
                .fingerprint("firefox")
                .profileUpdatedAt(Instant.parse("2026-11-14T20:00:00Z"))
                .enrolledAt(Instant.parse("2026-11-01T10:00:00Z"))
                .enrolledBy(UUID.randomUUID())
                .build();
    }

    private static WebContext webContext() {
        var application = JakartaServletWebApplication.buildApplication(new MockServletContext());
        var exchange = application.buildExchange(new MockHttpServletRequest(),
                new MockHttpServletResponse());
        return new WebContext(exchange, Locale.ENGLISH);
    }
}
