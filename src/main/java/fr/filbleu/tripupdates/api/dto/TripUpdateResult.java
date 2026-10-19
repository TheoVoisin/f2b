package fr.filbleu.tripupdates.api.dto;

import java.time.Instant;

public record TripUpdateResult(
        String tripId,
        String routeId,
        String line,
        String direction,
        String stopId,
        String stopName,
        Integer stopSequence,
        Instant arrivalTime,
        Integer arrivalDelaySeconds,
        Instant departureTime,
        Integer departureDelaySeconds,
        Instant feedTimestamp) {
}
