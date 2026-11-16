package org.bogomips.treasureInc.engine.character;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Info;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Portal;
import org.bogomips.treasureInc.engine.character.CharacterCatalog.Rank;
import org.bogomips.treasureInc.engine.character.CharacterService.CharacterView;
import org.bogomips.treasureInc.engine.character.CrewStore.Crew;
import org.bogomips.treasureInc.engine.quest.QuestCatalog.Metric;
import org.bogomips.treasureInc.engine.quest.QuestTracker;
import org.bogomips.treasureInc.engine.user.UserService;
import org.bogomips.treasureInc.engine.user.UserService.SpendResult;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Summons characters from a portal: the user pays, and each draw gives a character of the portal without knowing
 * which one beforehand. A character the user does not have joins its crew; another one is a duplicate, which is
 * only counted. Like every change of the crew, it is made in Redis under the user's lock.
 */
@ApplicationScoped
public class RecruitService {

    private final CrewStore crews;
    private final CharacterService characters;
    private final UserService users;
    private final UserService.Lock lock;
    private final int cost;
    private final int multi;
    private final RandomGenerator random;
    private final QuestTracker quests;

    @Inject
    public RecruitService(CrewStore crews, CharacterService characters, UserService users, UserService.Lock lock,
                          @ConfigProperty(name = "engine.recruitment.cost", defaultValue = "1000") int cost,
                          @ConfigProperty(name = "engine.recruitment.multi", defaultValue = "10") int multi,
                          QuestTracker quests) {
        this(crews, characters, users, lock, cost, multi, RandomGenerator.getDefault(), quests);
    }

    /** A recruitment that counts nothing for the quests. */
    RecruitService(CrewStore crews, CharacterService characters, UserService users, UserService.Lock lock,
                   int cost, int multi, RandomGenerator random) {
        this(crews, characters, users, lock, cost, multi, random, QuestTracker.NONE);
    }

    RecruitService(CrewStore crews, CharacterService characters, UserService users, UserService.Lock lock,
                   int cost, int multi, RandomGenerator random, QuestTracker quests) {
        this.quests = quests;
        if (cost < 0 || multi < 2) {
            throw new IllegalArgumentException("engine.recruitment: cost cannot be negative and multi is at least 2");
        }
        this.crews = crews;
        this.characters = characters;
        this.users = users;
        this.lock = lock;
        this.cost = cost;
        this.multi = multi;
        this.random = random;
    }

    /** Price of one draw. */
    public int cost() {
        return cost;
    }

    /** How many draws the "several draws at once" request makes. */
    public int multi() {
        return multi;
    }

    /** A request draws either once or {@link #multi} times. */
    public boolean isValidCount(int count) {
        return count == 1 || count == multi;
    }

    /** Pays for {@code count} draws in the portal and adds the characters drawn to the crew of the user. */
    public Uni<Result> recruit(String publicKey, String privateKey, Portal portal, int count) {
        int price = Math.multiplyExact(cost, count);
        return lock.withLock(publicKey, () -> users.spendLocked(publicKey, privateKey, price).flatMap(spent -> {
            if (spent.status() != SpendResult.Status.OK) {
                return Uni.createFrom().item(new Result(spent.status(), List.of(), spent.money(), List.of()));
            }
            return crews.get(publicKey).flatMap(crew -> {
                Crew updated = crew;
                List<Draw> draws = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Info drawn = draw(portal);
                    boolean isNew = !updated.owns(drawn.num());
                    updated = updated.withRecruit(drawn.num());
                    draws.add(new Draw(drawn, isNew, updated.copies(drawn.num())));
                }
                Crew saved = updated;
                return crews.save(publicKey, saved)
                        .flatMap(v -> users.markDirty(publicKey))
                        .flatMap(v -> quests.addLocked(publicKey, Map.of(Metric.RECRUITS, count)))
                        .flatMap(v -> characters.viewsLocked(publicKey, saved))
                        .map(views -> new Result(SpendResult.Status.OK, draws, spent.money(), views.characters()));
            });
        }));
    }

    /** One draw: the rank first, by its weight, then any character of the portal with this rank. */
    Info draw(Portal portal) {
        List<Info> candidates = portal.ofRank(Rank.fromRoll(random.nextInt(Rank.TOTAL_WEIGHT)));
        return candidates.get(random.nextInt(candidates.size()));
    }

    // ---- values

    /** A character drawn; copies is how many times the user has it so far, this draw included. */
    public record Draw(Info character, boolean isNew, int copies) {
    }

    /**
     * Money is the balance afterwards (unchanged unless OK). The draws are in the order they were made, and the
     * characters are every character of the user afterwards; both are empty unless OK.
     */
    public record Result(SpendResult.Status status, List<Draw> draws, int money, List<CharacterView> characters) {
    }
}
