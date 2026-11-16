package org.bogomips.treasureInc.engine.quest;

import io.smallrye.mutiny.Uni;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;

import java.util.Map;

/** Where the other domains report what a player just did, so its quests move forward. */
public interface QuestTracker {

    /** Counts nothing: for the services built without quests. */
    QuestTracker NONE = (publicKey, amounts) -> Uni.createFrom().voidItem();

    /**
     * Adds the amounts to the quests of the user that count these metrics.
     * Call it inside the user's lock, once the action is done.
     */
    Uni<Void> addLocked(String publicKey, Map<Metric, Integer> amounts);
}
