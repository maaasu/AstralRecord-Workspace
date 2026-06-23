package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 報酬アイテムの表示名と個数を GUI 向けに整形します。
 */
public final class RewardDisplayFormatter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private RewardDisplayFormatter() {
    }

    /**
     * 報酬表示用の 1 行コンポーネントを作成します。
     *
     * @param model 報酬アイテム。未解決の場合は不明表記
     * @param amount 個数
     * @return 報酬行
     */
    public static @NotNull Component rewardLine(@Nullable ItemModel model, int amount) {
        return Component.text("報酬: ", NamedTextColor.GRAY)
            .append(displayName(model))
            .append(Component.text(" ×" + amount, NamedTextColor.YELLOW, TextDecoration.BOLD))
            .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 報酬表示用の箇条書きコンポーネントを作成します。
     *
     * @param model 報酬アイテム。未解決の場合は itemId を表示
     * @param itemId 未解決時のフォールバック ID
     * @param amount 個数
     * @return 箇条書き行
     */
    public static @NotNull Component rewardBullet(@Nullable ItemModel model, @NotNull String itemId, int amount) {
        return Component.text("- ", NamedTextColor.GRAY)
            .append(displayName(model, itemId))
            .append(Component.text(" ×" + amount, NamedTextColor.YELLOW, TextDecoration.BOLD))
            .decoration(TextDecoration.ITALIC, false);
    }

    private static @NotNull Component displayName(@Nullable ItemModel model) {
        return displayName(model, "不明な報酬");
    }

    private static @NotNull Component displayName(@Nullable ItemModel model, @NotNull String fallback) {
        String translated = ColorCodeUtil.toLegacyText(resolveName(model, fallback), fallback);
        return LEGACY.deserialize(translated).decoration(TextDecoration.ITALIC, false);
    }

    private static @NotNull String resolveName(@Nullable ItemModel model, @NotNull String fallback) {
        if (model == null) {
            return fallback;
        }
        if (ItemService.ASTRALD_CURRENCY_ITEM_ID.equalsIgnoreCase(model.getId())) {
            return "アストラルド";
        }
        if (ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(model.getId())
            || ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(model.getId())) {
            return "ゴールド";
        }
        if (model.getName() == null || model.getName().isBlank()) {
            return fallback;
        }
        return model.getName();
    }
}
