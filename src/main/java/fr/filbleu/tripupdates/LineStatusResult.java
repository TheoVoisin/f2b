package fr.filbleu.tripupdates;

import java.time.Instant;
import java.util.List;

public record LineStatusResult(
        String line,
        String status,
        boolean alertFeedAvailable,
        Instant feedTimestamp,
        Instant lastSuccessfulFetch,
        List<Alert> alerts) {

    public record Alert(
            String id,
            String cause,
            String effect,
            String header,
            String description,
            String url,
            List<ActivePeriod> activePeriods,
            List<InformedEntity> informedEntities) {
    }

    public record ActivePeriod(Instant start, Instant end) {
    }

    public record InformedEntity(String routeId, String tripId, String stopId) {
    }
}
