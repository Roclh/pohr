package org.Roclh.repository.telegram;

import org.Roclh.model.TelegramProxyUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TelegramProxyUserRepository extends JpaRepository<TelegramProxyUser, UUID> {
    List<TelegramProxyUser> findByEnabledTrue();
    long countByEnabledTrue();
    List<TelegramProxyUser> findBySecretIsNull();
}
