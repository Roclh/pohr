package org.Roclh.service.script;

import org.Roclh.model.script.ScriptVersion;
import org.Roclh.model.script.ScriptVersionSource;
import org.Roclh.repository.script.ScriptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScriptVersionServiceTest {

    @TempDir
    Path scriptsHome;

    private ScriptVersionRepository versionRepo;
    private ScriptVersionService service;

    @BeforeEach
    void setUp() {
        versionRepo = mock(ScriptVersionRepository.class);
        service = new ScriptVersionService(versionRepo);
        ReflectionTestUtils.setField(service, "scriptsHome", scriptsHome.toString());
    }

    @Test
    @DisplayName("Снапшот из БД находится, даже если он вне текущего .history")
    void listsSnapshotFromDatabasePath() throws Exception {
        UUID scriptId = UUID.randomUUID();
        Path snapshot = scriptsHome.resolve("old-location").resolve("snapshot-1.0.bak");
        Files.createDirectories(snapshot.getParent());
        byte[] content = "old content".getBytes(StandardCharsets.UTF_8);
        Files.write(snapshot, content);

        ScriptVersion version = ScriptVersion.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .version("1.0")
                .versionMajor(1)
                .versionMinor(0)
                .hash("h")
                .size(content.length)
                .source(ScriptVersionSource.SEED)
                .snapshotPath(snapshot.toString())
                .build();
        when(versionRepo.findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId))
                .thenReturn(List.of(version));

        List<ScriptVersionService.BackupInfo> backups = service.listBackups(scriptId);

        assertThat(backups).hasSize(1);
        assertThat(backups.get(0).fileName()).isEqualTo("snapshot-1.0.bak");
        assertThat(backups.get(0).version()).isEqualTo("1.0");
        assertThat(service.readBackup(scriptId, "snapshot-1.0.bak")).isEqualTo(content);
    }

    @Test
    @DisplayName("Ручной бэкап из .history тоже попадает в список")
    void listsManualHistoryBackup() throws Exception {
        UUID scriptId = UUID.randomUUID();
        Path history = scriptsHome.resolve(".history");
        Files.createDirectories(history);
        Path manual = history.resolve(scriptId + ".1.1.20261024-170000-123.bak");
        Files.write(manual, "manual".getBytes(StandardCharsets.UTF_8));

        when(versionRepo.findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId))
                .thenReturn(List.of());

        List<ScriptVersionService.BackupInfo> backups = service.listBackups(scriptId);

        assertThat(backups).hasSize(1);
        assertThat(backups.get(0).fileName()).isEqualTo(manual.getFileName().toString());
        assertThat(service.readBackup(scriptId, manual.getFileName().toString()))
                .isEqualTo("manual".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("purgeSnapshots удаляет и снапшоты из БД, и ручные .bak")
    void purgeRemovesDatabaseAndManualBackups() throws Exception {
        UUID scriptId = UUID.randomUUID();
        Path history = scriptsHome.resolve(".history");
        Files.createDirectories(history);
        Path manual = history.resolve(scriptId + ".1.1.20261024-170000-123.bak");
        Files.write(manual, "manual".getBytes(StandardCharsets.UTF_8));

        Path dbSnapshot = scriptsHome.resolve("db-snapshot.bak");
        Files.write(dbSnapshot, "db".getBytes(StandardCharsets.UTF_8));

        ScriptVersion version = ScriptVersion.builder()
                .id(UUID.randomUUID())
                .scriptId(scriptId)
                .version("1.0")
                .versionMajor(1)
                .versionMinor(0)
                .hash("h")
                .size(2)
                .source(ScriptVersionSource.SEED)
                .snapshotPath(dbSnapshot.toString())
                .build();
        when(versionRepo.findByScriptIdOrderByVersionMajorDescVersionMinorDesc(scriptId))
                .thenReturn(List.of(version));

        service.purgeSnapshots(scriptId);

        assertThat(Files.exists(manual)).isFalse();
        assertThat(Files.exists(dbSnapshot)).isFalse();
    }
}
