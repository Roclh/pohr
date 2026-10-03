package org.Roclh.model;

public enum NodeStatus {
    /** Нода зарегистрирована, но ещё не прислала первый health. */
    PENDING,
    /** Health есть, туннель проверен, всё ок. */
    HEALTHY,
    /** Туннель проходит, но с потерями / падения. */
    DEGRADED,
    /** Туннель не работает или нода не отвечает. */
    UNREACHABLE
}