package org.Roclh.repository.telegram;

import org.Roclh.model.TelegramProxyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TelegramProxyConfigRepository extends JpaRepository<TelegramProxyConfig, String> {
}
