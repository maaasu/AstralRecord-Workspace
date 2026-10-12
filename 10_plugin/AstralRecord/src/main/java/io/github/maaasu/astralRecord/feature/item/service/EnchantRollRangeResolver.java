package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 確定した付与効果と一致する、現行マスタの抽選範囲だけを解決します。 */
public final class EnchantRollRangeResolver {
    private EnchantRollRangeResolver() { }

    /**
     * 安定したmaster/effect識別子、対象装備グループ、status、type、保存値を照合します。
     * マスタ変更で旧効果がなくなった場合、固定値、曖昧な定義、保存値が範囲外ならnullを返します。
     * 保存値から候補を推測せず、通信・永続キャッシュ・個体変更は行いません。
     *
     * @param master 呼出時に公開済みキャッシュから解決した共通マスタ。未解決ならnull
     * @param slot 装備スロット
     * @param enchant 付与済み効果
     * @return 表示してよい現行の抽選範囲。解決できない場合はnull
     */
    public static @Nullable EquipmentStatContributionCalculator.Range resolve(@Nullable EnchantMaster master,
            @Nullable ItemEquipmentSlot slot, @NotNull EquipmentEnchant enchant) {
        if (master == null || !same(master.getId(), enchant.getEnchantMasterId())
                || enchant.getEffectId().isBlank() || !Double.isFinite(enchant.getValue())) return null;
        EnchantEquipmentType group = equipmentType(slot);
        if (group == null) return null;
        EnchantEntry matched = null;
        for (var target : master.getTargets()) {
            if (target.getEquipmentType() != group) continue;
            for (var entry : target.getEntries()) {
                if (!same(entry.getEffectId(), enchant.getEffectId())) continue;
                if (matched != null) return null;
                matched = entry;
            }
        }
        if (matched == null || !statusKey(matched.getStatus()).equals(statusKey(enchant.getStatus()))) return null;
        ItemEquipmentStatType type = ItemEquipmentStatType.fromApiValue(enchant.getType());
        if (!same(enchant.getType(), type.name()) || !same(matched.getType(), type.name())) return null;
        String[] endpoints = matched.getValue().trim().split("~", -1);
        if (endpoints.length != 2) return null;
        try {
            double first = Double.parseDouble(endpoints[0].trim());
            double second = Double.parseDouble(endpoints[1].trim());
            double min = Math.min(first, second), max = Math.max(first, second);
            if (!Double.isFinite(min) || !Double.isFinite(max) || min == max
                    || enchant.getValue() < min || enchant.getValue() > max) return null;
            return new EquipmentStatContributionCalculator.Range(min, max);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 付与経路と同じ装備グループへ変換します。 */
    private static @Nullable EnchantEquipmentType equipmentType(@Nullable ItemEquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case WEAPON, SUBWEAPON -> EnchantEquipmentType.WEAPON;
            case HEAD, CHEST, LEGS, FEET -> EnchantEquipmentType.ARMOR;
            case ACCESSORY -> EnchantEquipmentType.ACCESSORY;
            default -> null;
        };
    }

    /** IDとtypeの大小文字・周辺空白を正規化して照合します。 */
    private static boolean same(String first, String second) {
        return first.trim().equalsIgnoreCase(second.trim());
    }

    /** 実戦のステータス解決と同じ入力正規化を行います。 */
    private static String statusKey(String raw) {
        return raw.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
