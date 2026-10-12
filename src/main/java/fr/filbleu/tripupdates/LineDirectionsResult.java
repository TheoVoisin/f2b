package fr.filbleu.tripupdates;

import java.util.List;

public record LineDirectionsResult(String line, List<Direction> directions) {

    public record Direction(String name, String encodedDirection) {
    }
}
