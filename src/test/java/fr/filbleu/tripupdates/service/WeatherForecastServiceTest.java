package fr.filbleu.tripupdates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class WeatherForecastServiceTest {

    @Test
    @SuppressWarnings("null")
    void returnsCurrentConditionsAndAggregatedForecastForEachPeriod() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("codePostal=37000")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        [
                          {"nom":"Tours","centre":{"type":"Point","coordinates":[0.68,47.39]}},
                          {"nom":"Saint-Pierre-des-Corps","centre":{"type":"Point","coordinates":[0.73,47.39]}}
                        ]
                        """,
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://api.open-meteo.com/v1/forecast?")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(forecastResponse(), MediaType.APPLICATION_JSON));

        WeatherForecastService service = new WeatherForecastService(
                builder.build(),
                new WeatherDescriptionTranslationService(),
                "https://geo.api.gouv.fr/communes",
                "https://api.open-meteo.com/v1/forecast");

        WeatherForecastResult result = service.forecastForPostalCode("37000");

        assertEquals("Tours", result.commune());
        assertEquals("2026-10-16", result.date().toString());
        assertEquals("2026-10-16T10:00", result.current().time().toString());
        assertEquals(14.0, result.current().temperatureCelsius());
        assertEquals("Principalement dégagé", result.current().description());
        assertEquals(12.5, result.morning().averageTemperatureCelsius());
        assertEquals(10.0, result.morning().minimumTemperatureCelsius());
        assertEquals(15.0, result.morning().maximumTemperatureCelsius());
        assertEquals(1, result.morning().weatherCode());
        assertEquals(5, result.morning().maximumPrecipitationProbabilityPercent());
        assertEquals(18.5, result.afternoon().averageTemperatureCelsius());
        assertEquals(3, result.afternoon().weatherCode());
        assertEquals("Couvert", result.afternoon().description());
        assertEquals(24.5, result.evening().averageTemperatureCelsius());
        assertEquals(61, result.evening().weatherCode());
        assertEquals("Pluie", result.evening().description());
        assertEquals(17, result.evening().maximumPrecipitationProbabilityPercent());
        server.verify();
    }

    @Test
    void rejectsNonFrenchPostalCodeFormat() {
        WeatherForecastService service = new WeatherForecastService(
                RestClient.create(),
                new WeatherDescriptionTranslationService(),
                "https://geo.api.gouv.fr/communes",
                "https://api.open-meteo.com/v1/forecast");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.forecastForPostalCode("3700"));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void translatesWeatherCodesToFrench() {
        WeatherDescriptionTranslationService service = new WeatherDescriptionTranslationService();

        assertEquals("Ciel dégagé", service.toFrench(0));
        assertEquals("Averses de pluie", service.toFrench(80));
        assertEquals("Conditions inconnues", service.toFrench(-1));
    }

    private String forecastResponse() {
        return """
                {
                  "current": {
                    "time": "2026-10-16T10:00",
                    "temperature_2m": 14,
                    "relative_humidity_2m": 50,
                    "apparent_temperature": 12.5,
                    "precipitation": 0.1,
                    "weather_code": 1,
                    "wind_speed_10m": 12
                  },
                  "hourly": {
                    "time": [
                      "2026-10-16T06:00", "2026-10-16T07:00", "2026-10-16T08:00",
                      "2026-10-16T09:00", "2026-10-16T10:00", "2026-10-16T11:00",
                      "2026-10-16T12:00", "2026-10-16T13:00", "2026-10-16T14:00",
                      "2026-10-16T15:00", "2026-10-16T16:00", "2026-10-16T17:00",
                      "2026-10-16T18:00", "2026-10-16T19:00", "2026-10-16T20:00",
                      "2026-10-16T21:00", "2026-10-16T22:00", "2026-10-16T23:00"
                    ],
                    "temperature_2m": [10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27],
                    "weather_code": [1, 1, 2, 1, 2, 1, 3, 3, 2, 3, 2, 3, 61, 61, 80, 61, 80, 61],
                    "precipitation_probability": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17]
                  }
                }
                """;
    }
}
