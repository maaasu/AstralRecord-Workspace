package io.github.maaasu.astralRecord.feature.inventory.model;

import org.jetbrains.annotations.NotNull;

public enum AccessorySlotType {
    OFF_HAND(1, "オフハンド"),
    NECKLACE(2, "首飾り"),
    RING(3, "指輪"),
    EARRING(4, "耳飾り"),
    BRACELET(5, "腕輪"),
    BELT(6, "ベルト"),
    CHARM(7, "護符");

    private final int slotIndex;
    private final String displayName;

    AccessorySlotType(int slotIndex, @NotNull String displayName) {
        this.slotIndex = slotIndex;
        this.displayName = displayName;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public @NotNull String getDisplayName() {
        return displayName;
    }

    public static @NotNull AccessorySlotType fromSlotIndex(int slotIndex) {
        for (AccessorySlotType type : values()) {
            if (type.slotIndex == slotIndex) {
                return type;
            }
        }
        return CHARM;
    }
}
