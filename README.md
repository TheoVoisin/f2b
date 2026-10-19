# Fil Bleu Box

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

The administration space is available at `http://localhost:8080/` and requires a login. The `admin` account can manage devices and browse lines. The login page also provides a **Visualisation** button to browse lines and stops without signing in; this read-only access does not include device management. The admin credentials are configured by `filbleu.security.users.admin.*` in `application.yml`; override them for deployments rather than using the development defaults.

In VS Code, install the recommended Java Extension Pack if prompted, then select the `FilBleuApplication` launch configuration and press `F5` to run or debug the server.

## Docker

Build the image from the project root:

```powershell
docker build -t fil-bleu-box .
```

Run it with the device registry persisted in the host's `data` directory and an optional external `config/application.yml` mounted read-only:

```powershell
New-Item -ItemType Directory -Force config | Out-Null
docker run --name fil-bleu-box -p 8080:8080 `
  --mount "type=bind,source=$((Get-Location).Path)\data,target=/app/data" `
  --mount "type=bind,source=$((Get-Location).Path)\config,target=/app/config,readonly" `
  fil-bleu-box
```

The application reads and writes `/app/data/devices.json`. The external `/app/config/application.yml`, when present, takes precedence over the configuration packaged in the application; copy `src/main/resources/application.yml` there and edit it to configure the deployment. Keep credentials and other deployment-specific settings in this external file rather than rebuilding the image.

### Nginx reverse proxy

The application honors the standard `Forwarded` and `X-Forwarded-*` headers, so Nginx can terminate HTTPS and forward requests to the container. When Nginx runs directly on the Docker host, publish the application port only on loopback:

```powershell
docker run --name fil-bleu-box -p 127.0.0.1:8080:8080 `
  --mount "type=bind,source=$((Get-Location).Path)\data,target=/app/data" `
  --mount "type=bind,source=$((Get-Location).Path)\config,target=/app/config,readonly" `
  --env FILBLEU_SECURITY_USERS_ADMIN_USERNAME `
  --env FILBLEU_SECURITY_USERS_ADMIN_PASSWORD `
  fil-bleu-box
```

Set `FILBLEU_SECURITY_USERS_ADMIN_USERNAME` and `FILBLEU_SECURITY_USERS_ADMIN_PASSWORD` in the environment before running the command; Spring environment properties override the mounted YAML configuration. Do not expose port `8080` publicly when Nginx is the public entry point.

Example Nginx virtual host for an application served at the domain root:

```nginx
server {
    listen 443 ssl;
    server_name filbleu.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
    }
}
```

If Nginx also runs in Docker, attach both containers to a private Docker network instead of publishing the application port, then proxy to `http://fil-bleu-box:8080`. Keep the application reachable only by the trusted proxy so clients cannot send spoofed forwarded headers directly.

## Bruno collection

Open the collection in `bruno/fil-bleu-api` with Bruno. The collection-level variables are defined in `collection.bru`; edit them to change the host, line, direction, or stop. No environment needs to be selected.

## API

`GET /api/lines/{line}/direction/{direction}/stops/{stopId}/trips` returns the next two scheduled departures for an exact stop on a line and direction. Realtime times and delays are included when a matching live update is available; otherwise the realtime fields are `null`.

```text
GET http://localhost:8080/api/lines/2/direction/LYCEE%20J.%20MONNET/stops/TTR%3AGATO-2T/trips
```

The response is a JSON array containing scheduled arrival/departure times and optional realtime arrival/departure times and delays. Times are UTC ISO-8601 timestamps; delays are seconds. If fewer than two departures remain in the next 14 days, the response contains fewer than two results.

`GET /api/lines/{line}/status` returns current GTFS-Realtime service alerts that apply to the line. The same `lineStatus` object is included in each result from the trips-at-stop endpoint. The administration page displays the line status and any active alerts when a direction is selected, lets you filter the stop list to a single stop, and provides a button to refresh the displayed line status and upcoming passages. Realtime passage times are highlighted; delays are shown in yellow. `NO_PUBLISHED_ALERTS` means the alert feed currently contains no applicable published alerts; it is not a guarantee that service is running normally. `FEED_UNAVAILABLE` means no successful alert-feed snapshot is available.

```text
GET http://localhost:8080/api/lines/2/status
```

`GET /api/lines` lists the line names available in the static GTFS catalogue. The administration page at `/` includes a **Lignes et arrêts** tab for browsing these lines, their directions, and the ordered stops in each direction.

`GET /api/lines/{line}/directions` lists the directions for a line. Each result includes its display name and URL-encoded path value:

```text
GET http://localhost:8080/api/lines/2/directions
```

