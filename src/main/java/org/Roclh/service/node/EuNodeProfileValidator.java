package org.Roclh.service.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.service.edge.EdgeDnsResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка профиля REALITY узла до сохранения (Т03).
 *
 * <p>Ошибки блокируют сохранение, предупреждения — нет: профиль может быть
 * валиден по формату, но указывать на чужой IP или CDN, и это осознанный
 * выбор админа. Сетевые проверки (DNS, TLS) выполняются только если базовые
 * поля корректны, чтобы не тратить 5 секунд на заведомо битую пару.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EuNodeProfileValidator {

    /** Домен без scheme/port; punycode и цифры допустимы. */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$");
    private static final Pattern TARGET = Pattern.compile(
            "^(\\[[0-9a-fA-F:]+]|[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?):(\\d{1,5})$");
    private static final Pattern BRAND = Pattern.compile(
            "(?i)(^|[.-])(apple|icloud|microsoft|google|cloudflare)([.-]|$)");
    private static final Set<String> FINGERPRINTS =
            Set.of("firefox", "edge", "chrome", "safari", "random");
    private static final Set<String> CDN_TOKENS = Set.of(
            "cloudflare", "akamai", "fastly", "cloudfront", "azureedge", "edgekey",
            "edgesuite", "akamaiedge", "cdn");

    private final EdgeDnsResolver dnsResolver;
    private final TlsProbe tlsProbe;

    public ValidationResult validate(EuNodeProfileForm form, EuNode node) {
        EuNodeProfileForm profile = form == null ? EuNodeProfileForm.empty() : form.normalized();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (profile.isEmpty()) {
            warnings.add("Профиль пустой: узел использует pohr.eu-nodes.reality.sni/target и fingerprint=firefox.");
            return new ValidationResult(true, errors, warnings, null);
        }

        if (profile.sni() == null) {
            errors.add("sni обязателен, если задан target или fingerprint");
        } else {
            validateSni(profile.sni(), errors);
        }

        TargetParts target = null;
        if (profile.target() == null) {
            errors.add("target обязателен в формате host:port, например eu.pohr-roclh.xyz:443");
        } else {
            target = parseTarget(profile.target(), errors);
        }

        validateFingerprint(profile.fingerprint(), errors, warnings);

        if (!errors.isEmpty()) {
            return new ValidationResult(false, errors, warnings, null);
        }

        checkDns(profile.sni(), node, warnings);
        if (target != null) {
            checkCdn(target.host(), warnings);
        }

        TlsProbe.Result tls = tlsProbe.probe(target.host(), target.port(), profile.sni());
        if (!tls.ok()) {
            errors.add("TLS-проверка " + target + " с SNI " + profile.sni()
                    + " не прошла: " + tls.error());
        } else if (!"TLSv1.3".equals(tls.tlsVersion())) {
            errors.add("target отвечает по " + tls.tlsVersion() + ", для REALITY нужен TLS 1.3");
        } else if (!sanContains(tls.subjectAltNames(), profile.sni())) {
            errors.add("Сертификат target не содержит sni '" + profile.sni()
                    + "' в SAN: " + tls.subjectAltNames());
        } else {
            warnings.add("TLS-проверка пройдена: TLS 1.3, SAN содержит " + profile.sni() + ".");
        }

        return new ValidationResult(errors.isEmpty(), errors, warnings, tls);
    }

    private void validateSni(String sni, List<String> errors) {
        if (sni.length() > 253) {
            errors.add("sni длиннее 253 символов");
            return;
        }
        if (!DOMAIN.matcher(sni).matches()) {
            errors.add("sni должен быть доменом без scheme, порта и пробелов: " + sni);
            return;
        }
        if (BRAND.matcher(sni).find()) {
            errors.add("sni не должен использовать брендовые домены (apple/icloud/microsoft/google/cloudflare): " + sni);
        }
    }

    private TargetParts parseTarget(String value, List<String> errors) {
        if (value.length() > 255) {
            errors.add("target длиннее 255 символов");
            return null;
        }
        Matcher matcher = TARGET.matcher(value);
        if (!matcher.matches()) {
            errors.add("target должен быть в формате host:port (без scheme и пути): " + value);
            return null;
        }
        String host = matcher.group(1);
        int port;
        try {
            port = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            errors.add("target: некорректный порт");
            return null;
        }
        if (port < 1 || port > 65535) {
            errors.add("target: порт должен быть в диапазоне 1..65535");
            return null;
        }
        String bareHost = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        return new TargetParts(bareHost, port, value);
    }

    private void validateFingerprint(String fingerprint, List<String> errors, List<String> warnings) {
        if (fingerprint == null) {
            warnings.add("fingerprint не задан: RU-outbound использует firefox.");
            return;
        }
        String fp = fingerprint.toLowerCase(Locale.ROOT);
        if (!FINGERPRINTS.contains(fp)) {
            errors.add("fingerprint должен быть одним из firefox/edge/chrome/safari/random: " + fingerprint);
            return;
        }
        if ("random".equals(fp)) {
            errors.add("fingerprint=random нестабилен и хуже для скрытности; выбери firefox/edge/safari");
        } else if ("chrome".equals(fp)) {
            warnings.add("fingerprint=chrome — самый распространённый отпечаток; для маскировки лучше firefox/edge/safari.");
        }
    }

    /**
     * DNS — только предупреждение: SNI может резолвиться не в host узла,
     * например при переезде или перед обновлением A-записи.
     */
    private void checkDns(String sni, EuNode node, List<String> warnings) {
        EdgeDnsResolver.Lookup sniLookup = dnsResolver.lookup(sni);
        if (!sniLookup.resolved()) {
            warnings.add("sni '" + sni + "' не резолвится: " + sniLookup.error());
            return;
        }
        if (node == null || node.getHost() == null || node.getHost().isBlank()) {
            return;
        }
        String nodeHost = node.getHost().trim();
        EdgeDnsResolver.Lookup hostLookup = dnsResolver.lookup(nodeHost);
        Set<String> nodeAddresses = new HashSet<>(hostLookup.resolved()
                ? hostLookup.addresses()
                : List.of(nodeHost));
        boolean matches = sniLookup.addresses().stream().anyMatch(nodeAddresses::contains);
        if (!matches) {
            warnings.add("sni резолвится в " + sniLookup.addresses()
                    + ", а host узла — " + nodeHost + ": SNI не указывает на этот узел.");
        }
    }

    private void checkCdn(String targetHost, List<String> warnings) {
        String host = targetHost.toLowerCase(Locale.ROOT);
        boolean cdn = CDN_TOKENS.stream().anyMatch(host::contains);
        if (cdn) {
            warnings.add("target похож на CDN (" + targetHost + "): REALITY через CDN не работает и это лишний детект.");
        }
    }

    static boolean sanContains(List<String> sans, String sni) {
        if (sans == null || sni == null) return false;
        String wanted = sni.toLowerCase(Locale.ROOT);
        for (String san : sans) {
            String candidate = san.toLowerCase(Locale.ROOT);
            if (candidate.equals(wanted)) return true;
            if (candidate.startsWith("*.")) {
                String suffix = candidate.substring(1); // ".example.com"
                if (wanted.endsWith(suffix)
                        && wanted.substring(0, wanted.length() - suffix.length()).indexOf('.') < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private record TargetParts(String host, int port, String raw) {
        @Override
        public String toString() {
            return raw;
        }
    }

    /**
     * Итог проверки. {@code tls} может быть null, если проверка не дошла до
     * сети (битый формат) или профиль пустой.
     */
    public record ValidationResult(
            boolean ok,
            List<String> errors,
            List<String> warnings,
            TlsProbe.Result tls
    ) {
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }
}
