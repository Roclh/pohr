package org.Roclh.service.node;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * TLS-проба на JSSE: один handshake, таймаут 5 с, проверка версии протокола и
 * SAN сертификата.
 *
 * <p>Почему не {@code xray tls ping}: команда Xray умеет только
 * {@code xray tls ping [-ip <ip>] <domain>} — она всегда ходит на :443 и не
 * позволяет разделить адрес target и SNI. Профиль же допускает произвольный
 * порт target, поэтому проба делается напрямую через сокет с SNI = {@code sni}.
 * Проверка цепочки остаётся включённой (дефолтный trust store): самоподписанный
 * сертификат — заведомо нерабочая цель для REALITY.</p>
 */
@Slf4j
@Component
public class JavaTlsProbe implements TlsProbe {

    private static final int TIMEOUT_MS = 5_000;

    @Override
    public Result probe(String host, int port, String sni) {
        if (host == null || host.isBlank() || sni == null || sni.isBlank()) {
            return Result.failed("host и sni обязательны");
        }
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);

            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(sni)));
            // Проверку имени делаем сами по SAN: SNI и target.host могут
            // легально не совпадать (target — это адрес сайта-прикрытия).
            params.setEndpointIdentificationAlgorithm(null);
            socket.setSSLParameters(params);

            socket.startHandshake();
            SSLSession session = socket.getSession();
            String version = session.getProtocol();
            List<String> sans = subjectAltNames(session);
            if (!"TLSv1.3".equals(version)) {
                return Result.failed("TLS " + version + " вместо TLS 1.3");
            }
            return Result.ok(version, sans);
        } catch (Exception e) {
            log.debug("TLS probe to {}:{} with SNI '{}' failed: {}", host, port, sni, e.getMessage());
            return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static List<String> subjectAltNames(SSLSession session) {
        try {
            Certificate[] chain = session.getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate cert)) {
                return List.of();
            }
            Collection<List<?>> entries = cert.getSubjectAlternativeNames();
            if (entries == null) return List.of();
            List<String> names = new ArrayList<>();
            for (List<?> entry : entries) {
                if (entry.size() < 2) continue;
                Object type = entry.get(0);
                Object value = entry.get(1);
                // 2 = dNSName, 7 = iPAddress
                if ((Integer.valueOf(2).equals(type) || Integer.valueOf(7).equals(type)) && value != null) {
                    names.add(value.toString());
                }
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }
}
