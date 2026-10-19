package fr.filbleu.tripupdates.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

public record Device(
        UUID uuid,
        String name,
        String line,
        String direction,
        String stopId,
        String postalCode) {

    public static Device registered(UUID uuid) {
        return new Device(uuid, uuid.toString(), null, null, null, null);
    }

    @JsonIgnore
    public boolean isConfigured() {
        return line != null && direction != null && stopId != null && postalCode != null;
    }
}
