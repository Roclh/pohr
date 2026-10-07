package org.Roclh.service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.service.edge.EdgeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EdgeConfigChangedListener {

    private final EdgeService edgeService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEdgeChanged(EdgeConfigChangedEvent event) {
        try {
            edgeService.apply();
            log.info("Edge reloaded after: {}", event.reason());
        } catch (Exception e) {
            log.error("Edge apply failed ({}): {}", event.reason(), e.getMessage(), e);
        }
    }
}