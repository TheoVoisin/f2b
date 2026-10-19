package fr.filbleu.tripupdates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fr.filbleu.tripupdates.api.dto.Device;
import fr.filbleu.tripupdates.api.dto.DeviceInfosResult;
import fr.filbleu.tripupdates.api.dto.LineStatusResult;
import fr.filbleu.tripupdates.api.dto.UpcomingTripResult;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EpaperDisplayRendererTest {

    @Test
    void rendersFourHundredByThreeHundredBlackAndWhitePngWithAlertsAndDelay() throws Exception {
        EpaperDisplayRenderer renderer = new EpaperDisplayRenderer();

        byte[] png = renderer.render(deviceInfosWithAlertAndDelay());
        var image = ImageIO.read(new ByteArrayInputStream(png));

        assertEquals(400, image.getWidth());
        assertEquals(300, image.getHeight());
        boolean hasBlackPixel = false;
        boolean hasWhitePixel = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y) & 0x00ffffff;
                assertTrue(color == 0x000000 || color == 0xffffff);
                hasBlackPixel |= color == 0x000000;
                hasWhitePixel |= color == 0xffffff;
            }
        }
        assertTrue(hasBlackPixel);
        assertTrue(hasWhitePixel);
        assertTrue(countBlackPixels(image, 16, 240, 25, 24) > 10);
    }

    private int countBlackPixels(java.awt.image.BufferedImage image, int x, int y, int width, int height) {
        int count = 0;
        for (int pixelY = y; pixelY < y + height; pixelY++) {
            for (int pixelX = x; pixelX < x + width; pixelX++) {
                if ((image.getRGB(pixelX, pixelY) & 0x00ffffff) == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private DeviceInfosResult deviceInfosWithAlertAndDelay() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Device device = new Device(uuid, "Lobby display", "2", "Centre", "STOP-1", "37000");
        LineStatusResult.Alert alert = new LineStatusResult.Alert(
                "alert-1",
                null,
                "DETOUR",
                "Travaux sur la ligne",
                "Arrêt non desservi jusqu'à demain",
                null,
                List.of(),
                List.of());
        LineStatusResult lineStatus = new LineStatusResult(
                "2",
                "ALERTS_PUBLISHED",
                true,
                Instant.parse("2026-10-19T09:00:00Z"),
                Instant.parse("2026-10-19T09:00:00Z"),
                List.of(alert));
        List<UpcomingTripResult> trips = List.of(
                new UpcomingTripResult(
                        "trip-1",
                        "realtime-1",
                        "2",
                        "Centre",
                        "STOP-1",
                        "Hôtel de Ville",
                        1,
                        Instant.parse("2026-10-19T10:00:00Z"),
                        Instant.parse("2026-10-19T10:00:00Z"),
                        Instant.parse("2026-10-19T10:05:00Z"),
                        300,
                        null,
                        null,
                        Instant.parse("2026-10-19T09:00:00Z"),
                        lineStatus));
        WeatherForecastResult.CurrentWeather current = new WeatherForecastResult.CurrentWeather(
                LocalDateTime.parse("2026-10-19T10:00"),
                14,
                13,
                60,
                0.2,
                10,
                1,
                "Ensoleillé");
        WeatherForecastResult.PeriodForecast period = new WeatherForecastResult.PeriodForecast(
                16,
                12,
                20,
                2,
                "Partiellement nuageux",
                20);
        WeatherForecastResult weather = new WeatherForecastResult(
                "37000",
                "Tours",
                LocalDate.parse("2026-10-19"),
                current,
                period,
                period,
                period);
        return new DeviceInfosResult(device, trips, lineStatus, weather);
    }
}
