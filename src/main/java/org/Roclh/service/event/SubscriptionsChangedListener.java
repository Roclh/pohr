package org.Roclh.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionsChangedListener {

    private final XrayConfigMaterializer materializer;
    private final XrayProcessManager processManager;

    /**
     * AFTER_COMMIT: к моменту вызова транзакция создания/удаления подписки
     * уже зафиксирована, значит materialize() увидит актуальный набор в БД.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubscriptionsChanged(SubscriptionsChangedEvent event) {
        try {
            materializer.materialize();
            if (processManager.isRunning()) {
                processManager.restart();
                log.info("Subscriptions changed ({}), Xray restarted with new clients[]", event.reason());
            } else {
                log.info("Subscriptions changed ({}), Xray stopped — config materialized, will apply on next start",
                        event.reason());
            }
        } catch (Exception e) {
            // Не роняем запрос пользователя: подписка уже создана, Xray
            // подтянет её при следующем ручном restart через UI.
            log.error("Failed to apply subscription change to Xray: {}", e.getMessage(), e);
        }
    }
}