package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 共通マスターと照合済みの、進行プリセットの付与効果です。 */
public record PresetEquipmentEnchant(String masterId, String effectId, String status, String type, double value) {

    /**
     * 対象装備の区分と確定値を共通マスターに照合します。
     * @param master 公開済みの候補マスター。未登録の場合は null
     * @param slot 装備の種別
     * @param effectId 安定した候補効果ID
     * @param value 永続化可能な精度で指定した確定値
     * @return 使用可能な効果。区分・効果・値がマスターに合わない場合は null
     */
    public static @Nullable PresetEquipmentEnchant resolve(@Nullable EnchantMaster master, ItemEquipmentSlot slot,
            String effectId, BigDecimal value) {
        var group = EnchantRollRangeResolver.equipmentType(slot);
        if (master == null || group == null) return null;

        EnchantEntry match = null;
        for (var target : master.getTargets()) {
            if (target.getEquipmentType() != group) continue;
            for (var entry : target.getEntries()) {
                if (!entry.getEffectId().trim().equalsIgnoreCase(effectId.trim())) continue;
                if (match != null) return null;
                match = entry;
            }
        }
        if (match == null || match.getWeight() <= 0 || match.getStatus().isBlank()) return null;
        var type = ItemEquipmentStatType.fromApiValue(match.getType());
        if (!type.name().equalsIgnoreCase(match.getType().trim()) || !withinRange(match.getValue(), value)) return null;
        return new PresetEquipmentEnchant(master.getId(), match.getEffectId().trim(), match.getStatus().trim(),
            type.name(), value.doubleValue());
    }

    /**
     * 付与効果を生成時に一度だけ個体へ結び付けます。
     * @param instanceId 新規装備個体ID
     * @param index 0から始まる付与枠
     * @return 未固定の付与効果
     */
    public EquipmentEnchant attach(String instanceId, int index) {
        return new EquipmentEnchant(UUID.randomUUID().toString(), instanceId, index, masterId, effectId,
            status, type, value, false);
    }

    /**
     * 既存個体の付与効果と、個体識別子や固定状態を除いて設定が一致するか判定します。
     * @param enchant 既存個体に保存済みの付与効果
     * @param index プリセットで指定した付与枠
     * @return 一致し、個体を変更せずに再利用できる場合 true
     */
    public boolean matches(EquipmentEnchant enchant, int index) {
        return enchant.getSlotIndex() == index && masterId.equalsIgnoreCase(enchant.getEnchantMasterId())
            && effectId.equalsIgnoreCase(enchant.getEffectId()) && status.equalsIgnoreCase(enchant.getStatus())
            && type.equalsIgnoreCase(enchant.getType()) && Double.compare(value, enchant.getValue()) == 0;
    }

    /**
     * 個体の付与枠すべてがプリセットに一致するか、APIの返却順に依存せず判定します。
     * @param desired 付与枠順のプリセット効果
     * @param existing 保存順の個体効果
     * @return 空欄を含む個体の付与構成が一致する場合 true
     */
    public static boolean matchesAll(List<PresetEquipmentEnchant> desired, List<EquipmentEnchant> existing) {
        if (desired.size() != existing.size()) return false;
        for (int index = 0; index < desired.size(); index++) {
            final int slot = index;
            if (existing.stream().noneMatch(enchant -> desired.get(slot).matches(enchant, slot))) return false;
        }
        return true;
    }

    /** 固定値と両端を含む範囲を、丸めずに照合します。 */
    private static boolean withinRange(String raw, BigDecimal value) {
        String[] endpoints = raw.split("~", -1);
        if (endpoints.length > 2) return false;
        try {
            BigDecimal first = new BigDecimal(endpoints[0].trim());
            BigDecimal last = new BigDecimal(endpoints[endpoints.length - 1].trim());
            return value.compareTo(first.min(last)) >= 0 && value.compareTo(first.max(last)) <= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
