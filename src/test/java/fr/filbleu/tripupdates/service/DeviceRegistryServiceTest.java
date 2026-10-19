package fr.filbleu.tripupdates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.filbleu.tripupdates.api.dto.Device;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DeviceRegistryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsRegistrationSettingsAndDeletion() {
        Path storageFile = temporaryDirectory.resolve("devices.json");
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        DeviceRegistryService registry = new DeviceRegistryService(new ObjectMapper(), storageFile.toString());

        assertEquals(uuid, registry.register(uuid).uuid());
        assertEquals(uuid.toString(), registry.find(uuid).name());
        assertFalse(registry.find(uuid).isConfigured());

        Device configured = registry.update(
                uuid,
                "Lobby display",
                "2",
                "LYCEE J. MONNET",
                "TTR:GATO-2T",
                "37000",
                java.util.List.of("TTR:STOP-2", "TTR:STOP-3"));
        assertTrue(configured.isConfigured());
        assertEquals(java.util.List.of("TTR:STOP-2", "TTR:STOP-3"), configured.downstreamStopIds());

        DeviceRegistryService reloaded = new DeviceRegistryService(new ObjectMapper(), storageFile.toString());
        assertEquals(configured, reloaded.find(uuid));

        reloaded.delete(uuid);
        DeviceRegistryService afterDeletion = new DeviceRegistryService(new ObjectMapper(), storageFile.toString());
        assertTrue(afterDeletion.findAll().isEmpty());
    }

    @Test
    void rejectsDuplicateRegistrationAndInvalidSettings() {
        Path storageFile = temporaryDirectory.resolve("devices.json");
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        DeviceRegistryService registry = new DeviceRegistryService(new ObjectMapper(), storageFile.toString());
        registry.register(uuid);

        ResponseStatusException duplicate = assertThrows(
                ResponseStatusException.class,
                () -> registry.register(uuid));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());

        ResponseStatusException invalidPostalCode = assertThrows(
                ResponseStatusException.class,
                () -> registry.update(uuid, "Display", "2", "Direction", "Stop", "3700"));
        assertEquals(HttpStatus.BAD_REQUEST, invalidPostalCode.getStatusCode());

        ResponseStatusException missingName = assertThrows(
                ResponseStatusException.class,
                () -> registry.update(uuid, " ", "2", "Direction", "Stop", "37000"));
        assertEquals(HttpStatus.BAD_REQUEST, missingName.getStatusCode());

        ResponseStatusException tooManyDownstreamStops = assertThrows(
                ResponseStatusException.class,
                () -> registry.update(
                        uuid,
                        "Display",
                        "2",
                        "Direction",
                        "Stop",
                        "37000",
                        java.util.List.of("Stop-2", "Stop-3", "Stop-4")));
        assertEquals(HttpStatus.BAD_REQUEST, tooManyDownstreamStops.getStatusCode());
    }

    @Test
    void assignsUuidAsNameWhenLoadingLegacyDeviceRecords() throws IOException {
        Path storageFile = temporaryDirectory.resolve("legacy-devices.json");
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Files.writeString(
                storageFile,
                """
                {
                  "%s": {
                    "uuid": "%s",
                    "line": "2",
                    "direction": "Direction",
                    "stopId": "Stop",
                    "postalCode": "37000"
                  }
                }
                """.formatted(uuid, uuid));

        DeviceRegistryService registry = new DeviceRegistryService(new ObjectMapper(), storageFile.toString());

        assertEquals(uuid.toString(), registry.find(uuid).name());
        assertTrue(registry.find(uuid).isConfigured());
        assertTrue(registry.find(uuid).downstreamStopIds().isEmpty());
    }
}
