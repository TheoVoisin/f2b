package fr.filbleu.tripupdates;

import java.util.List;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/lines")
public class LineController {

    private final GtfsStaticCatalog catalog;
    private final TripUpdateService tripUpdateService;
    private final LineStatusService lineStatusService;

    public LineController(
            GtfsStaticCatalog catalog,
            TripUpdateService tripUpdateService,
            LineStatusService lineStatusService) {
        this.catalog = catalog;
        this.tripUpdateService = tripUpdateService;
        this.lineStatusService = lineStatusService;
    }

    @GetMapping("/{line}/direction/{direction}/stops/{stopId}/trips")
    public List<UpcomingTripResult> getTripUpdatesAtStop(
            @PathVariable String line,
            @PathVariable String direction,
            @PathVariable String stopId) {
        return tripUpdateService.nextTripsAtStop(line, stopId, direction);
    }

    @GetMapping("/{line}/status")
    public LineStatusResult getLineStatus(@PathVariable String line) {
        if (catalog.stopsForLine(line).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found: " + line);
        }
        return lineStatusService.statusForLine(line);
    }

    @GetMapping("/{line}/directions")
    public LineDirectionsResult getDirections(@PathVariable String line) {
        List<GtfsStaticCatalog.DirectionStops> catalogDirections = catalog.stopsForLine(line);
        if (catalogDirections.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found: " + line);
        }

        List<LineDirectionsResult.Direction> directions = catalogDirections.stream()
                .map(direction -> new LineDirectionsResult.Direction(
                        direction.direction(),
                        UriUtils.encodePathSegment(direction.direction(), StandardCharsets.UTF_8)))
                .toList();
        return new LineDirectionsResult(line, directions);
    }

    @GetMapping("/{line}/direction/{direction}/stops")
    public LineStopsResult getStops(
            @PathVariable String line,
            @PathVariable String direction) {
        GtfsStaticCatalog.DirectionStops catalogDirection =
                catalog.stopsForLineAndDirection(line, direction);
        if (catalogDirection == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Direction not found for line " + line + ": " + direction);
        }

        List<LineStopsResult.Stop> stops = catalogDirection.stops().stream()
                .map(stop -> new LineStopsResult.Stop(stop.stopSequence(), stop.stopId(), stop.stopName()))
                .toList();
        return new LineStopsResult(
                line,
                List.of(new LineStopsResult.DirectionStops(catalogDirection.direction(), stops)));
    }
}
