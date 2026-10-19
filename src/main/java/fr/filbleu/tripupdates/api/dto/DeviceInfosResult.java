package fr.filbleu.tripupdates.api.dto;

import java.util.List;

public record DeviceInfosResult(
        Device device,
        List<UpcomingTripResult> trips,
        LineStatusResult lineStatus,
        WeatherForecastResult weather) {
}
