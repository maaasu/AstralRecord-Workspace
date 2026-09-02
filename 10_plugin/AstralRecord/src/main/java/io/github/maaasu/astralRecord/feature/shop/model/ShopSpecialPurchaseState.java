package io.github.maaasu.astralRecord.feature.shop.model;

import org.jetbrains.annotations.NotNull;

/** 通常の商品付与とは別に、購入後へ直結する効果と購入可否を表します。 */
public record ShopSpecialPurchaseState(
    @NotNull Action action,
    int currentLevel,
    int nextLevel,
    int maxLevel
) {
    private static final ShopSpecialPurchaseState STANDARD =
        new ShopSpecialPurchaseState(Action.STANDARD, 0, 0, 0);

    public static @NotNull ShopSpecialPurchaseState standard() {
        return STANDARD;
    }

    public static @NotNull ShopSpecialPurchaseState learn(int maxLevel) {
        return new ShopSpecialPurchaseState(Action.SKILL_LEARN, 0, 1, Math.max(1, maxLevel));
    }

    public static @NotNull ShopSpecialPurchaseState levelUp(int currentLevel, int maxLevel) {
        int safeMaxLevel = Math.max(1, maxLevel);
        int safeCurrentLevel = Math.max(1, Math.min(currentLevel, safeMaxLevel));
        return new ShopSpecialPurchaseState(
            Action.SKILL_LEVEL_UP, safeCurrentLevel, Math.min(safeMaxLevel, safeCurrentLevel + 1), safeMaxLevel
        );
    }

    public static @NotNull ShopSpecialPurchaseState maxLevel(int maxLevel) {
        int safeMaxLevel = Math.max(1, maxLevel);
        return new ShopSpecialPurchaseState(Action.SKILL_MAX_LEVEL, safeMaxLevel, safeMaxLevel, safeMaxLevel);
    }

    public static @NotNull ShopSpecialPurchaseState processing() {
        return new ShopSpecialPurchaseState(Action.PROCESSING, 0, 0, 0);
    }

    public static @NotNull ShopSpecialPurchaseState unavailable() {
        return new ShopSpecialPurchaseState(Action.UNAVAILABLE, 0, 0, 0);
    }

    public boolean special() {
        return action != Action.STANDARD;
    }

    public boolean canPurchase() {
        return action == Action.STANDARD || action == Action.SKILL_LEARN || action == Action.SKILL_LEVEL_UP;
    }

    public boolean singleQuantity() {
        return special();
    }

    public enum Action {
        STANDARD,
        SKILL_LEARN,
        SKILL_LEVEL_UP,
        SKILL_MAX_LEVEL,
        PROCESSING,
        UNAVAILABLE
    }
}
