package org.bogomips.treasureInc.engine.item;

import java.util.List;

public record ItemPage(List<ItemInfo> items, int page, int limit, int total) {
}
