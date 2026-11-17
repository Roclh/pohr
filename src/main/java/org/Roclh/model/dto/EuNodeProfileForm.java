package org.Roclh.model.dto;

/**
 * Профиль REALITY узла (Т03). Пустые значения означают «использовать
 * глобальные настройки» — так существующие узлы продолжают работать без
 * заполненного профиля.
 */
public record EuNodeProfileForm(
        String sni,
        String target,
        String fingerprint
) {

    public static EuNodeProfileForm empty() {
        return new EuNodeProfileForm(null, null, null);
    }

    public EuNodeProfileForm normalized() {
        return new EuNodeProfileForm(trim(sni), trim(target), trim(fingerprint));
    }

    public boolean isEmpty() {
        EuNodeProfileForm n = normalized();
        return n.sni() == null && n.target() == null && n.fingerprint() == null;
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
