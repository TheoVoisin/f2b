package fr.filbleu.tripupdates.service;

import com.fasterxml.jackson.databind.JsonNode;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult.CurrentWeather;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult.PeriodForecast;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WeatherForecastService {

    private final RestClient restClient;
    private final WeatherDescriptionTranslationService translationService;
    private final String geocodingUrl;
    private final String forecastUrl;

    public WeatherForecastService(
            RestClient restClient,
            WeatherDescriptionTranslationService translationService,
            @Value("${weather.geocoding-url:https://geo.api.gouv.fr/communes}") String geocodingUrl,
            @Value("${weather.forecast-url:https://api.open-meteo.com/v1/forecast}") String forecastUrl) {
        this.restClient = restClient;
        this.translationService = translationService;
        this.geocodingUrl = geocodingUrl;
        this.forecastUrl = forecastUrl;
    }

    public WeatherForecastResult forecastForPostalCode(String postalCode) {
        if (postalCode == null || !postalCode.matches("\\d{5}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A French five-digit postal code is required");
        }

        JsonNode commune = findCommune(postalCode);
        JsonNode coordinates = commune.path("centre").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2
                || !coordinates.get(0).isNumber() || !coordinates.get(1).isNumber()) {
            throw badGateway("The French commune service returned invalid coordinates");
        }

        double longitude = coordinates.get(0).asDouble();
        double latitude = coordinates.get(1).asDouble();
        JsonNode forecast = fetchForecast(latitude, longitude);
        JsonNode current = forecast.path("current");
        LocalDateTime currentTime = parseCurrentTime(current.path("time").asText(null));
        LocalDate date = currentTime.toLocalDate();
        CurrentWeather currentWeather = new CurrentWeather(
                currentTime,
                number(current, "temperature_2m"),
                number(current, "apparent_temperature"),
                integer(current, "relative_humidity_2m"),
                number(current, "precipitation"),
                number(current, "wind_speed_10m"),
                integer(current, "weather_code"),
                translationService.toFrench(integer(current, "weather_code")));

        JsonNode hourly = forecast.path("hourly");
        return new WeatherForecastResult(
                postalCode,
                commune.path("nom").asText(),
                date,
                currentWeather,
                periodForecast(hourly, date, 6, 12),
                periodForecast(hourly, date, 12, 18),
                periodForecast(hourly, date, 18, 24));
    }

    private JsonNode findCommune(String postalCode) {
        JsonNode communes;
        try {
            communes = restClient.get()
                    .uri(geocodingUrl + "?codePostal={postalCode}&fields=nom,centre&format=json", postalCode)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw badGateway("Unable to retrieve commune information for the postal code", exception);
        }
        if (communes == null || !communes.isArray()) {
            throw badGateway("The French commune service returned an invalid response");
        }
        if (communes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No French commune found for postal code " + postalCode);
        }
        return communes.get(0);
    }

    private JsonNode fetchForecast(double latitude, double longitude) {
        JsonNode response;
        try {
            response = restClient.get()
                    .uri(UriComponentsBuilder.fromUriString(forecastUrl)
                            .queryParam("latitude", latitude)
                            .queryParam("longitude", longitude)
                            .queryParam(
                                    "current",
                                    "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m")
                            .queryParam("hourly", "temperature_2m,weather_code,precipitation_probability")
                            .queryParam("forecast_days", 1)
                            .queryParam("timezone", "auto")
                            .build()
                            .encode()
                            .toUri())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw badGateway("Unable to retrieve the weather forecast", exception);
        }
        if (response == null || !response.path("current").isObject() || !response.path("hourly").isObject()) {
            throw badGateway("The weather service returned an invalid response");
        }
        return response;
    }

    private PeriodForecast periodForecast(JsonNode hourly, LocalDate date, int startHour, int endHour) {
        JsonNode times = hourly.path("time");
        JsonNode temperatures = hourly.path("temperature_2m");
        JsonNode weatherCodes = hourly.path("weather_code");
        JsonNode precipitationProbabilities = hourly.path("precipitation_probability");
        if (!times.isArray() || !temperatures.isArray() || !weatherCodes.isArray()
                || !precipitationProbabilities.isArray()
                || times.size() != temperatures.size()
                || times.size() != weatherCodes.size()
                || times.size() != precipitationProbabilities.size()) {
            throw badGateway("The weather service returned incomplete hourly forecasts");
        }

        List<Double> periodTemperatures = new ArrayList<>();
        Map<Integer, Integer> weatherCodeCounts = new LinkedHashMap<>();
        int maximumPrecipitationProbability = 0;
        for (int index = 0; index < times.size(); index++) {
            LocalDateTime time;
            try {
                time = LocalDateTime.parse(times.get(index).asText());
            } catch (DateTimeParseException exception) {
                throw badGateway("The weather service returned an invalid hourly timestamp", exception);
            }
            if (!time.toLocalDate().equals(date)
                    || time.getHour() < startHour
                    || time.getHour() >= endHour) {
                continue;
            }
            JsonNode temperature = temperatures.get(index);
            JsonNode weatherCode = weatherCodes.get(index);
            JsonNode precipitationProbability = precipitationProbabilities.get(index);
            if (!temperature.isNumber() || !weatherCode.isIntegralNumber()
                    || !precipitationProbability.isIntegralNumber()) {
                throw badGateway("The weather service returned invalid hourly forecast values");
            }
            periodTemperatures.add(temperature.asDouble());
            weatherCodeCounts.merge(weatherCode.asInt(), 1, Integer::sum);
            maximumPrecipitationProbability =
                    Math.max(maximumPrecipitationProbability, precipitationProbability.asInt());
        }

        if (periodTemperatures.isEmpty()) {
            throw badGateway("The weather service did not return forecast data for the requested period");
        }

        double minimum = periodTemperatures.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double maximum = periodTemperatures.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double average = periodTemperatures.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        int representativeWeatherCode = weatherCodeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow()
                .getKey();
        return new PeriodForecast(
                average,
                minimum,
                maximum,
                representativeWeatherCode,
                translationService.toFrench(representativeWeatherCode),
                maximumPrecipitationProbability);
    }

    private LocalDateTime parseCurrentTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw badGateway("The weather service omitted the current timestamp");
        }
        try {
            return LocalDateTime.parse(timestamp);
        } catch (DateTimeParseException exception) {
            throw badGateway("The weather service returned an invalid current timestamp", exception);
        }
    }

    private double number(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isNumber()) {
            throw badGateway("The weather service omitted a valid " + field + " value");
        }
        return value.asDouble();
    }

    private int integer(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber()) {
            throw badGateway("The weather service omitted a valid " + field + " value");
        }
        return value.asInt();
    }

    private ResponseStatusException badGateway(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private ResponseStatusException badGateway(String message, Exception cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
