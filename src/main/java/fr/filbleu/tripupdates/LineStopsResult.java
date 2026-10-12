package fr.filbleu.tripupdates;

import java.util.List;

public record LineStopsResult(String line, List<DirectionStops> directions) {

    public record DirectionStops(String direction, List<Stop> stops) {
    }

    public record Stop(int stopSequence, String stopId, String stopName) {
    }
}
