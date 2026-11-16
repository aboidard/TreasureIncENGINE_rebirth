package org.bogomips.treasureInc.engine.character;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;
import org.bogomips.treasureInc.engine.item.Equipment;
import org.bogomips.treasureInc.engine.item.ItemInfo;
import org.bogomips.treasureInc.engine.item.ItemService;
import org.bogomips.treasureInc.engine.user.UserService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The characters of a user (see {@link CharacterCatalog} for who they are) and their equipment. An equipped item stays an item of the user, but it leaves the
 * inventory (see {@link ItemService#page}) until it is taken off. Every change is made in Redis under the user's
 * lock; the database is written by the next synchronization.
 */
@ApplicationScoped
public class CharacterService {

    private final CrewStore crews;
    private final ItemService items;
    private final UserService users;
    private final UserService.Lock lock;
    private final Activity activity;

    public CharacterService(CrewStore crews, ItemService items, UserService users, UserService.Lock lock,
                            Activity activity) {
        this.crews = crews;
        this.items = items;
        this.users = users;
        this.lock = lock;
        this.activity = activity;
    }

    /** Whether a character has this number; a user only has some of them (see {@link #ownsLocked}). */
    public boolean isValid(int num) {
        return CharacterCatalog.exists(num);
    }

    /** Whether the user has this character; false for an unknown user. Call it inside the user's lock. */
    public Uni<Boolean> ownsLocked(String publicKey, int num) {
        return crews.get(publicKey).map(crew -> crew != null && crew.owns(num));
    }

    /** How many characters the user has. Call it inside the user's lock, for a logged user. */
    public Uni<Integer> countLocked(String publicKey) {
        return crews.get(publicKey).map(crew -> crew == null ? 0 : crew.characters().size());
    }

    /** Every character of the user, with what it wears and the expedition it is on. */
    public Uni<Result> list(String publicKey, String privateKey) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> user == null
                ? Uni.createFrom().item(Result.of(Status.UNAUTHORIZED))
                : crews.get(publicKey).flatMap(crew -> views(publicKey, crew))));
    }

    /**
     * Puts an item of the inventory in a slot of a character; the item that was there goes back to the inventory.
     * Refused while the character is on an expedition.
     */
    public Uni<Result> equip(String publicKey, String privateKey, int num, int slot, int itemId) {
        return change(publicKey, privateKey, num, crew -> items.list(publicKey).map(list -> {
            ItemInfo item = list.items().stream().filter(i -> i.id() == itemId).findFirst().orElse(null);
            if (item == null || crew.equippedIds().contains(itemId)) {
                return Change.refused(Status.NOT_FOUND);
            }
            Integer itemSlot = Equipment.slot(item.stats());
            return itemSlot == null || itemSlot != slot ? Change.refused(Status.WRONG_SLOT)
                    : Change.to(crew.with(num, slot, itemId));
        }));
    }

    /** Empties a slot of a character: the item goes back to the inventory. Nothing happens if the slot is empty. */
    public Uni<Result> unequip(String publicKey, String privateKey, int num, int slot) {
        return change(publicKey, privateKey, num, crew -> Uni.createFrom().item(Change.to(crew.with(num, slot, null))));
    }

    /** The items a character wears. Call it inside the user's lock, for a logged user. */
    public Uni<List<ItemInfo>> equippedLocked(String publicKey, int num) {
        return crews.get(publicKey).flatMap(crew -> {
            if (crew == null || crew.equipment(num).isEmpty()) {
                return Uni.createFrom().item(List.<ItemInfo>of());
            }
            return items.list(publicKey).map(list -> List.copyOf(worn(crew.equipment(num), list.items()).values()));
        });
    }

    private Uni<Result> change(String publicKey, String privateKey, int num,
                               Function<Crew, Uni<Change>> decide) {
        return lock.withLock(publicKey, () -> users.openSession(publicKey, privateKey).flatMap(user -> {
            if (user == null) {
                return Uni.createFrom().item(Result.of(Status.UNAUTHORIZED));
            }
            return activity.expeditionsByCharacter(publicKey).flatMap(busy -> {
                if (busy.containsKey(num)) {
                    return Uni.createFrom().item(Result.of(Status.BUSY));
                }
                return crews.get(publicKey).flatMap(crew -> !crew.owns(num)
                        ? Uni.createFrom().item(Result.of(Status.UNKNOWN_CHARACTER))
                        : decide.apply(crew).flatMap(change -> {
                    if (change.status() != Status.OK) {
                        return Uni.createFrom().item(Result.of(change.status()));
                    }
                    return crews.save(publicKey, change.crew())
                            .flatMap(v -> users.markDirty(publicKey))
                            .flatMap(v -> views(publicKey, change.crew()));
                }));
            });
        }));
    }

    private Uni<Result> views(String publicKey, Crew crew) {
        return viewsLocked(publicKey, crew);
    }

    /** Every character of the crew, by number. Call it inside the user's lock. */
    Uni<Result> viewsLocked(String publicKey, Crew crew) {
        return items.list(publicKey).flatMap(list -> activity.expeditionsByCharacter(publicKey).map(busy -> {
            List<CharacterView> views = new ArrayList<>();
            for (int num : new TreeSet<>(crew.characters().keySet())) {
                CharacterCatalog.Info info = CharacterCatalog.get(num);
                if (info != null) {
                    views.add(new CharacterView(info, crew.copies(num), busy.get(num),
                            worn(crew.equipment(num), list.items())));
                }
            }
            return new Result(Status.OK, views);
        }));
    }

    /** The items by slot; a slot whose item no longer exists is left out. */
    private static Map<Integer, ItemInfo> worn(Map<Integer, Integer> equipment, List<ItemInfo> items) {
        Map<Integer, ItemInfo> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        Map<Integer, ItemInfo> worn = new TreeMap<>();
        equipment.forEach((slot, id) -> {
            ItemInfo item = byId.get(id);
            if (item != null) {
                worn.put(slot, item);
            }
        });
        return worn;
    }

    // ---- values

    /** NOT_FOUND is about the item; UNKNOWN_CHARACTER is a character the user does not have. */
    public enum Status { OK, UNAUTHORIZED, NOT_FOUND, WRONG_SLOT, BUSY, UNKNOWN_CHARACTER }

    /**
     * A character of a user as shown to the client: who it is, how many times the user obtained it, and what it
     * wears; expeditionId is null unless the character is on an expedition.
     */
    public record CharacterView(CharacterCatalog.Info info, int copies, String expeditionId,
                                Map<Integer, ItemInfo> equipment) {

        public int num() {
            return info.num();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> worn = new TreeMap<>();
            equipment.forEach((slot, item) -> worn.put(String.valueOf(slot), item.toMap()));
            Map<String, Object> m = new LinkedHashMap<>(info.toMap());
            m.put("copies", copies);
            m.put("expeditionId", expeditionId);
            m.put("equipment", worn);
            return m;
        }
    }

    /** The characters are every character of the user, after the change if there was one; empty unless OK. */
    public record Result(Status status, List<CharacterView> characters) {

        static Result of(Status status) {
            return new Result(status, List.of());
        }
    }

    private record Change(Status status, Crew crew) {

        static Change refused(Status status) {
            return new Change(status, null);
        }

        static Change to(Crew crew) {
            return new Change(Status.OK, crew);
        }
    }

    // ---- port, implemented by the expedition domain

    public interface Activity {
        /** The id of the expedition under way of each busy character, by character number. */
        Uni<Map<Integer, String>> expeditionsByCharacter(String publicKey);
    }
}
