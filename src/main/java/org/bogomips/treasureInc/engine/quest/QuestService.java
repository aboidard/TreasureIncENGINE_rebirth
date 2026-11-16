package org.bogomips.treasureInc.engine.quest;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.quest.QuestBoards.Board;
import org.bogomips.treasureInc.engine.quest.QuestBoards.Entry;
import org.bogomips.treasureInc.engine.quest.QuestBoards.State;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Definition;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Scope;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading the quests of a player and claiming the reward of those it completed: money and items.
 * A reward is never given by itself: the player has to claim it before its board is replaced.
 */
@ApplicationScoped
public class QuestService {

    private final QuestBoards boards;
    private final UserService users;
    private final ItemService items;
    private final UserService.Lock lock;

    public QuestService(QuestBoards boards, UserService users, ItemService items, UserService.Lock lock) {
        this.boards = boards;
        this.users = users;
        this.items = items;
        this.lock = lock;
    }

    /** The daily and weekly quests of the user; the state is null on bad credentials. */
    public Uni<State> list(String publicKey, String privateKey) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> user == null
                ? Uni.createFrom().<State>nullItem()
                : boards.currentLocked(publicKey)));
    }

    /** Gives the reward of a completed quest, once. */
    public Uni<ClaimResult> claim(String publicKey, String privateKey, String questId) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(ClaimResult.refused(ClaimResult.Status.UNAUTHORIZED));
            }
            return boards.currentLocked(publicKey).flatMap(state -> {
                Entry entry = state.entry(questId);
                if (entry == null) {
                    return Uni.createFrom().item(ClaimResult.refused(ClaimResult.Status.NOT_FOUND));
                }
                if (!entry.claimable()) {
                    return Uni.createFrom().item(ClaimResult.refused(entry.claimed()
                            ? ClaimResult.Status.ALREADY_CLAIMED : ClaimResult.Status.NOT_COMPLETED));
                }
                Definition quest = entry.quest();
                State claimed = state.claimed(questId);
                // the quest is marked first: a failure afterwards cannot give its reward twice
                return boards.saveLocked(publicKey, claimed)
                        .flatMap(v -> users.credit(publicKey, quest.money(), true))
                        .flatMap(paid -> items.generateLocked(publicKey, privateKey, quest.items())
                                .map(given -> new ClaimResult(ClaimResult.Status.OK, quest, paid.money(), given,
                                        claimed)));
            });
        }));
    }

    /**
     * What the client receives:
     * {"claimable": 1, "daily": {"period", "resetIn", "quests": [{"id", "metric", "goal", "progress", "completed",
     * "claimed", "reward": {"money", "items"}}]}, "weekly": {...}}
     */
    public Map<String, Object> toMap(State state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimable", state.claimable());
        for (Scope scope : Scope.values()) {
            Board board = state.board(scope);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("period", board.period());
            b.put("resetIn", boards.resetIn(scope));
            b.put("quests", board.quests().stream().map(QuestService::toMap).toList());
            m.put(scope.key(), b);
        }
        return m;
    }

    private static Map<String, Object> toMap(Entry entry) {
        Definition quest = entry.quest();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.id());
        m.put("metric", quest.metric().key());
        m.put("goal", quest.goal());
        m.put("progress", entry.progress());
        m.put("completed", entry.completed());
        m.put("claimed", entry.claimed());
        m.put("reward", reward(quest));
        return m;
    }

    static Map<String, Object> reward(Definition quest) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("money", quest.money());
        m.put("items", quest.items());
        return m;
    }

    // ---- values

    /**
     * quest is the quest claimed, money the balance afterwards, items the items given and state the boards
     * afterwards; all are null or empty unless OK.
     */
    public record ClaimResult(Status status, Definition quest, int money, List<ItemInfo> items, State state) {

        public enum Status { OK, UNAUTHORIZED, NOT_FOUND, NOT_COMPLETED, ALREADY_CLAIMED }

        static ClaimResult refused(Status status) {
            return new ClaimResult(status, null, 0, List.of(), null);
        }
    }
}
