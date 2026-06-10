package io.github.maaasu.astralRecord.feature.trade.gui;

import java.util.List;
import java.util.Set;

public final class TradeGuiLayout {
    public static final int SIZE = 54;
    public static final int READY_SLOT = 49;
    public static final List<Integer> OWN_SLOT_LIST = List.of(
        0, 1, 2, 3,
        9, 10, 11, 12,
        18, 19, 20, 21,
        27, 28, 29, 30,
        36, 37, 38, 39,
        45, 46, 47, 48
    );
    public static final List<Integer> PARTNER_SLOT_LIST = List.of(
        5, 6, 7, 8,
        14, 15, 16, 17,
        23, 24, 25, 26,
        32, 33, 34, 35,
        41, 42, 43, 44,
        50, 51, 52, 53
    );
    public static final Set<Integer> OWN_SLOTS = Set.copyOf(OWN_SLOT_LIST);
    public static final Set<Integer> PARTNER_SLOTS = Set.copyOf(PARTNER_SLOT_LIST);
    public static final Set<Integer> DIVIDER_SLOTS = Set.of(4, 13, 22, 31, 40);

    private TradeGuiLayout() {
    }
}
