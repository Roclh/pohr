package org.Roclh.service.edge;

import org.Roclh.model.edge.EdgeConfig;
import org.Roclh.model.edge.EdgeSite;
import org.Roclh.repository.edge.EdgeConfigRepository;
import org.Roclh.repository.edge.EdgeRouteRepository;
import org.Roclh.repository.edge.EdgeSiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeConfigServiceTest {

    private EdgeConfigRepository cfgRepo;
    private EdgeRouteRepository routeRepo;
    private EdgeSiteRepository siteRepo;
    private EdgeConfigService service;

    @BeforeEach
    void setUp() {
        cfgRepo = mock(EdgeConfigRepository.class);
        routeRepo = mock(EdgeRouteRepository.class);
        siteRepo = mock(EdgeSiteRepository.class);
        service = new EdgeConfigService(cfgRepo, routeRepo, siteRepo);
        ReflectionTestUtils.setField(service, "edgeHome", "/app/xray");

        when(cfgRepo.findById("default")).thenReturn(Optional.of(EdgeConfig.builder()
                .name("default")
                .streamPort(443)
                .httpPort(80)
                .httpsPort(8444)
                .fallbackTarget("127.0.0.1:8444")
                .workerProcesses("auto")
                .workerConnections(768)
                .streamProxyTimeout("300s")
                .streamConnectTimeout("5s")
                .autoHttps("disable_redirects")
                .build()));
        when(routeRepo.findByEnabledTrueOrderBySortOrderAsc()).thenReturn(List.of());
    }

    @Test
    void staticSiteUsesFileServerAndRootPath() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("www.pohr-roclh.xyz", "static", "/app/xray/site")));

        String caddyfile = service.buildCaddyfile();

        assertThat(caddyfile)
                .contains("www.pohr-roclh.xyz {")
                .contains("root * /app/xray/site")
                .contains("file_server")
                .contains("encode gzip")
                .contains("http://www.pohr-roclh.xyz {")
                .contains("redir https://{host}{uri} 301")
                .doesNotContain("reverse_proxy");
    }

    @Test
    void staticSiteWithoutRootPathFallsBackToPlaceholderDir() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("edge.pohr-roclh.xyz", "static", null)));

        assertThat(service.buildCaddyfile()).contains("root * /app/xray/site");
    }

    @Test
    void proxySiteStillUsesReverseProxy() {
        when(siteRepo.findByEnabledTrueOrderByDomainAsc()).thenReturn(List.of(
                site("pohr-roclh.xyz", "proxy", null)));

        String caddyfile = service.buildCaddyfile();

        assertThat(caddyfile)
                .contains("reverse_proxy 127.0.0.1:8080")
                .contains("http://pohr-roclh.xyz {")
                .doesNotContain("file_server");
    }

    private static EdgeSite site(String domain, String type, String rootPath) {
        return EdgeSite.builder()
                .domain(domain)
                .siteType(type)
                .rootPath(rootPath)
                .upstreamHost("127.0.0.1")
                .upstreamPort(8080)
                .bindAddress(null)
                .tlsMode("acme")
                .enabled(true)
                .build();
    }
}
