package fr.filbleu.tripupdates.api.dto;

import java.util.List;

public record DeviceSettingsRequest(
        String name,
        String line,
        String direction,
        String stopId,
        String postalCode,
        List<String> downstreamStopIds) {
}
