package org.bogomips.treasureInc.engine.item.domain;

import java.time.Instant;
import java.util.random.RandomGenerator;

/** Creates random items. The description is kept as an id (see {@link ItemTexts}). */
public class ItemGenerator {

    private final RandomGenerator random;

    public ItemGenerator(RandomGenerator random) {
        this.random = random;
    }

    public ItemInfo generate(int id) {
        Rarity rarity = Rarity.fromRoll(random.nextInt(1000) + 1);
        int price = (random.nextInt(1000) + 1000) * rarity.coef();
        String now = Instant.now().toString();
        return new ItemInfo(id, ItemTexts.itemName(rarity, random), null, null, price, rarity.value(), now, now,
                ItemTexts.randomDescriptionId(rarity, random));
    }
}
