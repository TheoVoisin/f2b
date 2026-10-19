package fr.filbleu.tripupdates.infrastructure.gtfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GtfsStaticCatalogTest {

    @Test
    void resolvesScheduledArrivalAtConfiguredStopEvenWhenItIsATerminalStop() throws Exception {
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(restClient.get().uri(anyString()).retrieve().body(byte[].class))
                .thenReturn(smallGtfsArchive());
        GtfsStaticCatalog catalog = new GtfsStaticCatalog(restClient);
        catalog.load();

        GtfsStaticCatalog.ScheduledStopArrival arrival = catalog.scheduledArrivalAtStop(
                "trip-1",
                LocalDate.of(2026, 10, 19),
                "TERMINAL",
                10);

        assertEquals(30, arrival.stopSequence());
        assertEquals(Instant.parse("2026-10-19T06:30:00Z"), arrival.arrivalTime());
        assertNull(catalog.scheduledArrivalAtStop(
                "trip-1",
                LocalDate.of(2026, 10, 19),
                "MAIN",
                10));
    }

    private byte[] smallGtfsArchive() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("routes.txt", """
                route_id,route_short_name,route_long_name,route_type
                route-1,2,Line 2,3
                """);
        files.put("stops.txt", """
                stop_id,stop_name
                MAIN,Main stop
                NEXT,Next stop
                TERMINAL,Terminal stop
                """);
        files.put("trips.txt", """
                route_id,service_id,trip_id,trip_headsign
                route-1,service-1,trip-1,Destination
                """);
        files.put("stop_times.txt", """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                trip-1,08:00:00,08:00:00,MAIN,10
                trip-1,08:15:00,08:16:00,NEXT,20
                trip-1,08:30:00,,TERMINAL,30
                """);
        files.put("calendar.txt", """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                service-1,1,1,1,1,1,1,1,20260101,20261231
                """);
        files.put("calendar_dates.txt", """
                service_id,date,exception_type
                """);
        files.put("agency.txt", """
                agency_name,agency_url,agency_timezone
                Fil Bleu,https://example.test,Europe/Paris
                """);

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive.toByteArray();
    }
}
