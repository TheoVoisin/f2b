package fr.filbleu.tripupdates.api.dto;

import java.time.Instant;

public record DownstreamStopArrivalResult(
        String stopId,
        String stopName,
        Instant scheduledArrivalTime,
        Instant arrivalTime,
        Integer arrivalDelaySeconds) {
}
