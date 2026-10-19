package fr.filbleu.tripupdates.api;

import fr.filbleu.tripupdates.api.dto.ConnectionResult;
import fr.filbleu.tripupdates.api.dto.Device;
import fr.filbleu.tripupdates.api.dto.DeviceInfosResult;
import fr.filbleu.tripupdates.api.dto.DeviceRegistrationRequest;
import fr.filbleu.tripupdates.api.dto.DeviceSettingsRequest;
import fr.filbleu.tripupdates.api.dto.LineStatusResult;
import fr.filbleu.tripupdates.api.dto.WeatherForecastResult;
import fr.filbleu.tripupdates.api.dto.UpcomingTripResult;
import fr.filbleu.tripupdates.infrastructure.gtfs.GtfsStaticCatalog;
import fr.filbleu.tripupdates.service.DeviceRegistryService;
import fr.filbleu.tripupdates.service.EpaperDisplayRenderer;
import fr.filbleu.tripupdates.service.LineStatusService;
import fr.filbleu.tripupdates.service.TripUpdateService;
import fr.filbleu.tripupdates.service.WeatherForecastService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistryService deviceRegistryService;
    private final EpaperDisplayRenderer epaperDisplayRenderer;
    private final TripUpdateService tripUpdateService;
    private final LineStatusService lineStatusService;
    private final WeatherForecastService weatherForecastService;
    private final GtfsStaticCatalog catalog;

    public DeviceController(
            DeviceRegistryService deviceRegistryService,
            EpaperDisplayRenderer epaperDisplayRenderer,
            TripUpdateService tripUpdateService,
            LineStatusService lineStatusService,
            WeatherForecastService weatherForecastService,
            GtfsStaticCatalog catalog) {
        this.deviceRegistryService = deviceRegistryService;
        this.epaperDisplayRenderer = epaperDisplayRenderer;
        this.tripUpdateService = tripUpdateService;
        this.lineStatusService = lineStatusService;
        this.weatherForecastService = weatherForecastService;
        this.catalog = catalog;
    }

    @GetMapping("/ping")
    public ConnectionResult ping() {
        return new ConnectionResult("ok");
    }

    @GetMapping
    public List<Device> getDevices() {
        return deviceRegistryService.findAll();
    }

    @PostMapping
    public ResponseEntity<Device> register(@RequestBody DeviceRegistrationRequest request) {
        if (request == null || request.uuid() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A device UUID is required");
        }
        Device device = deviceRegistryService.register(request.uuid());
        return ResponseEntity.status(HttpStatus.CREATED).body(device);
    }

    @GetMapping("/{uuid}")
    public Device getDevice(@PathVariable("uuid") UUID uuid) {
        return deviceRegistryService.find(uuid);
    }

    @PutMapping("/{uuid}")
    public Device updateSettings(
            @PathVariable("uuid") UUID uuid,
            @RequestBody DeviceSettingsRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Device settings are required");
        }
        validateDownstreamStops(request);
        return deviceRegistryService.update(
                uuid,
                request.name(),
                request.line(),
                request.direction(),
                request.stopId(),
                request.postalCode(),
                request.downstreamStopIds());
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<Void> delete(@PathVariable("uuid") UUID uuid) {
        deviceRegistryService.delete(uuid);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{uuid}/infos")
    public DeviceInfosResult getInfos(@PathVariable("uuid") UUID uuid) {
        Device device = deviceRegistryService.find(uuid);
        if (!device.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Device settings must be configured before requesting infos");
        }

        List<UpcomingTripResult> trips = tripUpdateService.nextTripsAtStop(
                device.line(),
                device.stopId(),
                device.direction(),
                device.downstreamStopIds());
        LineStatusResult lineStatus = lineStatusService.statusForLine(device.line());
        WeatherForecastResult weather = weatherForecastService.forecastForPostalCode(device.postalCode());
        return new DeviceInfosResult(device, trips, lineStatus, weather);
    }

    @GetMapping(value = "/{uuid}/display", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getDisplay(@PathVariable("uuid") UUID uuid) throws IOException {
        byte[] image = epaperDisplayRenderer.render(getInfos(uuid));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache())
                .body(image);
    }

    private void validateDownstreamStops(DeviceSettingsRequest request) {
        List<String> downstreamStopIds = request.downstreamStopIds();
        if (downstreamStopIds == null || downstreamStopIds.isEmpty()) {
            return;
        }
        if (downstreamStopIds.size() > 2
                || request.line() == null
                || request.direction() == null
                || request.stopId() == null
                || request.stopId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid downstream stop configuration");
        }
        GtfsStaticCatalog.DirectionStops direction =
                catalog.stopsForLineAndDirection(request.line(), request.direction());
        if (direction == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected line direction is unavailable");
        }
        int previousStopSequence = -1;
        for (GtfsStaticCatalog.StopInfo stop : direction.stops()) {
            if (stop.stopId().equalsIgnoreCase(request.stopId().trim())) {
                previousStopSequence = stop.stopSequence();
                break;
            }
        }
        if (previousStopSequence < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected stop is unavailable");
        }

        for (String stopId : downstreamStopIds) {
            if (stopId == null || stopId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Downstream stop IDs cannot be blank");
            }
            int stopSequence = -1;
            for (GtfsStaticCatalog.StopInfo stop : direction.stops()) {
                if (stop.stopSequence() > previousStopSequence
                        && stop.stopId().equalsIgnoreCase(stopId.trim())) {
                    stopSequence = stop.stopSequence();
                    break;
                }
            }
            if (stopSequence < 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Downstream stops must follow the main stop in route order");
            }
            previousStopSequence = stopSequence;
        }
    }
}
