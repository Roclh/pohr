package org.Roclh.service.node;

import org.Roclh.model.EuNode;
import org.Roclh.repository.telegram.TelegramProxyConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EuConfigServiceTest {

    private static final String GLOBAL_DEST = "127.0.0.1:443";
    private static final String GLOBAL_SNI = "eu.pohr-roclh.xyz";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EuConfigService service;

    @BeforeEach
    void setUp() {
        service = new EuConfigService(mock(TelegramProxyConfigRepository.class));
        ReflectionTestUtils.setField(service, "defaultSniValue", GLOBAL_SNI);
        ReflectionTestUtils.setField(service, "defaultTargetValue", GLOBAL_DEST);
    }

    @Test
    @DisplayName("без профиля EU-конфиг берёт глобальные dest/serverNames")
    void euConfigFallsBackToGlobals() {
        String config = service.buildEuConfig(node(null, null, null));

        assertThat(config).contains("\"dest\": \"" + GLOBAL_DEST + "\"");
        assertThat(config).contains("\"serverNames\": [\"" + GLOBAL_SNI + "\"]");
    }

    @Test
    @DisplayName("с профилем EU-конфиг берёт dest/serverNames узла")
    void euConfigUsesNodeProfile() {
        String config = service.buildEuConfig(node("eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox"));

        assertThat(config).contains("\"dest\": \"eu.pohr-roclh.xyz:443\"");
        assertThat(config).contains("\"serverNames\": [\"eu.pohr-roclh.xyz\"]");
    }

    @Test
    @DisplayName("RU-outbound берёт serverName/fingerprint из профиля")
    void euOutboundUsesProfile() throws Exception {
        JsonNode outbound = objectMapper.readTree(
                objectMapper.writeValueAsString(
                        service.buildEuOutbound(node("eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "safari"))));

        JsonNode reality = outbound.path("streamSettings").path("realitySettings");
        assertThat(reality.path("serverName").asText()).isEqualTo("eu.pohr-roclh.xyz");
        assertThat(reality.path("fingerprint").asText()).isEqualTo("safari");
    }

    @Test
    @DisplayName("RU-outbound без профиля — глобальный serverName и firefox вместо chrome")
    void euOutboundFallsBackToFirefox() throws Exception {
        JsonNode outbound = objectMapper.readTree(
                objectMapper.writeValueAsString(
                        service.buildEuOutbound(node(null, null, null))));

        JsonNode reality = outbound.path("streamSettings").path("realitySettings");
        assertThat(reality.path("serverName").asText()).isEqualTo(GLOBAL_SNI);
        assertThat(reality.path("fingerprint").asText()).isEqualTo("firefox");
    }

    @Test
    @DisplayName("частичный профиль: своё значение только для заданного поля")
    void partialProfileMixesWithGlobals() {
        EuNode node = node("edge.pohr-roclh.xyz", null, null);
        EuConfigService.EffectiveProfile effective = service.effectiveProfile(node);

        assertThat(effective.sni()).isEqualTo("edge.pohr-roclh.xyz");
        assertThat(effective.sniFromNode()).isTrue();
        assertThat(effective.target()).isEqualTo(GLOBAL_DEST);
        assertThat(effective.targetFromNode()).isFalse();
        assertThat(effective.fingerprint()).isEqualTo("firefox");
        assertThat(effective.fingerprintFromNode()).isFalse();
    }

    private static EuNode node(String sni, String target, String fingerprint) {
        return EuNode.builder()
                .id(UUID.randomUUID())
                .name("eu-poland-1")
                .host("78.17.145.160")
                .port(8443)
                .realityPublicKey("public-key")
                .realityShortId("aabbccdd")
                .tunnelUuid(UUID.randomUUID())
                .nodeSecret("secret")
                .sni(sni)
                .target(target)
                .fingerprint(fingerprint)
                .build();
    }
}
