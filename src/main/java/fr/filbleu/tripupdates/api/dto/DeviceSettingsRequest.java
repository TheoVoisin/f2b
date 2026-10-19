package fr.filbleu.tripupdates.api.dto;

public record DeviceSettingsRequest(
        String name,
        String line,
        String direction,
        String stopId,
        String postalCode) {
}
