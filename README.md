# Fil Bleu Trip Updates Server

A small Spring Boot REST API that downloads the Fil Bleu static GTFS schedule at startup, then fetches the live GTFS-Realtime trip updates every 30 seconds.

The realtime feed is Protocol Buffers, not JSON. The server joins its trip, route, and stop IDs with the static GTFS schedule so the API can filter by line number, stop name, and direction. Since realtime trip IDs differ from the static schedule, route and direction are inferred from matching updated stop IDs and stop sequences in `stop_times.txt`.

## Requirements

- Java 17+
- Maven 3.9+

## Run

```powershell
mvn spring-boot:run
```

The server listens on port `8080` by default. The initial realtime fetch happens during startup; later failed refreshes are logged and do not replace the last successfully fetched data.

## Bruno collection

Open the collection in `bruno/fil-bleu-api` with Bruno. The collection-level variables are defined in `collection.bru`; edit them to change the host, line, direction, or stop. No environment needs to be selected.

## API

`GET /api/lines/{line}/direction/{direction}/stops/{stopId}/trips` returns the next two scheduled departures for an exact stop on a line and direction. Realtime times and delays are included when a matching live update is available; otherwise the realtime fields are `null`.

```text
GET http://localhost:8080/api/lines/2/direction/LYCEE%20J.%20MONNET/stops/TTR%3AGATO-2T/trips
```

The response is a JSON array containing scheduled arrival/departure times and optional realtime arrival/departure times and delays. Times are UTC ISO-8601 timestamps; delays are seconds. If fewer than two departures remain in the next 14 days, the response contains fewer than two results.

`GET /api/lines/{line}/status` returns current GTFS-Realtime service alerts that apply to the line. The same `lineStatus` object is included in each result from the trips-at-stop endpoint. `NO_PUBLISHED_ALERTS` means the alert feed currently contains no applicable published alerts; it is not a guarantee that service is running normally. `FEED_UNAVAILABLE` means no successful alert-feed snapshot is available.

```text
GET http://localhost:8080/api/lines/2/status
```

`GET /api/lines/{line}/directions` lists the directions for a line. Each result includes its display name and URL-encoded path value:

```text
GET http://localhost:8080/api/lines/2/directions
```

`GET /api/lines/{line}/direction/{direction}/stops` returns the ordered stops for only that direction:

```text
GET http://localhost:8080/api/lines/2/direction/LYCEE%20J.%20MONNET/stops
```

Each stop includes its sequence, ID, and name. An unknown line or direction returns `404 Not Found`.

## Configuration

Override the feed URLs or refresh interval with environment variables or Spring properties:

| Property | Default |
| --- | --- |
| `filbleu.trip-updates-url` | `https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/trip-updates` |
| `filbleu.service-alerts-url` | `https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/service-alerts` |
| `filbleu.static-gtfs-url` | Fil Bleu's `filbleu_gtfs.zip` export on data.tours-metropole.fr |
| `filbleu.refresh-interval-ms` | `30000` |
| `server.port` | `8080` (`PORT` environment variable can override it) |
