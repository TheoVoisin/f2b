package fr.filbleu.tripupdates;

import java.time.Instant;

public record UpcomingTripResult(
        String tripId,
        String realtimeTripId,
        String line,
        String direction,
        String stopId,
        String stopName,
        int stopSequence,
        Instant scheduledArrivalTime,
        Instant scheduledDepartureTime,
        Instant arrivalTime,
        Integer arrivalDelaySeconds,
        Instant departureTime,
        Integer departureDelaySeconds,
        Instant feedTimestamp,
        LineStatusResult lineStatus) {
}
