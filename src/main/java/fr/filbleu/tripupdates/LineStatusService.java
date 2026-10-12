package fr.filbleu.tripupdates;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TimeRange;
import com.google.transit.realtime.GtfsRealtime.TranslatedString;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class LineStatusService {

    private static final Logger log = LoggerFactory.getLogger(LineStatusService.class);

    private final RestClient restClient;
    private final GtfsStaticCatalog catalog;
    private final AtomicReference<AlertSnapshot> currentSnapshot =
            new AtomicReference<>(new AlertSnapshot(false, null, null, List.of()));

    @Value("${filbleu.service-alerts-url:https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/service-alerts}")
    private String serviceAlertsUrl =
            "https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/service-alerts";

    public LineStatusService(RestClient restClient, GtfsStaticCatalog catalog) {
        this.restClient = restClient;
        this.catalog = catalog;
    }

    @PostConstruct
    void loadInitialFeed() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${filbleu.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            byte[] feed = restClient.get().uri(serviceAlertsUrl).retrieve().body(byte[].class);
            if (feed == null) {
                throw new IOException("The service alerts feed returned an empty response");
            }
            Instant fetchedAt = Instant.now();
            FeedMessage message = FeedMessage.parseFrom(feed);
            Instant feedTimestamp = message.getHeader().hasTimestamp()
                    ? Instant.ofEpochSecond(message.getHeader().getTimestamp())
                    : null;
            List<AlertEntry> alerts = message.getEntityList().stream()
                    .filter(entity -> entity.hasAlert())
                    .map(entity -> new AlertEntry(entity.getId(), entity.getAlert()))
                    .toList();
            currentSnapshot.set(new AlertSnapshot(true, feedTimestamp, fetchedAt, alerts));
        } catch (RestClientException | IOException exception) {
            log.error("Unable to refresh the Fil Bleu service alerts; retaining the last successful snapshot", exception);
        }
    }

    public LineStatusResult statusForLine(String line) {
        AlertSnapshot snapshot = currentSnapshot.get();
        Instant now = Instant.now();
        List<LineStatusResult.Alert> alerts = snapshot.alerts().stream()
                .filter(entry -> isActive(entry.alert(), now))
                .filter(entry -> appliesToLine(entry.alert(), line))
                .map(this::toResult)
                .toList();
        String status = !snapshot.available()
                ? "FEED_UNAVAILABLE"
                : alerts.isEmpty() ? "NO_PUBLISHED_ALERTS" : "ALERTS_PUBLISHED";
        return new LineStatusResult(
                line,
                status,
                snapshot.available(),
                snapshot.feedTimestamp(),
                snapshot.fetchedAt(),
                alerts);
    }

    private boolean appliesToLine(Alert alert, String line) {
        if (alert.getInformedEntityCount() == 0) {
            return true;
        }
        return alert.getInformedEntityList().stream().anyMatch(selector -> appliesToLine(selector, line));
    }

    private boolean appliesToLine(EntitySelector selector, String line) {
        boolean hasSpecificSelector = false;
        if (selector.hasRouteId()) {
            hasSpecificSelector = true;
            if (!catalog.routeBelongsToLine(selector.getRouteId(), line)) {
                return false;
            }
        }
        if (selector.hasTrip()) {
            hasSpecificSelector = true;
            String tripId = selector.getTrip().getTripId();
            String routeId = selector.getTrip().getRouteId();
            boolean applies = !routeId.isBlank()
                    ? catalog.routeBelongsToLine(routeId, line)
                    : catalog.tripBelongsToLine(tripId, line);
            if (!applies) {
                return false;
            }
        }
        if (selector.hasStopId()) {
            hasSpecificSelector = true;
            if (!catalog.stopBelongsToLine(selector.getStopId(), line)) {
                return false;
            }
        }
        if (selector.hasRouteType()) {
            hasSpecificSelector = true;
            if (!catalog.routeTypeBelongsToLine(selector.getRouteType(), line)) {
                return false;
            }
        }
        return hasSpecificSelector || !selector.hasAgencyId() || selector.getAgencyId().equals("filbleu");
    }

    private boolean isActive(Alert alert, Instant now) {
        if (alert.getActivePeriodCount() == 0) {
            return true;
        }
        return alert.getActivePeriodList().stream().anyMatch(period -> isActive(period, now));
    }

    private boolean isActive(TimeRange period, Instant now) {
        Instant start = period.hasStart() ? Instant.ofEpochSecond(period.getStart()) : null;
        Instant end = period.hasEnd() ? Instant.ofEpochSecond(period.getEnd()) : null;
        return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }

    private LineStatusResult.Alert toResult(AlertEntry entry) {
        Alert alert = entry.alert();
        List<LineStatusResult.ActivePeriod> activePeriods = alert.getActivePeriodList().stream()
                .map(period -> new LineStatusResult.ActivePeriod(
                        period.hasStart() ? Instant.ofEpochSecond(period.getStart()) : null,
                        period.hasEnd() ? Instant.ofEpochSecond(period.getEnd()) : null))
                .toList();
        List<LineStatusResult.InformedEntity> informedEntities = alert.getInformedEntityList().stream()
                .map(selector -> new LineStatusResult.InformedEntity(
                        selector.hasRouteId() ? selector.getRouteId() : null,
                        selector.hasTrip() ? selector.getTrip().getTripId() : null,
                        selector.hasStopId() ? selector.getStopId() : null))
                .toList();
        return new LineStatusResult.Alert(
                entry.id(),
                alert.hasCause() ? alert.getCause().name() : null,
                alert.hasEffect() ? alert.getEffect().name() : null,
                alert.hasHeaderText() ? text(alert.getHeaderText()) : null,
                alert.hasDescriptionText() ? text(alert.getDescriptionText()) : null,
                alert.hasUrl() ? text(alert.getUrl()) : null,
                activePeriods,
                informedEntities);
    }

    private String text(TranslatedString translatedString) {
        return translatedString.getTranslationList().stream()
                .filter(translation -> translation.hasText())
                .sorted((left, right) -> Boolean.compare(
                        !left.getLanguage().toLowerCase(Locale.ROOT).startsWith("fr"),
                        !right.getLanguage().toLowerCase(Locale.ROOT).startsWith("fr")))
                .map(translation -> translation.getText())
                .findFirst()
                .orElse(null);
    }

    private record AlertEntry(String id, Alert alert) {
    }

    private record AlertSnapshot(
            boolean available,
            Instant feedTimestamp,
            Instant fetchedAt,
            List<AlertEntry> alerts) {
    }
}
