package fr.filbleu.tripupdates.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.filbleu.tripupdates.api.dto.Device;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryService.class);
    private static final TypeReference<Map<UUID, Device>> DEVICE_MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path storageFile;
    private final Map<UUID, Device> devices = new LinkedHashMap<>();

    public DeviceRegistryService(
            ObjectMapper objectMapper,
            @Value("${filbleu.devices-file:./data/devices.json}") String storageFile) {
        this.objectMapper = objectMapper;
        this.storageFile = Path.of(storageFile);
        load();
    }

    public synchronized List<Device> findAll() {
        return List.copyOf(devices.values());
    }

    public synchronized Device find(UUID uuid) {
        Device device = devices.get(uuid);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found: " + uuid);
        }
        return device;
    }

    public synchronized Device register(UUID uuid) {
        if (uuid == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A device UUID is required");
        }
        if (devices.containsKey(uuid)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device already registered: " + uuid);
        }

        Device device = Device.registered(uuid);
        Map<UUID, Device> updatedDevices = new LinkedHashMap<>(devices);
        updatedDevices.put(uuid, device);
        persist(updatedDevices);
        devices.put(uuid, device);
        return device;
    }

    public synchronized Device update(
            UUID uuid,
            String name,
            String line,
            String direction,
            String stopId,
            String postalCode) {
        return update(uuid, name, line, direction, stopId, postalCode, List.of());
    }

    public synchronized Device update(
            UUID uuid,
            String name,
            String line,
            String direction,
            String stopId,
            String postalCode,
            List<String> downstreamStopIds) {
        find(uuid);
        requireText(name, "name");
        requireText(line, "line");
        requireText(direction, "direction");
        requireText(stopId, "stopId");
        if (postalCode == null || !postalCode.matches("\\d{5}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A French five-digit postal code is required");
        }

        List<String> normalizedDownstreamStopIds = downstreamStopIds == null
                ? List.of()
                : downstreamStopIds.stream()
                        .map(value -> value == null ? "" : value.trim())
                        .filter(value -> !value.isEmpty())
                        .toList();
        if (normalizedDownstreamStopIds.size() > 2
                || normalizedDownstreamStopIds.stream().distinct().count() != normalizedDownstreamStopIds.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "At most two distinct downstream stops may be configured");
        }

        Device device = new Device(
                uuid,
                name.trim(),
                line.trim(),
                direction.trim(),
                stopId.trim(),
                postalCode,
                normalizedDownstreamStopIds);
        Map<UUID, Device> updatedDevices = new LinkedHashMap<>(devices);
        updatedDevices.put(uuid, device);
        persist(updatedDevices);
        devices.put(uuid, device);
        return device;
    }

    public synchronized void delete(UUID uuid) {
        find(uuid);
        Map<UUID, Device> updatedDevices = new LinkedHashMap<>(devices);
        updatedDevices.remove(uuid);
        persist(updatedDevices);
        devices.remove(uuid);
    }

    private void load() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            Map<UUID, Device> storedDevices = objectMapper.readValue(storageFile.toFile(), DEVICE_MAP_TYPE);
            if (storedDevices == null) {
                throw new IOException("The device registry JSON must contain an object");
            }
            for (Map.Entry<UUID, Device> entry : storedDevices.entrySet()) {
                Device device = entry.getValue();
                if (entry.getKey() == null || device == null || !entry.getKey().equals(device.uuid())) {
                    throw new IOException("The device registry contains an invalid UUID/device entry");
                }
                if (device.name() == null || device.name().isBlank()) {
                    device = new Device(
                            device.uuid(),
                            device.uuid().toString(),
                            device.line(),
                            device.direction(),
                            device.stopId(),
                            device.postalCode(),
                            device.downstreamStopIds());
                }
                devices.put(entry.getKey(), device);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load device registry from " + storageFile, exception);
        }
    }

    private void persist(Map<UUID, Device> updatedDevices) {
        Path absoluteFile = storageFile.toAbsolutePath();
        Path parent = absoluteFile.getParent();
        Path temporaryFile = null;
        try {
            Files.createDirectories(parent);
            String prefix = absoluteFile.getFileName().toString();
            if (prefix.length() < 3) {
                prefix = (prefix + "___").substring(0, 3);
            }
            temporaryFile = Files.createTempFile(parent, prefix, ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), updatedDevices);
            try {
                Files.move(
                        temporaryFile,
                        absoluteFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, absoluteFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist device registry to " + storageFile, exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    log.warn("Unable to remove temporary device registry file {}", temporaryFile, exception);
                }
            }
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required");
        }
    }
}
