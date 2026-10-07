package org.Roclh.service.script;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.script.Script;
import org.Roclh.model.script.ScriptAccessLevel;
import org.Roclh.model.script.ScriptVersion;
import org.Roclh.model.script.UserScriptVersion;
import org.Roclh.repository.script.UserScriptVersionRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Расчёт доступных обновлений для пользователя.
 *
 * <p>Для каждого entrypoint, релевантного платформе, считает транзитивные
 * зависимости и сравнивает серверные версии с тем, что отчитался клиент.
 * Minor — серая метка, major — яркая.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptUpdateService {

    private final ScriptCatalogService catalog;
    private final ScriptVersionService versionService;
    private final UserScriptVersionRepository userVersions;

    public enum BumpLevel { NONE, MINOR, MAJOR }

    public record ScriptUpdate(
            String scriptName,
            String installedVersion,
            String latestVersion,
            BumpLevel bumpLevel
    ) {}

    public record EntrypointUpdates(
            String entrypointName,
            List<ScriptUpdate> updates
    ) {
        public boolean hasUpdates() { return !updates.isEmpty(); }
        public boolean hasMajor() {
            return updates.stream().anyMatch(u -> u.bumpLevel() == BumpLevel.MAJOR);
        }
    }

    /**
     * Для всех entrypoint'ов, релевантных платформе (или "any"),
     * возвращает список устаревших зависимостей.
     */
    public List<EntrypointUpdates> checkForPlatform(UUID userId, String platform, boolean isAdmin) {
        Map<UUID, UserScriptVersion> installed = new HashMap<>();
        for (UserScriptVersion v : userVersions.findByUserId(userId)) {
            installed.put(v.getScriptId(), v);
        }

        List<EntrypointUpdates> result = new ArrayList<>();
        for (Script root : catalog.listEntrypointsForPlatform(platform)) {
            // Не-админ видит только PUBLIC entrypoint'ы
            if (!isAdmin && root.getAccessLevel() != ScriptAccessLevel.PUBLIC) continue;
            List<ScriptUpdate> updates = checkEntrypoint(root, installed, isAdmin);
            result.add(new EntrypointUpdates(root.getName(), updates));
        }
        return result;
    }

    private List<ScriptUpdate> checkEntrypoint(Script root,
                                               Map<UUID, UserScriptVersion> installed,
                                               boolean isAdmin) {
        List<ScriptUpdate> updates = new ArrayList<>();
        compareVersion(root, installed, updates);

        Set<String> deps = catalog.getTransitiveDependencies(root.getName());
        for (String depName : deps) {
            Script dep = catalog.findByName(depName).orElse(null);
            if (dep == null || !dep.isEnabled()) continue;
            // Не-админ не видит версии ADMIN-скриптов
            if (!isAdmin && dep.getAccessLevel() != ScriptAccessLevel.PUBLIC) continue;
            compareVersion(dep, installed, updates);
        }
        return updates;
    }

    private void compareVersion(Script script,
                                Map<UUID, UserScriptVersion> installed,
                                List<ScriptUpdate> out) {
        Optional<ScriptVersion> activeOpt = versionService.getActive(script.getId());
        if (activeOpt.isEmpty()) return;
        ScriptVersion active = activeOpt.get();

        UserScriptVersion userRow = installed.get(script.getId());
        if (userRow == null) {
            return;
        }

        ScriptVersionNumber current = ScriptVersionNumber.parse(userRow.getVersion());
        ScriptVersionNumber latest = ScriptVersionNumber.parse(active.getVersion());
        if (current.compareTo(latest) >= 0) return; // не устарело

        BumpLevel level = latest.isMajorChangeFrom(current) ? BumpLevel.MAJOR : BumpLevel.MINOR;
        out.add(new ScriptUpdate(
                script.getName(),
                current.toString(),
                latest.toString(),
                level));
    }
}