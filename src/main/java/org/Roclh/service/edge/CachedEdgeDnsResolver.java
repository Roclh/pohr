package org.Roclh.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNS-резолв SNI с кэшем на 5 минут. Карта SNI рендерится на каждый запрос
 * админки; без кэша страница делала бы десятки DNS-запросов подряд.
 */
@Slf4j
@Component
public class CachedEdgeDnsResolver implements EdgeDnsResolver {

    static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(Lookup lookup, Instant expiresAt) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Clock clock;

    public CachedEdgeDnsResolver() {
        this(Clock.systemUTC());
    }

    /** Конструктор для тестов: позволяет подменить часы. */
    CachedEdgeDnsResolver(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Lookup lookup(String host) {
        if (host == null || host.isBlank()) {
            return Lookup.failed("empty host");
        }
        String key = host.trim().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.lookup();
        }
        Lookup lookup = resolveHost(key);
        cache.put(key, new Entry(lookup, now.plus(TTL)));
        return lookup;
    }

    /** Собственно резолв. Вынесен protected, чтобы тест мог считать вызовы. */
    protected Lookup resolveHost(String host) {
        try {
            List<String> addresses = Arrays.stream(InetAddress.getAllByName(host))
                    .map(InetAddress::getHostAddress)
                    .distinct()
                    .sorted()
                    .toList();
            return addresses.isEmpty()
                    ? Lookup.failed("no A/AAAA records")
                    : Lookup.ok(addresses);
        } catch (UnknownHostException e) {
            log.debug("Edge SNI '{}' does not resolve: {}", host, e.getMessage());
            return Lookup.failed(e.getMessage() == null ? "unknown host" : e.getMessage());
        }
    }
}