`GET /api/lines/{line}/direction/{direction}/stops` returns the ordered stops for only that direction:

```text
GET http://localhost:8080/api/lines/2/direction/LYCEE%20J.%20MONNET/stops
```

Each stop includes its sequence, ID, and name. An unknown line or direction returns `404 Not Found`.

`GET /api/lines/{line}/direction/{direction}/stops/with-trips` returns the same ordered stops along with up to two upcoming passages at each stop. Passage results include scheduled and available realtime times and delays.

`GET /api/weather/{postalCode}` returns current conditions and today's morning, afternoon, and evening forecast for a French five-digit postal code:

```text
GET http://localhost:8080/api/weather/37000
```

The location is resolved to the first commune returned by the French government's commune service when a postal code covers multiple communes. Forecast data comes from Open-Meteo. Weather descriptions are translated to French by the weather description translation service. Periods use local time: morning is 06:00-12:00, afternoon is 12:00-18:00, and evening is 18:00-00:00. Each period reports average/minimum/maximum temperature, the most common weather code and French description, and the maximum precipitation probability. Invalid postal codes return `400 Bad Request`, unknown postal codes return `404 Not Found`, and upstream service failures return `502 Bad Gateway`.

### Device API

Devices can check connectivity with `GET /api/devices/ping`, which returns `{"status":"ok"}`.

Register a device by sending its UUID:

```text
POST /api/devices
Content-Type: application/json

{"uuid":"550e8400-e29b-41d4-a716-446655440000"}
```

The device registry supports `GET /api/devices`, `GET /api/devices/{uuid}`, `PUT /api/devices/{uuid}`, and `DELETE /api/devices/{uuid}`; these management routes require the `admin` account. `GET /api/devices/ping`, `GET /api/devices/{uuid}/infos`, and `GET /api/devices/{uuid}/display` remain available without login for device clients. `PUT` replaces the device's query settings:

```json
{
  "name": "Lobby display",
  "line": "2",
  "direction": "LYCEE J. MONNET",
  "stopId": "TTR:GATO-2T",
  "postalCode": "37000",
  "downstreamStopIds": ["TTR:STOP-2", "TTR:STOP-3"]
}
```

`downstreamStopIds` is optional and accepts up to two distinct stops in route order, after the configured main stop in the same direction. After settings are saved, `GET /api/devices/{uuid}/infos` returns a combined object containing the device (including its `name` and downstream stops), the next two trips at the configured main stop, their theoretical and available realtime arrival times at each configured downstream stop, the line's status, and the postal-code weather forecast. A device without all required settings receives `409 Conflict`; invalid downstream-stop selections return `400 Bad Request`; unknown UUIDs return `404 Not Found`, and duplicate registrations return `409 Conflict`. Newly registered devices use their UUID as the default name until settings are updated. Device data is stored in `./data/devices.json` by default. Set `FILBLEU_DEVICES_FILE` (or the `filbleu.devices-file` property) to change the file location.

`GET /api/devices/{uuid}/display` returns a 400×300, black-and-white PNG for an e-paper screen. It shows the two next arrival times at the configured main stop and, when configured, their arrival times at up to two downstream stops, using realtime times when available and theoretical times otherwise. A known realtime delay or advance for the next passage is shown in its own panel beside the line alerts, rather than inline with the passage times. It also shows current weather and the morning, afternoon, and evening forecast, each with a weather pictogram based on its weather code. When the line has active alerts, the image includes the first alert and the number of active alerts. The endpoint uses the same configuration checks and upstream data as `/infos`.

## Configuration

Override the feed URLs, weather service URLs, refresh interval, or device registry file with environment variables or Spring properties:

| Property | Default |
| --- | --- |
| `filbleu.trip-updates-url` | `https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/trip-updates` |
| `filbleu.service-alerts-url` | `https://data.filbleu.fr/ws-tr/gtfs-rt/opendata/service-alerts` |
| `filbleu.static-gtfs-url` | Fil Bleu's `filbleu_gtfs.zip` export on data.tours-metropole.fr |
| `filbleu.refresh-interval-ms` | `30000` |
| `weather.geocoding-url` | `https://geo.api.gouv.fr/communes` |
| `weather.forecast-url` | `https://api.open-meteo.com/v1/forecast` |
| `filbleu.devices-file` | `./data/devices.json` (`FILBLEU_DEVICES_FILE` environment variable can override it) |
| `filbleu.security.users.admin.username` | `admin` |
| `filbleu.security.users.admin.password` | Defined in `application.yml` |
| `server.port` | `8080` (`PORT` environment variable can override it) |
| `server.forward-headers-strategy` | `framework` (honors reverse-proxy forwarded headers) |
