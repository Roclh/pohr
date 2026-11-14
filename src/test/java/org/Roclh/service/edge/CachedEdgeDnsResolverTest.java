package org.Roclh.service.edge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachedEdgeDnsResolverTest {

    @Test
    @DisplayName("DNS-резолв кэшируется на 5 минут и обновляется после истечения")
    void cachesLookupsForFiveMinutes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-14T10:00:00Z"));
        CountingResolver resolver = new CountingResolver(clock);

        assertThat(resolver.lookup("htmlacademy.ru").addresses()).containsExactly("1.2.3.4");
        resolver.lookup("htmlacademy.ru");
        resolver.lookup("HTMLACADEMY.RU");
        assertThat(resolver.calls).isEqualTo(1);

        resolver.lookup("dodopizza.ru");
        assertThat(resolver.calls).isEqualTo(2);

        clock.advance(Duration.ofMinutes(4));
        resolver.lookup("htmlacademy.ru");
        assertThat(resolver.calls).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        resolver.lookup("htmlacademy.ru");
        assertThat(resolver.calls).isEqualTo(3);
    }

    private static final class CountingResolver extends CachedEdgeDnsResolver {
        private int calls;

        private CountingResolver(Clock clock) {
            super(clock);
        }

        @Override
        protected Lookup resolveHost(String host) {
            calls++;
            return Lookup.ok(List.of("1.2.3.4"));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
