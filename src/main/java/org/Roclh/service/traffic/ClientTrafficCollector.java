package org.Roclh.service.traffic;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.ClientTrafficDaily;
import org.Roclh.model.ClientTrafficSample;
import org.Roclh.model.ClientTrafficState;
import org.Roclh.model.NodeTrafficSample;
import org.Roclh.model.Subscription;
import org.Roclh.repository.SubscriptionRepository;
import org.Roclh.repository.traffic.ClientTrafficDailyRepository;
import org.Roclh.repository.traffic.ClientTrafficSampleRepository;
import org.Roclh.repository.traffic.ClientTrafficStateRepository;
import org.Roclh.repository.traffic.NodeTrafficSampleRepository;
import org.Roclh.service.xray.XrayProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Раз в минуту снимает счётчики Xray и превращает их в дельты по клиентам.
 *
 * <p>Счётчики Xray кумулятивные и обнуляются при его перезапуске, поэтому
 * предыдущие сырые значения хранятся в {@code client_traffic_state}. Если новое
 * значение меньше предыдущего — считаем, что Xray перезапустился, и берём
 * текущее значение как дельту.</p>
 *
 * <p>Всё пишется с {@code node_id}: сейчас это локальная нода, в будущем —
 * каждая RU-нода моста. Формат таблиц уже готов к приёму данных с нескольких
 * нод без миграции.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClientTrafficCollector {

    private final XrayStatsClient statsClient;
    private final XrayProcessManager xrayProcessManager;
    private final ClientTrafficStateRepository stateRepository;
    private final ClientTrafficSampleRepository sampleRepository;
    private final ClientTrafficDailyRepository dailyRepository;
    private final NodeTrafficSampleRepository nodeRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Value("${pohr.node.id:local}")
    private String nodeId;

    @Value("${pohr.traffic.enabled:true}")
    private boolean enabled;

    @Value("${pohr.traffic.inbound-port:8443}")
    private int inboundPort;

    @Value("${pohr.traffic.retention-days:7}")
    private int retentionDays;

    @Value("${pohr.traffic.daily-retention-days:400}")
    private int dailyRetentionDays;

    @Scheduled(fixedDelayString = "${pohr.traffic.interval-ms:60000}",
            initialDelayString = "${pohr.traffic.initial-delay-ms:25000}")
    public void collect() {
        if (!enabled) {
            return;
        }
        if (!xrayProcessManager.isRunning()) {
            log.debug("Xray is not running, traffic collection skipped");
            return;
        }
        try {
            doCollect(Instant.now());
        } catch (Exception e) {
            log.warn("Client traffic collection failed: {}", e.getMessage());
        }
    }

    private void doCollect(Instant now) {
        Map<String, XrayStatsClient.Counters> counters = statsClient.queryUserTraffic();
        boolean xrayAvailable = counters != null;

        Map<String, ClientTrafficState> previous = new HashMap<>();
        stateRepository.findByNodeId(nodeId).forEach(s -> previous.put(s.getEmail(), s));

        Map<UUID, Subscription> subscriptions = new HashMap<>();
        subscriptionRepository.findAll().forEach(s -> subscriptions.put(s.getId(), s));

        long totalUp = 0;
        long totalDown = 0;
        int activeUsers = 0;

        if (counters != null) {
            for (Map.Entry<String, XrayStatsClient.Counters> entry : counters.entrySet()) {
                String email = entry.getKey();
                XrayStatsClient.Counters current = entry.getValue();
                ClientTrafficState state = previous.get(email);

                long deltaUp = delta(current.uplink(), state == null ? null : state.getRawUplink());
                long deltaDown = delta(current.downlink(), state == null ? null : state.getRawDownlink());

                if (state == null) {
                    state = ClientTrafficState.builder()
                            .nodeId(nodeId)
                            .email(email)
                            .build();
                }
                if (state.getRawUplink() != current.uplink()
                        || state.getRawDownlink() != current.downlink()
                        || state.getId() == null) {
                    state.setRawUplink(current.uplink());
                    state.setRawDownlink(current.downlink());
                    state.setUpdatedAt(now.toString());
                    stateRepository.save(state);
                }

                if (deltaUp == 0 && deltaDown == 0) {
                    continue;
                }

                UUID subscriptionId = null;
                UUID userId = null;
                try {
                    subscriptionId = UUID.fromString(email);
                    Subscription subscription = subscriptions.get(subscriptionId);
                    if (subscription != null) {
                        userId = subscription.getUserId();
                    }
                } catch (IllegalArgumentException ignored) {
                    // email не UUID — оставляем сэмпл без привязки к пользователю
                }

                sampleRepository.save(ClientTrafficSample.builder()
                        .ts(now.toString())
                        .nodeId(nodeId)
                        .userId(userId)
                        .subscriptionId(subscriptionId)
                        .email(email)
                        .uplinkBytes(deltaUp)
                        .downlinkBytes(deltaDown)
                        .build());

                if (userId != null) {
                    upsertDaily(now, userId, deltaUp, deltaDown);
                }

                totalUp += deltaUp;
                totalDown += deltaDown;
                activeUsers++;
            }
        }

        Integer activeConnections = TcpConnectionCounter.countEstablished(inboundPort);
        nodeRepository.save(NodeTrafficSample.builder()
                .ts(now.toString())
                .nodeId(nodeId)
                .activeConnections(activeConnections)
                .activeUsers(activeUsers)
                .uplinkBytes(totalUp)
                .downlinkBytes(totalDown)
                .xrayAvailable(xrayAvailable)
                .build());

        if (log.isDebugEnabled()) {
            log.debug("Traffic tick: users={}, up={}B, down={}B, conns={}, xrayAvailable={}",
                    activeUsers, totalUp, totalDown, activeConnections, xrayAvailable);
        }
        prune(now);
    }

    /**
     * Дельта кумулятивного счётчика. {@code previous == null} — первое наблюдение
     * (берём всё накопленное), {@code current < previous} — Xray перезапустился.
     */
    private static long delta(long current, Long previous) {
        if (previous == null) {
            return current;
        }
        return current >= previous ? current - previous : current;
    }

    private void upsertDaily(Instant now, UUID userId, long up, long down) {
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        ClientTrafficDaily daily = dailyRepository
                .findByDayAndNodeIdAndUserId(day, nodeId, userId)
                .orElseGet(() -> ClientTrafficDaily.builder()
                        .day(day)
                        .nodeId(nodeId)
                        .userId(userId)
                        .build());
        daily.setUplinkBytes(daily.getUplinkBytes() + up);
        daily.setDownlinkBytes(daily.getDownlinkBytes() + down);
        daily.setLastSeenAt(now.toString());
        dailyRepository.save(daily);
    }

    private void prune(Instant now) {
        String sampleCutoff = now.minusSeconds(retentionDays * 86400L).toString();
        int removed = sampleRepository.deleteOlderThan(sampleCutoff);
        if (removed > 0) {
            log.debug("Pruned {} traffic samples", removed);
        }
        String dayCutoff = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .minusDays(dailyRetentionDays).toString();
        dailyRepository.deleteBefore(dayCutoff);
        nodeRepository.deleteOlderThan(sampleCutoff);
    }
}
