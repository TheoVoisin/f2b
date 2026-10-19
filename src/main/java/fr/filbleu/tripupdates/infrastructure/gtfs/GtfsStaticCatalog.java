package fr.filbleu.tripupdates.infrastructure.gtfs;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class GtfsStaticCatalog {

    private final RestClient restClient;

    @Value("${filbleu.static-gtfs-url:https://data.tours-metropole.fr/api/v2/catalog/datasets/horaires-temps-reel-gtfsrt-reseau-filbleu-tmvl/alternative_exports/filbleu_gtfszip}")
    private String staticGtfsUrl =
            "https://data.tours-metropole.fr/api/v2/catalog/datasets/horaires-temps-reel-gtfsrt-reseau-filbleu-tmvl/alternative_exports/filbleu_gtfszip";

    private Map<String, Route> routes = Map.of();
    private Map<String, Stop> stops = Map.of();
    private Map<String, Trip> trips = Map.of();
    private Map<StopSequence, Set<TripDirection>> tripsByStopSequence = Map.of();
    private Map<DirectionKey, Map<Integer, Set<String>>> stopsByLineAndDirection = Map.of();
    private Map<StopDirectionKey, List<ScheduledDeparture>> departuresByStopDirection = Map.of();
    private Map<String, ServiceCalendar> serviceCalendars = Map.of();
    private Map<LocalDate, Map<String, Boolean>> serviceExceptions = Map.of();
    private ZoneId agencyTimeZone = ZoneId.of("Europe/Paris");

    public GtfsStaticCatalog(RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    void load() {
        try {
            byte[] archive = restClient.get().uri(staticGtfsUrl).retrieve().body(byte[].class);
            if (archive == null) {
                throw new IOException("The static GTFS download returned an empty response");
            }
            Catalog catalog = parseArchive(archive);
            routes = catalog.routes();
            stops = catalog.stops();
            trips = catalog.trips();
            tripsByStopSequence = catalog.tripsByStopSequence();
            stopsByLineAndDirection = catalog.stopsByLineAndDirection();
            departuresByStopDirection = catalog.departuresByStopDirection();
            serviceCalendars = catalog.serviceCalendars();
            serviceExceptions = catalog.serviceExceptions();
            agencyTimeZone = catalog.agencyTimeZone();
        } catch (RestClientException | IOException exception) {
            throw new IllegalStateException("Unable to load the Fil Bleu static GTFS schedule", exception);
        }
    }

    public Route route(String routeId) {
        return routes.get(routeId);
    }

    public Stop stop(String stopId) {
        return stops.get(stopId);
    }

    public Trip trip(String tripId) {
        return trips.get(tripId);
    }

    public Set<TripDirection> tripsAt(String stopId, int stopSequence) {
        return tripsByStopSequence.getOrDefault(new StopSequence(stopId, stopSequence), Set.of());
    }

    public List<String> lines() {
        return stopsByLineAndDirection.keySet().stream()
                .map(DirectionKey::line)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<DirectionStops> stopsForLine(String line) {
        String requestedLine = line.trim();
        List<DirectionStops> directions = new ArrayList<>();
        stopsByLineAndDirection.entrySet().stream()
                .filter(entry -> entry.getKey().line().equalsIgnoreCase(requestedLine))
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(DirectionKey::headsign)))
                .forEach(entry -> {
                    List<StopInfo> directionStops = new ArrayList<>();
                    new TreeMap<>(entry.getValue()).forEach((sequence, stopIds) ->
                            new TreeSet<>(stopIds).forEach(stopId -> {
                                Stop stop = stops.get(stopId);
                                directionStops.add(new StopInfo(
                                        sequence,
                                        stopId,
                                        stop == null ? stopId : stop.name()));
                            }));
                    directions.add(new DirectionStops(entry.getKey().headsign(), List.copyOf(directionStops)));
                });
        return List.copyOf(directions);
    }

    public DirectionStops stopsForLineAndDirection(String line, String direction) {
        return stopsForLine(line).stream()
                .filter(candidate -> candidate.direction().equalsIgnoreCase(direction.trim()))
                .findFirst()
                .orElse(null);
    }

    public boolean routeBelongsToLine(String routeId, String line) {
        Route route = routes.get(routeId);
        return route != null && route.shortName().equalsIgnoreCase(line.trim());
    }

    public boolean tripBelongsToLine(String tripId, String line) {
        Trip trip = trips.get(tripId);
        return trip != null && routeBelongsToLine(trip.routeId(), line);
    }

    public boolean stopBelongsToLine(String stopId, String line) {
        String requestedLine = line.trim();
        return stopsByLineAndDirection.entrySet().stream()
                .anyMatch(entry -> entry.getKey().line().equalsIgnoreCase(requestedLine)
                        && entry.getValue().values().stream()
                                .anyMatch(stopIds -> stopIds.stream().anyMatch(id -> id.equalsIgnoreCase(stopId))));
    }

    public boolean routeTypeBelongsToLine(int routeType, String line) {
        String requestedLine = line.trim();
        return routes.values().stream()
                .anyMatch(route -> route.shortName().equalsIgnoreCase(requestedLine)
                        && route.routeType() == routeType);
    }

    public List<ScheduledTrip> nextDepartures(
            String line, String direction, String stopId, Instant now, int limit) {
        String requestedLine = line.trim();
        String requestedDirection = direction.trim();
        ZoneId zone = agencyTimeZone;
        LocalDate today = now.atZone(zone).toLocalDate();
        List<ScheduledTrip> upcoming = new ArrayList<>();
        Set<String> seenTrips = new HashSet<>();

        for (int daysAhead = 0; daysAhead <= 14 && upcoming.size() < limit; daysAhead++) {
            LocalDate serviceDate = today.plusDays(daysAhead);
            for (Map.Entry<StopDirectionKey, List<ScheduledDeparture>> entry : departuresByStopDirection.entrySet()) {
                StopDirectionKey key = entry.getKey();
                if (!key.stopId().equalsIgnoreCase(stopId.trim())
                        || !key.line().equalsIgnoreCase(requestedLine)
                        || !key.direction().equalsIgnoreCase(requestedDirection)) {
                    continue;
                }
                for (ScheduledDeparture departure : entry.getValue()) {
                    if (!isServiceActive(departure.serviceId(), serviceDate)) {
                        continue;
                    }
                    LocalDateTime scheduledDeparture = serviceDate.atStartOfDay()
                            .plusSeconds(departure.departureSeconds());
                    if (!scheduledDeparture.atZone(zone).toInstant().isAfter(now)) {
                        continue;
                    }
                    String identity = departure.tripId() + "|" + serviceDate;
                    if (!seenTrips.add(identity)) {
                        continue;
                    }
                    Instant scheduledArrivalTime = departure.arrivalSeconds() == null
                            ? null
                            : serviceDate.atStartOfDay()
                                    .plusSeconds(departure.arrivalSeconds())
                                    .atZone(zone)
                                    .toInstant();
                    Instant scheduledDepartureTime = scheduledDeparture.atZone(zone).toInstant();
                    upcoming.add(new ScheduledTrip(
                            departure.tripId(),
                            departure.routeId(),
                            requestedLine,
                            requestedDirection,
                            stopId,
                            departure.stopSequence(),
                            scheduledArrivalTime,
                            scheduledDepartureTime));
                }
            }
            upcoming.sort(Comparator.comparing(ScheduledTrip::scheduledDepartureTime));
        }

        return upcoming.stream().limit(limit).toList();
    }

    private boolean isServiceActive(String serviceId, LocalDate date) {
        Boolean exception = serviceExceptions.getOrDefault(date, Map.of()).get(serviceId);
        if (exception != null) {
            return exception;
        }
        ServiceCalendar calendar = serviceCalendars.get(serviceId);
        if (calendar == null || date.isBefore(calendar.startDate()) || date.isAfter(calendar.endDate())) {
            return false;
        }
        return switch (date.getDayOfWeek()) {
            case MONDAY -> calendar.monday();
            case TUESDAY -> calendar.tuesday();
            case WEDNESDAY -> calendar.wednesday();
            case THURSDAY -> calendar.thursday();
            case FRIDAY -> calendar.friday();
            case SATURDAY -> calendar.saturday();
            case SUNDAY -> calendar.sunday();
        };
    }

    private Catalog parseArchive(byte[] archive) throws IOException {
        Map<String, Route> parsedRoutes = new HashMap<>();
        Map<String, Stop> parsedStops = new HashMap<>();
        Map<String, Trip> parsedTrips = new HashMap<>();
        boolean routesFound = false;
        boolean stopsFound = false;
        boolean tripsFound = false;
        byte[] stopTimesContents = null;
        byte[] calendarContents = null;
        byte[] calendarDatesContents = null;
        ZoneId parsedAgencyTimeZone = ZoneId.of("Europe/Paris");

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("routes.txt") || name.endsWith("/routes.txt")) {
                    routesFound = true;
                    readEntry(zip, (record) -> parsedRoutes.put(
                            record.get("route_id"),
                            new Route(
                                    record.get("route_short_name"),
                                    record.get("route_long_name"),
                                    Integer.parseInt(record.get("route_type")))));
                } else if (name.equals("stops.txt") || name.endsWith("/stops.txt")) {
                    stopsFound = true;
                    readEntry(zip, (record) -> parsedStops.put(
                            record.get("stop_id"),
                            new Stop(record.get("stop_name"))));
                } else if (name.equals("trips.txt") || name.endsWith("/trips.txt")) {
                    tripsFound = true;
                    readEntry(zip, (record) -> parsedTrips.put(
                            record.get("trip_id"),
                            new Trip(record.get("route_id"), record.get("service_id"), record.get("trip_headsign"))));
                } else if (name.equals("stop_times.txt") || name.endsWith("/stop_times.txt")) {
                    stopTimesContents = zip.readAllBytes();
                } else if (name.equals("calendar.txt") || name.endsWith("/calendar.txt")) {
                    calendarContents = zip.readAllBytes();
                } else if (name.equals("calendar_dates.txt") || name.endsWith("/calendar_dates.txt")) {
                    calendarDatesContents = zip.readAllBytes();
                } else if (name.equals("agency.txt") || name.endsWith("/agency.txt")) {
                    parsedAgencyTimeZone = readAgencyTimeZone(zip);
                }
                zip.closeEntry();
            }
        }

        if (!routesFound || !stopsFound || !tripsFound || stopTimesContents == null
                || calendarContents == null || calendarDatesContents == null) {
            throw new IOException(
                    "The static GTFS archive must contain routes.txt, stops.txt, trips.txt, stop_times.txt, "
                            + "calendar.txt, and calendar_dates.txt");
        }

        Map<StopSequence, Set<TripDirection>> parsedTripsByStopSequence = new HashMap<>();
        Map<DirectionKey, Map<Integer, Set<String>>> parsedStopsByLineAndDirection = new HashMap<>();
        Map<StopDirectionKey, List<ScheduledDeparture>> parsedDeparturesByStopDirection = new HashMap<>();
        readBytes(stopTimesContents, (record) -> {
            Trip trip = parsedTrips.get(record.get("trip_id"));
            String stopId = record.get("stop_id");
            String stopSequence = record.get("stop_sequence");
            if (trip != null && !stopId.isBlank() && !stopSequence.isBlank()) {
                int sequence = Integer.parseInt(stopSequence);
                StopSequence key = new StopSequence(stopId, sequence);
                parsedTripsByStopSequence
                        .computeIfAbsent(key, ignored -> new HashSet<>())
                        .add(new TripDirection(trip.routeId(), trip.headsign()));

                Route route = parsedRoutes.get(trip.routeId());
                if (route != null && !route.shortName().isBlank()) {
                    DirectionKey direction = new DirectionKey(route.shortName(), trip.headsign());
                    parsedStopsByLineAndDirection
                            .computeIfAbsent(direction, ignored -> new HashMap<>())
                            .computeIfAbsent(sequence, ignored -> new HashSet<>())
                            .add(stopId);
                    String departureTime = record.get("departure_time");
                    if (!departureTime.isBlank()) {
                        StopDirectionKey stopDirection = new StopDirectionKey(
                                route.shortName(),
                                trip.headsign(),
                                stopId);
                        parsedDeparturesByStopDirection
                                .computeIfAbsent(stopDirection, ignored -> new ArrayList<>())
                                .add(new ScheduledDeparture(
                                        record.get("trip_id"),
                                        trip.routeId(),
                                        trip.serviceId(),
                                        sequence,
                                        parseGtfsTime(departureTime),
                                        parseOptionalGtfsTime(record.get("arrival_time"))));
                    }
                }
            }
        });

        Map<StopSequence, Set<TripDirection>> immutableTripsByStopSequence = new HashMap<>();
        parsedTripsByStopSequence.forEach(
                (key, value) -> immutableTripsByStopSequence.put(key, Set.copyOf(value)));
        Map<DirectionKey, Map<Integer, Set<String>>> immutableStopsByLineAndDirection = new HashMap<>();
        parsedStopsByLineAndDirection.forEach((direction, stopsBySequence) -> {
            Map<Integer, Set<String>> immutableStopsBySequence = new HashMap<>();
            stopsBySequence.forEach((sequence, stopIds) ->
                    immutableStopsBySequence.put(sequence, Set.copyOf(stopIds)));
            immutableStopsByLineAndDirection.put(direction, Map.copyOf(immutableStopsBySequence));
        });
        parsedDeparturesByStopDirection.values().forEach(
                departures -> departures.sort(Comparator.comparingLong(ScheduledDeparture::departureSeconds)));
        Map<StopDirectionKey, List<ScheduledDeparture>> immutableDeparturesByStopDirection = new HashMap<>();
        parsedDeparturesByStopDirection.forEach(
                (key, departures) -> immutableDeparturesByStopDirection.put(key, List.copyOf(departures)));
        Map<String, ServiceCalendar> parsedServiceCalendars = new HashMap<>();
        readBytes(calendarContents, (record) -> parsedServiceCalendars.put(
                record.get("service_id"),
                new ServiceCalendar(
                        parseGtfsDate(record.get("start_date")),
                        parseGtfsDate(record.get("end_date")),
                        record.get("monday").equals("1"),
                        record.get("tuesday").equals("1"),
                        record.get("wednesday").equals("1"),
                        record.get("thursday").equals("1"),
                        record.get("friday").equals("1"),
                        record.get("saturday").equals("1"),
                        record.get("sunday").equals("1"))));
        Map<LocalDate, Map<String, Boolean>> parsedServiceExceptions = new HashMap<>();
        readBytes(calendarDatesContents, (record) -> {
            String exceptionType = record.get("exception_type");
            if (exceptionType.equals("1") || exceptionType.equals("2")) {
                LocalDate date = parseGtfsDate(record.get("date"));
                parsedServiceExceptions.computeIfAbsent(date, ignored -> new HashMap<>())
                        .put(record.get("service_id"), exceptionType.equals("1"));
            }
        });
        Map<LocalDate, Map<String, Boolean>> immutableServiceExceptions = new HashMap<>();
        parsedServiceExceptions.forEach(
                (date, exceptions) -> immutableServiceExceptions.put(date, Map.copyOf(exceptions)));
        return new Catalog(
                Map.copyOf(parsedRoutes),
                Map.copyOf(parsedStops),
                Map.copyOf(parsedTrips),
                Map.copyOf(immutableTripsByStopSequence),
                Map.copyOf(immutableStopsByLineAndDirection),
                Map.copyOf(immutableDeparturesByStopDirection),
                Map.copyOf(parsedServiceCalendars),
                Map.copyOf(immutableServiceExceptions),
                parsedAgencyTimeZone);
    }

    private ZoneId readAgencyTimeZone(ZipInputStream zip) throws IOException {
        byte[] contents = zip.readAllBytes();
        final ZoneId[] timeZone = {ZoneId.of("Europe/Paris")};
        readBytes(contents, (record) -> {
            String zone = record.get("agency_timezone");
            if (!zone.isBlank()) {
                timeZone[0] = ZoneId.of(zone);
            }
        });
        return timeZone[0];
    }

    private long parseGtfsTime(String value) {
        String[] parts = value.split(":");
        return Long.parseLong(parts[0]) * 3600L
                + Long.parseLong(parts[1]) * 60L
                + Long.parseLong(parts[2]);
    }

    private Long parseOptionalGtfsTime(String value) {
        return value.isBlank() ? null : parseGtfsTime(value);
    }

    private LocalDate parseGtfsDate(String value) {
        return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
    }

    private void readEntry(ZipInputStream zip, RecordConsumer consumer) throws IOException {
        readBytes(zip.readAllBytes(), consumer);
    }

    private void readBytes(byte[] contents, RecordConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            try (CSVParser parser = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .get()
                    .parse(reader)) {
                for (CSVRecord record : parser) {
                    consumer.accept(record);
                }
            }
        }
    }

    public record Route(String shortName, String longName, int routeType) {
    }

    public record Stop(String name) {
    }

    public record Trip(String routeId, String serviceId, String headsign) {
    }

    public record TripDirection(String routeId, String headsign) {
    }

    public record StopInfo(int stopSequence, String stopId, String stopName) {
    }

    public record DirectionStops(String direction, List<StopInfo> stops) {
    }

    public record ScheduledTrip(
            String tripId,
            String routeId,
            String line,
            String direction,
            String stopId,
            int stopSequence,
            Instant scheduledArrivalTime,
            Instant scheduledDepartureTime) {
    }

    private record ScheduledDeparture(
            String tripId,
            String routeId,
            String serviceId,
            int stopSequence,
            long departureSeconds,
            Long arrivalSeconds) {
    }

    private record ServiceCalendar(
            LocalDate startDate,
            LocalDate endDate,
            boolean monday,
            boolean tuesday,
            boolean wednesday,
            boolean thursday,
            boolean friday,
            boolean saturday,
            boolean sunday) {
    }

    private record DirectionKey(String line, String headsign) {
    }

    private record StopDirectionKey(String line, String direction, String stopId) {
    }

    private record StopSequence(String stopId, int sequence) {
    }

    private record Catalog(
            Map<String, Route> routes,
            Map<String, Stop> stops,
            Map<String, Trip> trips,
            Map<StopSequence, Set<TripDirection>> tripsByStopSequence,
            Map<DirectionKey, Map<Integer, Set<String>>> stopsByLineAndDirection,
            Map<StopDirectionKey, List<ScheduledDeparture>> departuresByStopDirection,
            Map<String, ServiceCalendar> serviceCalendars,
            Map<LocalDate, Map<String, Boolean>> serviceExceptions,
            ZoneId agencyTimeZone) {
    }

    @FunctionalInterface
    private interface RecordConsumer {
        void accept(CSVRecord record) throws IOException;
    }
}
