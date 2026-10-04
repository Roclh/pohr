package org.Roclh.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.ClientConfig;
import org.Roclh.repository.ClientConfigRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientConfigService {

    private final ClientConfigRepository repository;

    @PostConstruct
    void seedDefaults() {
        try {
            seedIfMissing("happ", "Happ", """
                    profile-title: Pohr
                    profile-update-interval: 6
                    fragmentation-enable: 1
                    fragmentation-packets: tlshello
                    fragmentation-length: 100-200
                    fragmentation-interval: 10-20
                    """);
            seedIfMissing("v2rayng", "v2rayNG", """
                    profile-title: Pohr
                    profile-update-interval: 6
                    """);
            seedIfMissing("v2rayn", "v2rayN", """
                    profile-title: Pohr
                    profile-update-interval: 6
                    """);
        } catch (Exception e) {
            log.error("Failed to seed client config defaults: {}", e.getMessage(), e);
        }
    }

    public List<ClientConfig> findAll() {
        return repository.findAll(Sort.by("clientName"));
    }

    public Optional<ClientConfig> findByName(String name) {
        return repository.findById(name);
    }

    /**
     * Выбирает наиболее специфичный (самый длинный) матчащийся шаблон.
     * Иначе "v2rayN" перехватывал бы UA "v2rayNG/1.8.x" (подстрока).
     */
    public Optional<ClientConfig> findByUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return Optional.empty();
        return repository.findAllByEnabledTrue().stream()
                .filter(cfg -> cfg.getUserAgentPattern() != null
                        && !cfg.getUserAgentPattern().isBlank()
                        && userAgent.contains(cfg.getUserAgentPattern()))
                .max(Comparator.comparingInt(c -> c.getUserAgentPattern().length()));
    }

    @Transactional
    public ClientConfig update(String name, boolean enabled, String uaPattern, String headers) {
        ClientConfig cfg = repository.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown client: " + name));
        cfg.setEnabled(enabled);
        cfg.setUserAgentPattern(uaPattern);
        cfg.setHeaders(headers == null ? "" : headers);
        return repository.save(cfg);
    }

    /** Парсит "Name: value" построчно. Пустые строки и # — игнорируются. */
    public Map<String, String> parseHeaders(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0) continue;
            String name = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (!name.isEmpty()) result.put(name, value);
        }
        return result;
    }

    private void seedIfMissing(String name, String uaPattern, String headers) {
        if (repository.existsById(name)) return;
        repository.save(ClientConfig.builder()
                .clientName(name)
                .enabled(true)
                .userAgentPattern(uaPattern)
                .headers(headers.strip())
                .build());
        log.info("Seeded default client config for '{}'", name);
    }
}