package org.Roclh.service.event;

/**
 * Публикуется при любом изменении набора активных подписок
 * (создание, toggle, удаление). Listener обязан перематериализовать
 * конфиг Xray и перезапустить процесс, иначе изменения не попадут
 * в clients[] до следующего ручного restart.
 */
public record SubscriptionsChangedEvent(String reason) {
}