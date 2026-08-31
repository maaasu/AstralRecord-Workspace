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

    /** @return 通常商品を表す共有state */
    public static @NotNull ShopSpecialPurchaseState standard() {
        return STANDARD;
    }

    /**
     * @param maxLevel 対象スキルの最大レベル
     * @return 初回購入で習得するstate
     */
    public static @NotNull ShopSpecialPurchaseState learn(int maxLevel) {
        return new ShopSpecialPurchaseState(Action.SKILL_LEARN, 0, 1, Math.max(1, maxLevel));
    }

    /**
     * @param currentLevel 購入前レベル
     * @param maxLevel 対象スキルの最大レベル
     * @return 再購入で1レベル上げるstate
     */
    public static @NotNull ShopSpecialPurchaseState levelUp(int currentLevel, int maxLevel) {
        int safeMaxLevel = Math.max(1, maxLevel);
        int safeCurrentLevel = Math.max(1, Math.min(currentLevel, safeMaxLevel));
        return new ShopSpecialPurchaseState(
            Action.SKILL_LEVEL_UP,
            safeCurrentLevel,
            Math.min(safeMaxLevel, safeCurrentLevel + 1),
            safeMaxLevel
        );
    }

    /**
     * @param maxLevel 対象スキルの最大レベル
     * @return 最大レベルのため購入を拒否するstate
     */
    public static @NotNull ShopSpecialPurchaseState maxLevel(int maxLevel) {
        int safeMaxLevel = Math.max(1, maxLevel);
        return new ShopSpecialPurchaseState(Action.SKILL_MAX_LEVEL, safeMaxLevel, safeMaxLevel, safeMaxLevel);
    }

    /** @return 前回購入の反映中であるstate */
    public static @NotNull ShopSpecialPurchaseState processing() {
        return new ShopSpecialPurchaseState(Action.PROCESSING, 0, 0, 0);
    }

    /** @return 習得状態を解決できず購入を拒否するstate */
    public static @NotNull ShopSpecialPurchaseState unavailable() {
        return new ShopSpecialPurchaseState(Action.UNAVAILABLE, 0, 0, 0);
    }

    /** @return 通常の商品付与以外の購入効果である場合は {@code true} */
    public boolean special() {
        return action != Action.STANDARD;
    }

    /** @return 現在の状態で購入を確定できる場合は {@code true} */
    public boolean canPurchase() {
        return action == Action.STANDARD || action == Action.SKILL_LEARN || action == Action.SKILL_LEVEL_UP;
    }

    /** @return 数量変更を禁止し、一回ずつ購入する商品である場合は {@code true} */
    public boolean singleQuantity() {
        return special();
    }

    /** 購入時に適用する追加効果の種別です。 */
    public enum Action {
        STANDARD,
        SKILL_LEARN,
        SKILL_LEVEL_UP,
        SKILL_MAX_LEVEL,
        PROCESSING,
        UNAVAILABLE
    }
}
