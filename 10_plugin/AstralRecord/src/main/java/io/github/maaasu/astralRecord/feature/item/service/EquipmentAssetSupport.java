package io.github.maaasu.astralRecord.feature.item.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.regex.Pattern;

/** 表示専用コピーの身体防具に、管理パックの装備レイヤーを適用します。 */
public final class EquipmentAssetSupport {
    private static final Pattern ASSET = Pattern.compile("astralrecord:[a-z0-9_]+(?:/[a-z0-9_]+)*");
    private static final Pattern NATIVE_ARMOR = Pattern.compile(
            "(?:(?:LEATHER|CHAINMAIL|IRON|GOLDEN|DIAMOND|NETHERITE|COPPER)_(?:HELMET|CHESTPLATE|LEGGINGS|BOOTS)|TURTLE_HELMET)");
    private static final Set<EquipmentSlot> ARMOR_SLOTS = Set.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private EquipmentAssetSupport() {
    }

    /**
     * 管理パックのアセットIDを検証します。空値や不正な参照は通常防具表示へ戻します。
     *
     * @param value マスターまたは保存済みPDC由来の参照
     * @return 使用可能な参照。空値・不正値ではnull
     */
    public static @Nullable String normalize(@Nullable String value) {
        if (value == null) return null;
        String candidate = value.trim();
        return candidate.length() <= 160 && ASSET.matcher(candidate).matches() ? candidate : null;
    }

    /**
     * 身体防具の既存equippable設定を維持して、表示用アセットだけを切り替えます。
     *
     * @param item 変更可能な表示専用コピー。サーバーの装備実体を渡さない
     * @param asset 最新マスターのアセット。削除・不正値は通常防具へ戻す
     * @return componentを変更した場合はtrue
     */
    public static boolean apply(@NotNull ItemStack item, @Nullable String asset) {
        var equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable == null || !ARMOR_SLOTS.contains(equippable.slot())) return false;
        String normalized = normalize(asset);
        if (normalized == null) {
            if (equippable.assetId() != null && equippable.assetId().namespace().equals("astralrecord")) {
                var defaults = item.getType().getDefaultData(DataComponentTypes.EQUIPPABLE);
                item.setData(DataComponentTypes.EQUIPPABLE,
                        equippable.toBuilder().assetId(defaults == null ? null : defaults.assetId()));
                return true;
            }
            return false;
        }
        Key key = Key.key(normalized);
        if (key.equals(equippable.assetId())) return false;
        item.setData(DataComponentTypes.EQUIPPABLE, equippable.toBuilder().assetId(key));
        return true;
    }

    /**
     * 基本素材が頭・胴・脚・足のネイティブ防具かを判定します。
     *
     * @param item 表示専用コピー
     * @return 身体防具の基本素材ならtrue。馬鎧・武器・装飾素材はfalse
     */
    public static boolean isNativeBodyArmor(@NotNull ItemStack item) {
        return NATIVE_ARMOR.matcher(item.getType().name()).matches();
    }
}
