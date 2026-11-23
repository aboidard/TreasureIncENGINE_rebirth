package org.bogomips.treasureInc.engine.expedition;

import io.smallrye.config.ConfigMapping;

import java.time.Duration;
import java.util.List;
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

    /** Experience points of one floor for a location of level 1, before the bonus of the rarity. */
    int xpPerFloor();

    /** How long the dug cases of a location are remembered when no expedition is sent. */
    Duration digTtl();

    /** How long a user's unopened expeditions are kept after the last one was sent. */
    Duration ttl();

    Obstacles obstacles();

    /** By lowercase location name. */
    Map<String, Location> locations();

    interface Location {
        /** The difficulty of the location, from 1: what is met there grows with it. A location of level 1 is free. */
        int level();

        /**
         * The chances of each item rarity in the chests brought back from there, in per mille, from common to
         * legendary: five numbers adding up to 1000.
         */
        List<Integer> loot();
    }

    /** What may stand in the way of a character on each floor (see ExpeditionRun). The chances are in percent. */
    interface Obstacles {
        /** How many spots of a floor may hold an obstacle. */
        int spots();

        /** Chance that a spot holds a barricade. */
        int barricadeChance();

        /** Chance that a spot holds a monster; with the chance of a barricade, 100 at most. */
        int monsterChance();

        /** Chance that the chest of a floor is kept by a monster. */
        int guardianChance();
    }
}
