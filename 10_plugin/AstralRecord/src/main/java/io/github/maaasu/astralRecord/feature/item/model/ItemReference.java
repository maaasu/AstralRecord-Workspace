package io.github.maaasu.astralRecord.feature.item.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * AstralRecord アイテムを業務ロジック側で識別するための参照情報です。
 * <p>
 * Bukkit の {@code ItemStack} や inventory entry、loadout slot などの入力境界から、
 * itemId / category / instanceId を切り出して保持します。
 * 表示用オブジェクトそのものではなく、業務ロジックが扱う最小識別情報として利用します。
 *
 * @param itemId アイテム ID
 * @param category アイテムカテゴリ
 * @param equipmentInstanceId 装備インスタンス ID。装備以外は null
 */
public record ItemReference(
    @NotNull String itemId,
    @NotNull String category,
    @Nullable String equipmentInstanceId
) {

    /**
     * 参照情報を正規化して生成します。
     *
     * @param itemId アイテム ID
     * @param category アイテムカテゴリ
     * @param equipmentInstanceId 装備インスタンス ID
     */
    public ItemReference {
        itemId = requireNonBlank(itemId, "itemId");
        category = requireNonBlank(category, "category");
        equipmentInstanceId = normalizeOptional(equipmentInstanceId);
    }

    /**
     * 装備インスタンス ID を保持しているか判定します。
     *
     * @return 保持している場合 true
     */
    public boolean hasEquipmentInstanceId() {
        return equipmentInstanceId != null;
    }

    private static @NotNull String requireNonBlank(@Nullable String value, @NotNull String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static @Nullable String normalizeOptional(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
