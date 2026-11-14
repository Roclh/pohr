package org.Roclh.model.dto.edge;

/**
 * Сгенерированный конфиг edge и его состояние на диске.
 *
 * @param name          nginx.conf | Caddyfile
 * @param path          путь к файлу на диске
 * @param existsOnDisk  файл существует
 * @param generatedText текст, собранный из БД (может быть большим)
 * @param generatedHash sha256 сгенерированного текста
 * @param diskText      текст файла на диске (null, если файла нет)
 * @param diskHash      sha256 файла на диске (null, если файла нет)
 * @param same          сгенерированный текст совпадает с файлом
 */
public record EdgeConfigFileDto(
        String name,
        String path,
        boolean existsOnDisk,
        String generatedText,
        String generatedHash,
        String diskText,
        String diskHash,
        boolean same
) {
}
