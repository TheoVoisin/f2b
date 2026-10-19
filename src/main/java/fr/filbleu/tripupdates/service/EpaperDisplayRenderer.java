package fr.filbleu.tripupdates.service;

import fr.filbleu.tripupdates.api.dto.DeviceInfosResult;
import fr.filbleu.tripupdates.api.dto.LineStatusResult;
import fr.filbleu.tripupdates.api.dto.UpcomingTripResult;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult.PeriodForecast;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class EpaperDisplayRenderer {

    public static final int WIDTH = 400;
    public static final int HEIGHT = 300;

    private static final int MARGIN = 12;
    private static final int CONTENT_WIDTH = WIDTH - 2 * MARGIN;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE dd MMMM uuuu", Locale.FRENCH);
    private static final Font NORMAL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    private static final Font ARRIVAL_TIME = new Font(Font.SANS_SERIF, Font.BOLD, 20);

    public byte[] render(DeviceInfosResult infos) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            drawHeader(graphics, infos);
            boolean hasAlerts = drawAlerts(graphics, infos.lineStatus());
            int tripTitleY = hasAlerts ? 129 : 105;
            drawTrips(graphics, infos.trips(), tripTitleY);
            drawWeather(graphics, infos.weather(), hasAlerts ? 214 : 208);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("No PNG image writer is available");
        }
        return output.toByteArray();
    }

    private void drawHeader(Graphics2D graphics, DeviceInfosResult infos) {
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, WIDTH, 31);
        graphics.setColor(Color.WHITE);
        graphics.setFont(BOLD);
        graphics.drawString(LocalTime.now(DISPLAY_ZONE).format(TIME_FORMAT), MARGIN, 20);
        String date = LocalDate.now(DISPLAY_ZONE).format(DATE_FORMAT).toUpperCase(Locale.FRENCH);
        FontMetrics metrics = graphics.getFontMetrics();
        int dateX = (WIDTH - metrics.stringWidth(date)) / 2;
        graphics.drawString(date, dateX, 20);
        String commune = safe(infos.weather().commune());
        int communeWidth = Math.max(0, WIDTH - MARGIN - (dateX + metrics.stringWidth(date)) - MARGIN);
        drawFitted(
                graphics,
                commune,
                WIDTH - MARGIN - Math.min(metrics.stringWidth(commune), communeWidth),
                20,
                communeWidth);

        graphics.setColor(Color.BLACK);
        graphics.setFont(NORMAL);
        drawFitted(graphics,
                safe(infos.device().line()) + "  >  " + safe(infos.device().direction()),
                MARGIN,
                46,
                CONTENT_WIDTH);
        graphics.setFont(BOLD);
        drawFitted(graphics, stopName(infos), MARGIN, 62, CONTENT_WIDTH);
        graphics.drawLine(MARGIN, 68, WIDTH - MARGIN, 68);
    }

    private String stopName(DeviceInfosResult infos) {
        return infos.trips().stream()
                .map(UpcomingTripResult::stopName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(safe(infos.device().stopId()));
    }

    private boolean drawAlerts(Graphics2D graphics, LineStatusResult status) {
        List<LineStatusResult.Alert> alerts = status.alerts();
        if (alerts != null && !alerts.isEmpty()) {
            int boxTop = 73;
            graphics.setColor(Color.BLACK);
            graphics.drawRect(MARGIN, boxTop, CONTENT_WIDTH, 43);
            graphics.fillRect(MARGIN + 1, boxTop + 1, CONTENT_WIDTH - 1, 12);
            graphics.setColor(Color.WHITE);
            graphics.setFont(SMALL.deriveFont(Font.BOLD, 11f));
            String label = alerts.size() == 1 ? "ALERTE" : "ALERTE (" + alerts.size() + ")";
            graphics.drawString(label, MARGIN + 5, boxTop + 10);

            graphics.setColor(Color.BLACK);
            graphics.setFont(SMALL);
            String alertText = firstNonBlank(
                    alerts.get(0).header(),
                    alerts.get(0).description(),
                    alerts.get(0).effect(),
                    "Perturbation en cours");
            drawWrapped(graphics, alertText, MARGIN + 5, boxTop + 25, CONTENT_WIDTH - 10, 12, 2);
            return true;
        }

        graphics.setColor(Color.BLACK);
        graphics.setFont(SMALL);
        String message = status.alertFeedAvailable()
                ? "Aucune alerte publiee"
                : "Flux d'alertes indisponible";
        graphics.drawString(message, MARGIN, 83);
        return false;
    }

    private void drawTrips(Graphics2D graphics, List<UpcomingTripResult> trips, int titleY) {
        graphics.setColor(Color.BLACK);
        graphics.setFont(BOLD.deriveFont(15f));
        graphics.drawString("PROCHAINS PASSAGES", MARGIN, titleY);
        graphics.setFont(NORMAL);

        if (trips == null || trips.isEmpty()) {
            graphics.drawString("Aucun passage disponible", MARGIN, titleY + 18);
        } else {
            int rowTop = titleY + 7;
            for (int index = 0; index < Math.min(2, trips.size()); index++) {
                UpcomingTripResult trip = trips.get(index);
                int y = rowTop + index * 40;
                graphics.drawRect(MARGIN, y, CONTENT_WIDTH, 36);
                graphics.setFont(BOLD);
                graphics.drawString((index + 1) + ".", MARGIN + 6, y + 20);
                String time = trip.arrivalTime() == null
                        ? formatTime(trip.scheduledArrivalTime())
                        : formatTime(trip.arrivalTime());
                graphics.setFont(ARRIVAL_TIME);
                graphics.drawString(time, MARGIN + 28, y + 23);
                String delay = delayLabel(trip);
                if (delay != null) {
                    graphics.setFont(NORMAL);
                    drawFitted(graphics, delay, MARGIN + 95, y + 19, CONTENT_WIDTH - 101);
                }
                graphics.setFont(SMALL);
                graphics.drawString(
                        trip.arrivalTime() == null ? "THEORIQUE" : "TEMPS REEL",
                        MARGIN + 28,
                        y + 34);
            }
        }
    }

    private void drawWeather(Graphics2D graphics, WeatherForecastResult weather, int top) {
        graphics.setColor(Color.BLACK);
        graphics.drawLine(MARGIN, top, WIDTH - MARGIN, top);
        int tileTop = top + 1;
        int tileWidth = CONTENT_WIDTH / 4;
        for (int index = 0; index < 4; index++) {
            int x = MARGIN + index * tileWidth;
            if (index > 0) {
                graphics.drawLine(x, tileTop, x, HEIGHT - 4);
            }
            graphics.setFont(SMALL.deriveFont(Font.BOLD));
            drawFitted(
                    graphics,
                    new String[] {"ACTUEL", "MATIN", "APRES-MIDI", "SOIR"}[index],
                    x + 4,
                    tileTop + 9,
                    tileWidth - 8);
            graphics.setFont(NORMAL);
            String temperature;
            String description;
            String rain;
            int weatherCode;
            if (index == 0) {
                temperature = temperature(weather.current().temperatureCelsius());
                description = weather.current().description();
                rain = String.format(Locale.ROOT, "%.1f mm", weather.current().precipitationMillimeters());
                weatherCode = weather.current().weatherCode();
            } else {
                PeriodForecast period = period(weather, index);
                temperature = temperature(period.averageTemperatureCelsius());
                description = period.description();
                rain = "Pluie " + period.maximumPrecipitationProbabilityPercent() + "%";
                weatherCode = period.weatherCode();
            }
            drawWeatherIcon(graphics, weatherCode, x + 6, tileTop + 13);
            graphics.drawString(temperature, x + 36, tileTop + 31);
            graphics.setFont(SMALL);
            drawWrapped(graphics, safe(description), x + 4, tileTop + 47, tileWidth - 8, 10, 2);
            graphics.drawString(rain, x + 4, HEIGHT - 3);
        }
    }

    private void drawWeatherIcon(Graphics2D graphics, int weatherCode, int x, int y) {
        if (weatherCode == 0) {
            drawSun(graphics, x + 8, y + 10);
        } else if (weatherCode == 1 || weatherCode == 2) {
            drawSun(graphics, x + 7, y + 8);
            drawCloud(graphics, x + 1, y + 8);
        } else if (weatherCode == 45 || weatherCode == 48) {
            drawFog(graphics, x, y);
        } else if (weatherCode >= 95) {
            drawCloud(graphics, x, y + 1);
            drawLightning(graphics, x + 8, y + 12);
            if (weatherCode >= 96) {
                drawRain(graphics, x + 1, y + 14, 1);
            }
        } else if ((weatherCode >= 71 && weatherCode <= 86) || weatherCode == 77) {
            drawCloud(graphics, x, y + 1);
            drawSnowflake(graphics, x + 4, y + 16);
            drawSnowflake(graphics, x + 13, y + 16);
        } else if ((weatherCode >= 51 && weatherCode <= 67)
                || (weatherCode >= 80 && weatherCode <= 82)) {
            drawCloud(graphics, x, y + 1);
            drawRain(graphics, x + 2, y + 14, 3);
        } else {
            drawCloud(graphics, x, y + 3);
        }
    }

    private void drawSun(Graphics2D graphics, int centerX, int centerY) {
        graphics.setColor(Color.BLACK);
        graphics.drawOval(centerX - 4, centerY - 4, 8, 8);
        graphics.drawLine(centerX, centerY - 8, centerX, centerY - 6);
        graphics.drawLine(centerX, centerY + 6, centerX, centerY + 8);
        graphics.drawLine(centerX - 8, centerY, centerX - 6, centerY);
        graphics.drawLine(centerX + 6, centerY, centerX + 8, centerY);
        graphics.drawLine(centerX - 6, centerY - 6, centerX - 5, centerY - 5);
        graphics.drawLine(centerX + 5, centerY + 5, centerX + 6, centerY + 6);
        graphics.drawLine(centerX + 5, centerY - 5, centerX + 6, centerY - 6);
        graphics.drawLine(centerX - 6, centerY + 6, centerX - 5, centerY + 5);
    }

    private void drawCloud(Graphics2D graphics, int x, int y) {
        graphics.setColor(Color.WHITE);
        graphics.fillOval(x + 2, y + 5, 12, 9);
        graphics.fillOval(x + 7, y + 1, 11, 13);
        graphics.fillOval(x + 13, y + 6, 10, 8);
        graphics.fillRect(x + 6, y + 8, 14, 6);
        graphics.setColor(Color.BLACK);
        graphics.drawArc(x + 2, y + 5, 12, 9, 90, 180);
        graphics.drawArc(x + 7, y + 1, 11, 13, 0, 180);
        graphics.drawArc(x + 13, y + 6, 10, 8, 0, 90);
        graphics.drawLine(x + 5, y + 14, x + 19, y + 14);
    }

    private void drawFog(Graphics2D graphics, int x, int y) {
        drawCloud(graphics, x, y);
        graphics.drawLine(x + 2, y + 17, x + 19, y + 17);
        graphics.drawLine(x + 5, y + 20, x + 16, y + 20);
    }

    private void drawRain(Graphics2D graphics, int x, int y, int drops) {
        for (int index = 0; index < drops; index++) {
            int dropX = x + index * 7;
            graphics.drawLine(dropX + 2, y, dropX, y + 4);
        }
    }

    private void drawSnowflake(Graphics2D graphics, int centerX, int centerY) {
        graphics.drawLine(centerX - 3, centerY, centerX + 3, centerY);
        graphics.drawLine(centerX, centerY - 3, centerX, centerY + 3);
        graphics.drawLine(centerX - 2, centerY - 2, centerX + 2, centerY + 2);
        graphics.drawLine(centerX - 2, centerY + 2, centerX + 2, centerY - 2);
    }

    private void drawLightning(Graphics2D graphics, int x, int y) {
        graphics.drawLine(x + 4, y, x, y + 6);
        graphics.drawLine(x, y + 6, x + 4, y + 6);
        graphics.drawLine(x + 4, y + 6, x + 1, y + 12);
    }

    private PeriodForecast period(WeatherForecastResult weather, int index) {
        return switch (index) {
            case 1 -> weather.morning();
            case 2 -> weather.afternoon();
            default -> weather.evening();
        };
    }

    private String delayLabel(UpcomingTripResult trip) {
        Integer delaySeconds = trip.arrivalDelaySeconds() != null
                ? trip.arrivalDelaySeconds()
                : trip.departureDelaySeconds();
        if (delaySeconds == null) {
            return null;
        }
        long minutes = Math.round(delaySeconds / 60.0);
        if (minutes == 0) {
            return "A l'heure";
        }
        return (minutes > 0 ? "+" : "") + minutes + " min";
    }

    private String formatTime(Instant time) {
        return time == null ? "--:--" : TIME_FORMAT.format(time.atZone(DISPLAY_ZONE));
    }

    private String temperature(double value) {
        return String.format(Locale.ROOT, "%.0f C", value);
    }

    private void drawWrapped(
            Graphics2D graphics,
            String text,
            int x,
            int baseline,
            int width,
            int lineHeight,
            int maximumLines) {
        FontMetrics metrics = graphics.getFontMetrics();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        for (int index = 0; index < Math.min(maximumLines, lines.size()); index++) {
            drawFitted(graphics, lines.get(index), x, baseline + index * lineHeight, width);
        }
    }

    private void drawFitted(Graphics2D graphics, String value, int x, int y, int width) {
        String text = safe(value);
        FontMetrics metrics = graphics.getFontMetrics();
        while (!text.isEmpty() && metrics.stringWidth(text) > width) {
            text = text.substring(0, text.length() - 1);
        }
        graphics.drawString(text, x, y);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
