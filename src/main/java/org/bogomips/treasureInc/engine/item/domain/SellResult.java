package org.bogomips.treasureInc.engine.item.domain;

import java.util.List;

public record SellResult(Status status, int totalPrice, int money, List<Integer> soldIds) {

    public enum Status { OK, UNAUTHORIZED, NOT_FOUND }

    public static SellResult of(Status status) {
        return new SellResult(status, 0, 0, List.of());
    }
}
