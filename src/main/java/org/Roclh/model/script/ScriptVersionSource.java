package org.Roclh.model.script;

public enum ScriptVersionSource {
    /** Скопирован из classpath при первом старте. */
    SEED,
    /** Создан через /admin/scripts/new. */
    UI_CREATE,
    /** Изменён через /admin/scripts/{id}/edit. */
    UI_EDIT,
    /** Обнаружено изменение файла в volume при сканировании. */
    VOLUME_RESCAN,
    /** Откат на предыдущую версию. */
    ROLLBACK
}