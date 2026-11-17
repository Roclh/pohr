package org.Roclh.service.node;

import java.util.List;

/**
 * Проверка TLS-пары «target + SNI» без применения профиля.
 *
 * <p>Отделено интерфейсом, чтобы валидатор можно было тестировать без сети и
 * чтобы в будущем заменить реализацию (например, на вызов {@code xray tls
 * ping}) без правки бизнес-логики.</p>
 */
public interface TlsProbe {

    /**
     * Один TLS-handshake к {@code host:port} с указанным SNI.
     *
     * @return версия протокола и SAN сертификата либо текст ошибки
     */
    Result probe(String host, int port, String sni);

    record Result(boolean ok, String tlsVersion, List<String> subjectAltNames, String error) {

        public static Result ok(String tlsVersion, List<String> sans) {
            return new Result(true, tlsVersion, List.copyOf(sans), null);
        }

        public static Result failed(String error) {
            return new Result(false, null, List.of(), error);
        }
    }
}
