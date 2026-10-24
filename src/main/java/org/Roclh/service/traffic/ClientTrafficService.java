package org.Roclh.service.traffic;

import lombok.RequiredArgsConstructor;
import org.Roclh.model.ClientTrafficDaily;
import org.Roclh.model.NodeTrafficSample;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.model.dto.ClientTrafficRow;
import org.Roclh.model.dto.TrafficSummary;
import org.Roclh.repository.SubscriptionRepository;
import org.Roclh.repository.UserRepository;
import org.Roclh.repository.traffic.ClientTrafficDailyRepository;
import org.Roclh.repository.traffic.NodeTrafficSampleRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Читает суточные роллапы и агрегаты по ноде для страницы мониторинга.
 * Данные по всем нодам складываются — при мультисерверном мосте это даст
 * общий расход клиента, а фильтр по node_id можно добавить позже.
 */
@Service
@RequiredArgsConstructor
public class ClientTrafficService {

    private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(5);

    private final ClientTrafficDailyRepository dailyRepository;
    private final NodeTrafficSampleRepository nodeRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    public TrafficSummary summary() {
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        List<ClientTrafficDaily> rows = dailyRepository.findByDayGreaterThanEqual(today);

        long up = 0;
        long down = 0;
        Set<UUID> clients = new HashSet<>();
        for (ClientTrafficDaily row : rows) {
            if (!today.equals(row.getDay())) {
                continue;
            }
            up += row.getUplinkBytes();
            down += row.getDownlinkBytes();
            clients.add(row.getUserId());
        }

        NodeTrafficSample latest = nodeRepository.findTopByOrderByTsDesc().orElse(null);
        return new TrafficSummary(
                human(up),
                human(down),
                latest == null ? null : latest.getActiveConnections(),
                latest == null ? null : latest.getActiveUsers(),
                clients.size());
    }

    public List<ClientTrafficRow> overview() {
        LocalDate todayDate = LocalDate.now(ZoneOffset.UTC);
        String today = todayDate.toString();
        String weekFrom = todayDate.minusDays(6).toString();
        List<ClientTrafficDaily> rows = dailyRepository.findByDayGreaterThanEqual(weekFrom);

        Map<UUID, long[]> aggregate = new HashMap<>();
        Map<UUID, String> lastSeen = new HashMap<>();
        for (ClientTrafficDaily row : rows) {
            long[] acc = aggregate.computeIfAbsent(row.getUserId(), k -> new long[4]);
            if (today.equals(row.getDay())) {
                acc[0] += row.getUplinkBytes();
                acc[1] += row.getDownlinkBytes();
            }
            acc[2] += row.getUplinkBytes();
            acc[3] += row.getDownlinkBytes();
            lastSeen.merge(row.getUserId(), row.getLastSeenAt(),
                    (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }

        Set<UUID> usersWithSubscription = new HashSet<>();
        for (Subscription subscription : subscriptionRepository.findAll()) {
            usersWithSubscription.add(subscription.getUserId());
        }

        List<User> users = new ArrayList<>(userRepository.findAll());
        users.removeIf(user -> !usersWithSubscription.contains(user.getId())
                && !aggregate.containsKey(user.getId()));
        users.sort(Comparator
                .comparingLong((User user) -> total(aggregate.get(user.getId())))
                .reversed()
                .thenComparing(User::getUsername));

        List<ClientTrafficRow> result = new ArrayList<>(users.size());
        for (User user : users) {
            long[] acc = aggregate.getOrDefault(user.getId(), new long[4]);
            String seen = lastSeen.get(user.getId());
            result.add(new ClientTrafficRow(
                    user.getUsername(),
                    human(acc[0]),
                    human(acc[1]),
                    human(acc[2]),
                    human(acc[3]),
                    formatLastSeen(seen),
                    isActive(seen)));
        }
        return result;
    }

    /** Сэмплы за 24 часа — для графика активных соединений на мониторинге. */
    public List<NodeTrafficSample> last24h() {
        String from = Instant.now().minus(Duration.ofHours(24)).toString();
        return nodeRepository.findByTsGreaterThanEqualOrderByTsAsc(from);
    }

    private static long total(long[] acc) {
        return acc == null ? 0 : acc[2] + acc[3];
    }

    private static boolean isActive(String lastSeenAt) {
        if (lastSeenAt == null) {
            return false;
        }
        try {
            return Instant.parse(lastSeenAt).isAfter(Instant.now().minus(ACTIVE_WINDOW));
        } catch (Exception e) {
            return false;
        }
    }

    private static String formatLastSeen(String lastSeenAt) {
        if (lastSeenAt == null) {
            return "—";
        }
        try {
            String iso = Instant.parse(lastSeenAt).toString();
            return iso.substring(0, 16).replace('T', ' ') + " UTC";
        } catch (Exception e) {
            return lastSeenAt;
        }
    }

    static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
