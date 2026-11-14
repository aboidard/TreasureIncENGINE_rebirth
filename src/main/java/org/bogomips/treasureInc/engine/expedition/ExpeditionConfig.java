package org.bogomips.treasureInc.engine.expedition;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.Map;

/** Expedition settings ("engine.expeditions" in application.yml). A new location only needs a new line there. */
@ConfigMapping(prefix = "engine.expeditions")
public interface ExpeditionConfig {

    /** Number of cases of a location's grid; case ids are in [0, gridSize). */
    int gridSize();

    /** Highest difficulty (number of floors) an expedition can have; the lowest is 1. */
    int maxDifficulty();

    /** Most items a chest can hold. */
    int maxItems();

    /** Cost of one floor for a location of level 1. */
    int costPerFloor();

    /** How long the dug cases of a location are remembered when no expedition is sent. */
    Duration digTtl();

    /** How long a user's unopened expeditions are kept after the last one was sent. */
    Duration ttl();

    /** By lowercase location name. */
    Map<String, Location> locations();

    interface Location {
        int level();
    }
}
