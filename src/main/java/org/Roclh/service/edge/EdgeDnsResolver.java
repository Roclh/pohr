package org.Roclh.service.edge;

import java.util.List;

/**
 * Резолвер SNI в A/AAAA-записи. Отделён интерфейсом, чтобы карту можно было
 * тестировать без реального DNS.
 */
public interface EdgeDnsResolver {

    Lookup lookup(String host);

    record Lookup(List<String> addresses, String error) {

        public static Lookup ok(List<String> addresses) {
            return new Lookup(List.copyOf(addresses), null);
        }

        public static Lookup failed(String error) {
            return new Lookup(List.of(), error);
        }

        public boolean resolved() {
            return error == null && !addresses.isEmpty();
        }
    }
}
