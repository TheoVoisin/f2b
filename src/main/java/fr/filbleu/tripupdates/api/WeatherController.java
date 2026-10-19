package fr.filbleu.tripupdates.api;

import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import fr.filbleu.tripupdates.service.WeatherForecastService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherForecastService weatherForecastService;

    public WeatherController(WeatherForecastService weatherForecastService) {
        this.weatherForecastService = weatherForecastService;
    }

    @GetMapping("/{postalCode}")
    public WeatherForecastResult getForecast(@PathVariable String postalCode) {
        return weatherForecastService.forecastForPostalCode(postalCode);
    }
}
