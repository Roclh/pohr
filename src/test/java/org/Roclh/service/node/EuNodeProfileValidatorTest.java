package org.Roclh.service.node;

import org.Roclh.model.EuNode;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.service.edge.EdgeDnsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EuNodeProfileValidatorTest {

    private static final String SNI = "eu.pohr-roclh.xyz";
    private static final String NODE_HOST = "78.17.145.160";

    private EdgeDnsResolver dns;
    private TlsProbe tls;
    private EuNodeProfileValidator validator;

    @BeforeEach
    void setUp() {
        dns = mock(EdgeDnsResolver.class);
        tls = mock(TlsProbe.class);
        validator = new EuNodeProfileValidator(dns, tls);

        when(dns.lookup(anyString())).thenReturn(EdgeDnsResolver.Lookup.ok(List.of(NODE_HOST)));
    }

    @Test
    @DisplayName("валидный профиль проходит проверку")
    void validProfilePasses() {
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.3", List.of(SNI)));

        var result = validator.validate(form(SNI, SNI + ":443", "firefox"), node());

        assertThat(result.ok()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.tls()).isNotNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("TLS-проверка пройдена"));
    }

    @Test
    @DisplayName("пустой профиль валиден и не ходит в сеть")
    void emptyProfileIsValidWithoutNetwork() {
        var result = validator.validate(EuNodeProfileForm.empty(), node());

        assertThat(result.ok()).isTrue();
        assertThat(result.tls()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("Профиль пустой"));
        verify(tls, never()).probe(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @DisplayName("битый домен SNI — ошибка")
    void brokenDomainFails() {
        var result = validator.validate(form("not a domain", "example.com:443", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("sni"));
    }

    @Test
    @DisplayName("брендовый SNI — ошибка")
    void brandSniFails() {
        var result = validator.validate(form("www.microsoft.com", "www.microsoft.com:443", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("брендовые"));
    }

    @Test
    @DisplayName("target без порта — ошибка")
    void targetWithoutPortFails() {
        var result = validator.validate(form(SNI, "eu.pohr-roclh.xyz", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("host:port"));
    }

    @Test
    @DisplayName("fingerprint=random — ошибка")
    void randomFingerprintFails() {
        var result = validator.validate(form(SNI, SNI + ":443", "random"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("random"));
    }

    @Test
    @DisplayName("fingerprint=chrome — предупреждение, но не ошибка")
    void chromeFingerprintWarns() {
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.3", List.of(SNI)));

        var result = validator.validate(form(SNI, SNI + ":443", "chrome"), node());

        assertThat(result.ok()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("chrome"));
    }

    @Test
    @DisplayName("недоступный TLS — ошибка")
    void unreachableTlsFails() {
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.failed("ConnectException: refused"));

        var result = validator.validate(form(SNI, SNI + ":443", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("TLS-проверка"));
    }

    @Test
    @DisplayName("TLS 1.2 вместо 1.3 — ошибка")
    void tls12Fails() {
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.2", List.of(SNI)));

        var result = validator.validate(form(SNI, SNI + ":443", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("TLS 1.3"));
    }

    @Test
    @DisplayName("SAN не содержит SNI — ошибка")
    void sanMismatchFails() {
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.3", List.of("other.example")));

        var result = validator.validate(form(SNI, SNI + ":443", "firefox"), node());

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("SAN"));
    }

    @Test
    @DisplayName("DNS не указывает на host узла — только предупреждение")
    void dnsMismatchIsWarningOnly() {
        when(dns.lookup(SNI)).thenReturn(EdgeDnsResolver.Lookup.ok(List.of("203.0.113.10")));
        when(dns.lookup(NODE_HOST)).thenReturn(EdgeDnsResolver.Lookup.ok(List.of(NODE_HOST)));
        when(tls.probe(SNI, 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.3", List.of(SNI)));

        var result = validator.validate(form(SNI, SNI + ":443", "firefox"), node());

        assertThat(result.ok()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("host узла"));
    }

    @Test
    @DisplayName("target за CDN — предупреждение")
    void cdnTargetWarns() {
        when(tls.probe("cdn.example.com", 443, SNI))
                .thenReturn(TlsProbe.Result.ok("TLSv1.3", List.of(SNI)));

        var result = validator.validate(form(SNI, "cdn.example.com:443", "firefox"), node());

        assertThat(result.ok()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("CDN"));
    }

    @Test
    @DisplayName("wildcard SAN покрывает SNI")
    void wildcardSanMatches() {
        assertThat(EuNodeProfileValidator.sanContains(List.of("*.pohr-roclh.xyz"), SNI)).isTrue();
        assertThat(EuNodeProfileValidator.sanContains(List.of("*.pohr-roclh.xyz"), "a.b.pohr-roclh.xyz")).isFalse();
    }

    private static EuNodeProfileForm form(String sni, String target, String fingerprint) {
        return new EuNodeProfileForm(sni, target, fingerprint);
    }

    private static EuNode node() {
        return EuNode.builder()
                .id(UUID.randomUUID())
                .name("eu-poland-1")
                .host(NODE_HOST)
                .port(8443)
                .realityPublicKey("public-key")
                .realityShortId("aabbccdd")
                .tunnelUuid(UUID.randomUUID())
                .nodeSecret("secret")
                .build();
    }
}
