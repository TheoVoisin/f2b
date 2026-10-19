package fr.filbleu.tripupdates.service;

import fr.filbleu.tripupdates.api.dto.LineStatusResult;
import fr.filbleu.tripupdates.api.dto.DownstreamStopArrivalResult;
import fr.filbleu.tripupdates.api.dto.TripUpdateResult;
import fr.filbleu.tripupdates.api.dto.UpcomingTripResult;
import fr.filbleu.tripupdates.infrastructure.gtfs.GtfsStaticCatalog;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class TripUpdateService {

    private static final Logger log = LoggerFactory.getLogger(TripUpdateService.class);

    private final RestClient restClient;
    private final GtfsStaticCatalog catalog;
    private final LineStatusService lineStatusService;
    private final AtomicReference<List<TripUpdateResult>> currentUpdates = new AtomicReference<>(List.of());

    @Value("${filbleu.trip-updates-url:https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/trip-updates}")
    private String tripUpdatesUrl = "https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/trip-updates";

    public TripUpdateService(
            RestClient restClient,
            GtfsStaticCatalog catalog,
            LineStatusService lineStatusService) {
        this.restClient = restClient;
        this.catalog = catalog;
        this.lineStatusService = lineStatusService;
    }

    @PostConstruct
    void loadInitialFeed() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${filbleu.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            byte[] feed = restClient.get().uri(tripUpdatesUrl).retrieve().body(byte[].class);
            if (feed == null) {
                throw new IOException("The trip updates feed returned an empty response");
            }
            currentUpdates.set(parseFeed(feed));
        } catch (RestClientException | IOException exception) {
            log.error("Unable to refresh the Fil Bleu trip updates; retaining the last successful snapshot", exception);
        }
    }

    public List<UpcomingTripResult> nextTripsAtStop(String line, String stopId, String direction) {
        return nextTripsAtStop(line, stopId, direction, List.of());
    }

    public List<UpcomingTripResult> nextTripsAtStop(
            String line, String stopId, String direction, List<String> downstreamStopIds) {
        List<TripUpdateResult> realtimeUpdates = currentUpdates.get();
        LineStatusResult lineStatus = lineStatusService.statusForLine(line);
        Set<String> matchedRealtimeTrips = new HashSet<>();
        return catalog.nextDepartures(line, direction, stopId, Instant.now(), 2).stream()
                .map(scheduled -> {
                    TripUpdateResult realtime = findRealtimeUpdate(scheduled, realtimeUpdates, matchedRealtimeTrips);
                    if (realtime != null) {
                        matchedRealtimeTrips.add(realtime.tripId());
                    }
                    GtfsStaticCatalog.Stop stop = catalog.stop(stopId);
                    List<DownstreamStopArrivalResult> downstreamArrivals =
                            downstreamArrivals(scheduled, realtime, downstreamStopIds, realtimeUpdates);
                    return new UpcomingTripResult(
                            scheduled.tripId(),
                            realtime == null ? null : realtime.tripId(),
                            scheduled.line(),
                            scheduled.direction(),
                            scheduled.stopId(),
                            stop == null ? stopId : stop.name(),
                            scheduled.stopSequence(),
                            scheduled.scheduledArrivalTime(),
                            scheduled.scheduledDepartureTime(),
                            realtime == null ? null : realtime.arrivalTime(),
                            realtime == null ? null : realtime.arrivalDelaySeconds(),
                            realtime == null ? null : realtime.departureTime(),
                            realtime == null ? null : realtime.departureDelaySeconds(),
                            realtime == null ? null : realtime.feedTimestamp(),
                            lineStatus,
                            downstreamArrivals);
                })
                .toList();
    }

    private List<DownstreamStopArrivalResult> downstreamArrivals(
            GtfsStaticCatalog.ScheduledTrip scheduled,
            TripUpdateResult mainStopRealtime,
            List<String> downstreamStopIds,
            List<TripUpdateResult> realtimeUpdates) {
        if (downstreamStopIds == null || downstreamStopIds.isEmpty()) {
            return List.of();
        }
        return downstreamStopIds.stream()
                .map(stopId -> {
                    GtfsStaticCatalog.ScheduledStopArrival scheduledArrival = catalog.scheduledArrivalAtStop(
                            scheduled.tripId(),
                            scheduled.serviceDate(),
                            stopId,
                            scheduled.stopSequence());
                    if (scheduledArrival == null) {
                        return null;
                    }
                    TripUpdateResult realtimeArrival = mainStopRealtime == null
                            ? null
                            : realtimeUpdates.stream()
                                    .filter(update -> update.tripId().equals(mainStopRealtime.tripId())
                                            && update.stopId().equalsIgnoreCase(stopId)
                                            && update.stopSequence() != null
                                            && update.stopSequence() == scheduledArrival.stopSequence())
                                    .findFirst()
                                    .orElse(null);
                    GtfsStaticCatalog.Stop stop = catalog.stop(stopId);
                    return new DownstreamStopArrivalResult(
                            stopId,
                            stop == null ? stopId : stop.name(),
                            scheduledArrival.arrivalTime(),
                            realtimeArrival == null ? null : realtimeArrival.arrivalTime(),
                            realtimeArrival == null ? null : realtimeArrival.arrivalDelaySeconds());
                })
                .filter(arrival -> arrival != null)
                .toList();
    }

    private TripUpdateResult findRealtimeUpdate(
            GtfsStaticCatalog.ScheduledTrip scheduled,
            List<TripUpdateResult> realtimeUpdates,
            Set<String> matchedRealtimeTrips) {
        TripUpdateResult bestMatch = null;
        long bestDifference = Long.MAX_VALUE;
        for (TripUpdateResult update : realtimeUpdates) {
            if (matchedRealtimeTrips.contains(update.tripId())
                    || !scheduled.line().equalsIgnoreCase(update.line())
                    || !scheduled.stopId().equalsIgnoreCase(update.stopId())
                    || update.stopSequence() == null
                    || update.stopSequence() != scheduled.stopSequence()
                    || !containsIgnoreCase(update.direction(), scheduled.direction())) {
                continue;
            }

            if (scheduled.tripId().equals(update.tripId())) {
                return update;
            }
            if (sameTripNumber(scheduled.tripId(), update.tripId())) {
                return update;
            }
            Instant observedTime = update.departureTime() != null ? update.departureTime() : update.arrivalTime();
            if (observedTime == null) {
                continue;
            }
            long difference = Math.abs(Duration.between(scheduled.scheduledDepartureTime(), observedTime).toSeconds());
            if (difference < bestDifference && difference <= Duration.ofMinutes(2).toSeconds()) {
                bestDifference = difference;
                bestMatch = update;
            }
        }
        return bestMatch;
    }

    private boolean sameTripNumber(String scheduledTripId, String realtimeTripId) {
        int scheduledSeparator = scheduledTripId.lastIndexOf('#');
        int realtimeSeparator = realtimeTripId.lastIndexOf('#');
        return scheduledSeparator >= 0
                && realtimeSeparator >= 0
                && scheduledTripId.substring(scheduledSeparator + 1)
                        .equals(realtimeTripId.substring(realtimeSeparator + 1));
    }

    private List<TripUpdateResult> parseFeed(byte[] feed) throws IOException {
        FeedMessage message = FeedMessage.parseFrom(feed);
        Instant feedTimestamp = message.getHeader().hasTimestamp()
                ? Instant.ofEpochSecond(message.getHeader().getTimestamp())
                : null;
        List<TripUpdateResult> updates = new ArrayList<>();

        for (FeedEntity entity : message.getEntityList()) {
            if (!entity.hasTripUpdate()) {
                continue;
            }

            TripDescriptor descriptor = entity.getTripUpdate().getTrip();
            String tripId = descriptor.getTripId();
            String routeId = descriptor.getRouteId();
            GtfsStaticCatalog.Trip staticTrip = catalog.trip(tripId);
            GtfsStaticCatalog.TripDirection inferredTrip = staticTrip == null
                    ? inferTripDirection(entity.getTripUpdate().getStopTimeUpdateList(), routeId)
                    : null;
            if (routeId.isBlank() && staticTrip != null) {
                routeId = staticTrip.routeId();
            } else if (routeId.isBlank() && inferredTrip != null) {
                routeId = inferredTrip.routeId();
            }
            GtfsStaticCatalog.Route route = catalog.route(routeId);
            String line = route == null ? routeId : firstNonBlank(route.shortName(), route.longName(), routeId);
            String direction = staticTrip != null
                    ? staticTrip.headsign()
                    : inferredTrip == null ? null : inferredTrip.headsign();

            for (StopTimeUpdate stopUpdate : entity.getTripUpdate().getStopTimeUpdateList()) {
                String stopId = stopUpdate.getStopId();
                GtfsStaticCatalog.Stop staticStop = catalog.stop(stopId);
                updates.add(new TripUpdateResult(
                        tripId,
                        routeId,
                        line,
                        direction,
                        stopId,
                        staticStop == null ? stopId : staticStop.name(),
                        stopUpdate.hasStopSequence() ? stopUpdate.getStopSequence() : null,
                        eventTime(stopUpdate.hasArrival(), stopUpdate.getArrival()),
                        eventDelay(stopUpdate.hasArrival(), stopUpdate.getArrival()),
                        eventTime(stopUpdate.hasDeparture(), stopUpdate.getDeparture()),
                        eventDelay(stopUpdate.hasDeparture(), stopUpdate.getDeparture()),
                        feedTimestamp));
            }
        }
        return List.copyOf(updates);
    }

    private GtfsStaticCatalog.TripDirection inferTripDirection(
            List<StopTimeUpdate> stopUpdates,
            String routeId) {
        Map<GtfsStaticCatalog.TripDirection, Set<Integer>> matchedSequences = new HashMap<>();
        for (StopTimeUpdate stopUpdate : stopUpdates) {
            if (!stopUpdate.hasStopSequence()) {
                continue;
            }
            for (GtfsStaticCatalog.TripDirection candidate :
                    catalog.tripsAt(stopUpdate.getStopId(), stopUpdate.getStopSequence())) {
                if (routeId.isBlank() || routeId.equals(candidate.routeId())) {
                    matchedSequences.computeIfAbsent(candidate, ignored -> new HashSet<>())
                            .add(stopUpdate.getStopSequence());
                }
            }
        }

        int highestMatchCount = 0;
        GtfsStaticCatalog.TripDirection bestMatch = null;
        boolean tied = false;
        for (Map.Entry<GtfsStaticCatalog.TripDirection, Set<Integer>> entry : matchedSequences.entrySet()) {
            int matchCount = entry.getValue().size();
            if (matchCount > highestMatchCount) {
                highestMatchCount = matchCount;
                bestMatch = entry.getKey();
                tied = false;
            } else if (matchCount == highestMatchCount) {
                tied = true;
            }
        }
        return tied ? null : bestMatch;
    }

    private Instant eventTime(boolean hasEvent, StopTimeEvent event) {
        return hasEvent && event.hasTime() ? Instant.ofEpochSecond(event.getTime()) : null;
    }

    private Integer eventDelay(boolean hasEvent, StopTimeEvent event) {
        return hasEvent && event.hasDelay() ? event.getDelay() : null;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (!first.isBlank()) {
            return first;
        }
        if (!second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private boolean containsIgnoreCase(String value, String query) {
        return isBlank(query)
                || (value != null && value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT)));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
