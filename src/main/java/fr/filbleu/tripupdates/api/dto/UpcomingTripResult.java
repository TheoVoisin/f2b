package fr.filbleu.tripupdates.api.dto;

import java.time.Instant;
import java.util.List;

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
        LineStatusResult lineStatus,
        List<DownstreamStopArrivalResult> downstreamArrivals) {

    public UpcomingTripResult {
        downstreamArrivals = downstreamArrivals == null ? List.of() : List.copyOf(downstreamArrivals);
    }

    public UpcomingTripResult(
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
        this(
                tripId,
                realtimeTripId,
                line,
                direction,
                stopId,
                stopName,
                stopSequence,
                scheduledArrivalTime,
                scheduledDepartureTime,
                arrivalTime,
                arrivalDelaySeconds,
                departureTime,
                departureDelaySeconds,
                feedTimestamp,
                lineStatus,
                List.of());
    }
}
