package org.Roclh.service.node;

import org.Roclh.model.EuNode;
import org.Roclh.model.EuNodeProfileHistory;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.repository.EuNodeProfileHistoryRepository;
import org.Roclh.repository.EuNodeRepository;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayProcessManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EuNodeProfileServiceTest {

    private static final UUID NODE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EuNodeRepository nodeRepository;
    private EuNodeProfileHistoryRepository historyRepository;
    private EuNodeProfileValidator validator;
    private EuConfigService euConfigService;
    private XrayConfigMaterializer materializer;
    private XrayProcessManager processManager;
    private EuNodeProfileService service;
    private EuNode node;
    private final List<EuNodeProfileHistory> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        nodeRepository = mock(EuNodeRepository.class);
        historyRepository = mock(EuNodeProfileHistoryRepository.class);
        validator = mock(EuNodeProfileValidator.class);
        euConfigService = mock(EuConfigService.class);
        materializer = mock(XrayConfigMaterializer.class);
        processManager = mock(XrayProcessManager.class);
        service = new EuNodeProfileService(nodeRepository, historyRepository, validator,
                euConfigService, materializer, processManager);

        node = EuNode.builder()
                .id(NODE_ID)
                .name("eu-poland-1")
                .host("78.17.145.160")
                .port(8443)
                .realityPublicKey("public-key")
                .realityShortId("aabbccdd")
                .tunnelUuid(UUID.randomUUID())
                .nodeSecret("secret")
                .build();

        when(nodeRepository.findById(NODE_ID)).thenReturn(Optional.of(node));
        when(nodeRepository.save(any(EuNode.class))).thenAnswer(inv -> inv.getArgument(0));
        when(historyRepository.findByNodeIdOrderByCreatedAtDesc(NODE_ID)).thenAnswer(inv -> history);
        when(historyRepository.save(any(EuNodeProfileHistory.class))).thenAnswer(inv -> {
            EuNodeProfileHistory saved = inv.getArgument(0);
            history.add(0, saved);
            return saved;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            history.remove(inv.getArgument(0, EuNodeProfileHistory.class));
            return null;
        }).when(historyRepository).delete(any(EuNodeProfileHistory.class));
        org.mockito.Mockito.doAnswer(inv -> {
            Iterable<EuNodeProfileHistory> removed = inv.getArgument(0);
            removed.forEach(history::remove);
            return null;
        }).when(historyRepository).deleteAll(org.mockito.ArgumentMatchers.anyIterable());
        when(euConfigService.buildEuConfig(any(EuNode.class))).thenReturn("{}");
        when(euConfigService.computeHash(anyString())).thenReturn("config-hash");
        when(processManager.isRunning()).thenReturn(true);
        when(processManager.restart()).thenReturn(true);
    }

    @Test
    @DisplayName("apply сохраняет профиль, историю и перезапускает Xray при изменении")
    void applySavesAndRestarts() {
        when(validator.validate(any(), eq(node))).thenReturn(ok());

        var result = service.apply(NODE_ID,
                new EuNodeProfileForm("eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox"),
                "admin");

        assertThat(result.applied()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(result.restarted()).isTrue();
        assertThat(node.getSni()).isEqualTo("eu.pohr-roclh.xyz");
        assertThat(node.getTarget()).isEqualTo("eu.pohr-roclh.xyz:443");
        assertThat(node.getFingerprint()).isEqualTo("firefox");
        assertThat(node.getProfileUpdatedAt()).isNotNull();
        assertThat(history).hasSize(1);
        verify(materializer).materialize();
        verify(processManager).restart();
    }

    @Test
    @DisplayName("apply при ошибке валидации ничего не сохраняет и не перезапускает")
    void applyWithValidationErrorsDoesNothing() {
        when(validator.validate(any(), eq(node))).thenReturn(
                new EuNodeProfileValidator.ValidationResult(false, List.of("bad"), List.of(), null));

        var result = service.apply(NODE_ID,
                new EuNodeProfileForm("bad", "bad", "random"), "admin");

        assertThat(result.applied()).isFalse();
        assertThat(node.getSni()).isNull();
        verify(nodeRepository, never()).save(any());
        verify(materializer, never()).materialize();
        verify(processManager, never()).restart();
    }

    @Test
    @DisplayName("повторное применение того же профиля не пишет историю и не рвёт сессии")
    void applySameProfileDoesNotRestart() {
        node.setSni("eu.pohr-roclh.xyz");
        node.setTarget("eu.pohr-roclh.xyz:443");
        node.setFingerprint("firefox");
        when(validator.validate(any(), eq(node))).thenReturn(ok());

        var result = service.apply(NODE_ID,
                new EuNodeProfileForm("eu.pohr-roclh.xyz", "eu.pohr-roclh.xyz:443", "firefox"),
                "admin");

        assertThat(result.applied()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.restarted()).isFalse();
        assertThat(history).isEmpty();
        verify(processManager, never()).restart();
    }

    @Test
    @DisplayName("rollback возвращает предыдущий профиль из истории")
    void rollbackRestoresPreviousProfile() {
        node.setSni("new.pohr-roclh.xyz");
        node.setTarget("new.pohr-roclh.xyz:443");
        history.add(EuNodeProfileHistory.builder()
                .id(UUID.randomUUID())
                .nodeId(NODE_ID)
                .sni("old.pohr-roclh.xyz")
                .target("old.pohr-roclh.xyz:443")
                .fingerprint("safari")
                .createdAt(Instant.now())
                .createdBy("admin")
                .build());

        var result = service.rollback(NODE_ID, "admin");

        assertThat(result.applied()).isTrue();
        assertThat(node.getSni()).isEqualTo("old.pohr-roclh.xyz");
        assertThat(node.getTarget()).isEqualTo("old.pohr-roclh.xyz:443");
        assertThat(node.getFingerprint()).isEqualTo("safari");
        assertThat(history).isEmpty();
        verify(materializer).materialize();
        verify(processManager).restart();
    }

    @Test
    @DisplayName("rollback без истории — ошибка")
    void rollbackWithoutHistoryFails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rollback(NODE_ID, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("откатывать некуда");
    }

    @Test
    @DisplayName("sync сбрасывает сохранённый hash и материализует конфиг без рестарта")
    void syncClearsHashWithoutRestart() {
        node.setConfigHash("stale");

        var result = service.sync(NODE_ID);

        assertThat(node.getConfigHash()).isNull();
        assertThat(result.configHash()).isEqualTo("config-hash");
        verify(materializer).materialize();
        verify(processManager, never()).restart();
    }

    @Test
    @DisplayName("profileHash зависит от значений профиля")
    void profileHashChangesWithProfile() {
        String empty = service.profileHash(node);
        node.setSni("eu.pohr-roclh.xyz");
        String withSni = service.profileHash(node);

        assertThat(empty).hasSize(64).isNotEqualTo(withSni);
    }

    private static EuNodeProfileValidator.ValidationResult ok() {
        return new EuNodeProfileValidator.ValidationResult(true, List.of(), List.of(), null);
    }
}
