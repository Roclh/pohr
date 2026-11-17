package org.Roclh.service.node;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EuNode;
import org.Roclh.model.EuNodeProfileHistory;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.repository.EuNodeProfileHistoryRepository;
import org.Roclh.repository.EuNodeRepository;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Сохранение и применение профиля REALITY узла (Т03).
 *
 * <p>Сохранение профиля — это не просто UPDATE: меняется и EU-конфиг (его
 * подхватит EU-агент при следующем опросе), и RU-outbound (его нужно
 * материализовать и применить перезапуском Xray). Поэтому
 * {@link #apply(UUID, EuNodeProfileForm, String)} — осознанное действие
 * «Применить», а не каждое редактирование формы.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EuNodeProfileService {

    static final int HISTORY_LIMIT = 5;

    private final EuNodeRepository nodeRepository;
    private final EuNodeProfileHistoryRepository historyRepository;
    private final EuNodeProfileValidator validator;
    private final EuConfigService euConfigService;
    private final XrayConfigMaterializer materializer;
    private final XrayProcessManager xrayProcessManager;

    public EuNode requireNode(UUID id) {
        return nodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Узел не найден: " + id));
    }

    public EuNodeProfileValidator.ValidationResult validate(EuNode node, EuNodeProfileForm form) {
        return validator.validate(form, node);
    }

    /**
     * Проверить, сохранить профиль, пересобрать RU-конфиг и при необходимости
     * перезапустить Xray. Если валидация не прошла — ничего не сохраняется.
     */
    public ApplyResult apply(UUID id, EuNodeProfileForm form, String actor) {
        EuNode node = requireNode(id);
        EuNodeProfileForm profile = form == null ? EuNodeProfileForm.empty() : form.normalized();
        EuNodeProfileValidator.ValidationResult validation = validator.validate(profile, node);
        if (!validation.ok()) {
            return new ApplyResult(false, validation, node,
                    profileHash(node), configHash(node), false, false);
        }

        String previousSni = node.getSni();
        String previousTarget = node.getTarget();
        String previousFingerprint = node.getFingerprint();
        boolean changed = !Objects.equals(previousSni, profile.sni())
                || !Objects.equals(previousTarget, profile.target())
                || !Objects.equals(previousFingerprint, profile.fingerprint());
        if (changed) {
            recordHistory(node, actor);
        }
        node.setSni(profile.sni());
        node.setTarget(profile.target());
        node.setFingerprint(profile.fingerprint());
        node.setProfileUpdatedAt(Instant.now());
        // Пока агент не пришлёт новый hash, сохранённый описывает прошлый конфиг.
        node.setConfigHash(null);
        nodeRepository.save(node);
        pruneHistory(node.getId());

        // Перезапуск Xray рвёт сессии, поэтому только при реальном изменении.
        boolean restarted = changed && materializeAndRestart();
        log.info("Applied REALITY profile to node '{}': sni={}, target={}, fingerprint={} (changed={}, restarted={})",
                node.getName(), profile.sni(), profile.target(), profile.fingerprint(), changed, restarted);
        return new ApplyResult(true, validation, node, profileHash(node), configHash(node), changed, restarted);
    }

    /** Откат на предыдущий сохранённый профиль (последние 5 значений). */
    public ApplyResult rollback(UUID id, String actor) {
        EuNode node = requireNode(id);
        List<EuNodeProfileHistory> history = historyRepository.findByNodeIdOrderByCreatedAtDesc(id);
        if (history.isEmpty()) {
            throw new IllegalStateException("Для узла нет сохранённых профилей — откатывать некуда");
        }
        EuNodeProfileHistory previous = history.getFirst();
        historyRepository.delete(previous);

        node.setSni(previous.getSni());
        node.setTarget(previous.getTarget());
        node.setFingerprint(previous.getFingerprint());
        node.setProfileUpdatedAt(Instant.now());
        node.setConfigHash(null);
        nodeRepository.save(node);

        boolean restarted = materializeAndRestart();
        log.info("Rolled back REALITY profile of node '{}' to sni={}, target={}, fingerprint={} (by {})",
                node.getName(), previous.getSni(), previous.getTarget(), previous.getFingerprint(), actor);

        EuNodeProfileValidator.ValidationResult result = new EuNodeProfileValidator.ValidationResult(
                true,
                List.of(),
                List.of("Профиль откатан к предыдущему сохранённому значению."),
                null);
        return new ApplyResult(true, result, node, profileHash(node), configHash(node), true, restarted);
    }

    /**
     * Помечает узел к синхронизации: сбрасывает сохранённый hash конфига и
     * пересобирает RU-конфиг. Работающий Xray не перезапускается — на RU
     * outbound не изменился, а EU сам опросит конфиг в течение 5 минут.
     */
    public SyncResult sync(UUID id) {
        EuNode node = requireNode(id);
        node.setConfigHash(null);
        nodeRepository.save(node);
        materializer.materialize();
        String configHash = configHash(node);
        log.info("Marked EU node '{}' for config sync (configHash={})", node.getName(), configHash);
        return new SyncResult(node, profileHash(node), configHash);
    }

    public List<EuNodeProfileHistory> history(UUID nodeId) {
        return historyRepository.findByNodeIdOrderByCreatedAtDesc(nodeId)
                .stream()
                .limit(HISTORY_LIMIT)
                .toList();
    }

    /** Хэш профиля (не конфига): по нему MCP ловит конфликт правок. */
    public String profileHash(EuNode node) {
        String canonical = node.getId() + "\u0000"
                + nullSafe(node.getSni()) + "\u0000"
                + nullSafe(node.getTarget()) + "\u0000"
                + nullSafe(node.getFingerprint());
        return sha256(canonical);
    }

    /** Хэш EU-конфига — ровно тот, что увидит EU-агент. */
    public String configHash(EuNode node) {
        return euConfigService.computeHash(euConfigService.buildEuConfig(node));
    }

    public EuConfigService.EffectiveProfile effectiveProfile(EuNode node) {
        return euConfigService.effectiveProfile(node);
    }

    // --- Внутреннее -----------------------------------------------------------

    private boolean materializeAndRestart() {
        materializer.materialize();
        if (xrayProcessManager.isRunning()) {
            return xrayProcessManager.restart();
        }
        return false;
    }

    private void recordHistory(EuNode node, String actor) {
        historyRepository.save(EuNodeProfileHistory.builder()
                .nodeId(node.getId())
                .sni(node.getSni())
                .target(node.getTarget())
                .fingerprint(node.getFingerprint())
                .createdAt(Instant.now())
                .createdBy(actor)
                .build());
    }

    private void pruneHistory(UUID nodeId) {
        List<EuNodeProfileHistory> history =
                historyRepository.findByNodeIdOrderByCreatedAtDesc(nodeId);
        if (history.size() > HISTORY_LIMIT) {
            historyRepository.deleteAll(history.subList(HISTORY_LIMIT, history.size()));
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось посчитать hash профиля", e);
        }
    }

    public record ApplyResult(
            boolean applied,
            EuNodeProfileValidator.ValidationResult validation,
            EuNode node,
            String profileHash,
            String configHash,
            boolean changed,
            boolean restarted
    ) {
    }

    public record SyncResult(EuNode node, String profileHash, String configHash) {
    }
}
