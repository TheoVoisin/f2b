package fr.filbleu.tripupdates.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.UUID;

public record Device(
        UUID uuid,
        String name,
        String line,
        String direction,
        String stopId,
        String postalCode,
        List<String> downstreamStopIds) {

    public Device {
        downstreamStopIds = downstreamStopIds == null ? List.of() : List.copyOf(downstreamStopIds);
    }

    public Device(UUID uuid, String name, String line, String direction, String stopId, String postalCode) {
        this(uuid, name, line, direction, stopId, postalCode, List.of());
    }

    public static Device registered(UUID uuid) {
        return new Device(uuid, uuid.toString(), null, null, null, null, List.of());
    }

    @JsonIgnore
    public boolean isConfigured() {
        return line != null && direction != null && stopId != null && postalCode != null;
    }
}
