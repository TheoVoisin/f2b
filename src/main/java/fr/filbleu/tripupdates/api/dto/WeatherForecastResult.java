package fr.filbleu.tripupdates.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record WeatherForecastResult(
        String postalCode,
        String commune,
        LocalDate date,
        CurrentWeather current,
        PeriodForecast morning,
        PeriodForecast afternoon,
        PeriodForecast evening) {

    public record CurrentWeather(
            LocalDateTime time,
            double temperatureCelsius,
            double feelsLikeCelsius,
            int relativeHumidityPercent,
            double precipitationMillimeters,
            double windSpeedKilometersPerHour,
            int weatherCode,
            String description) {
    }

    public record PeriodForecast(
            double averageTemperatureCelsius,
            double minimumTemperatureCelsius,
            double maximumTemperatureCelsius,
            int weatherCode,
            String description,
            int maximumPrecipitationProbabilityPercent) {
    }
}
