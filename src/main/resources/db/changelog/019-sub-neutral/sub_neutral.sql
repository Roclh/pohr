--liquibase formatted sql

--changeset roclh:019-sub-neutral
-- Заголовки подписки не должны называть бренд: Profile-Title виден в клиенте
-- и в логах провайдера. Меняем сохранённые per-client настройки.
UPDATE client_configs
   SET headers = replace(replace(headers, 'profile-title: Pohr', 'profile-title: Subscription'),
                         'profile-title: pohr', 'profile-title: Subscription')
 WHERE lower(headers) LIKE '%profile-title:%pohr%';

--rollback UPDATE client_configs SET headers = replace(headers, 'profile-title: Subscription', 'profile-title: Pohr') WHERE lower(headers) LIKE '%profile-title:%subscription%';
