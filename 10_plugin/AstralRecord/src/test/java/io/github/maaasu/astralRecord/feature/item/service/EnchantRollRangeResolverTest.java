package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnchantRollRangeResolverTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 付与済み抽選範囲の照合
     * 検証契約: 対象グループ内の一意な同一効果を正規化照合し、逆順端点の有限範囲を返す。
     */
    @Test
    void resolvesOnlyMatchingEffectWithinEquipmentGroup() {
        var master = new EnchantMaster(1, "master", List.of(
            new EnchantTarget(EnchantEquipmentType.ARMOR, List.of(entry("effect", "DEFENSE", "FLAT", "9~10"))),
            new EnchantTarget(EnchantEquipmentType.WEAPON, List.of(entry("effect", " attack ", "scalar", "2~1")))));
        var result = EnchantRollRangeResolver.resolve(master, ItemEquipmentSlot.SUBWEAPON,
            enchant("master", "effect", "ATTACK", "SCALAR", 1.5));
        assertEquals(new EquipmentStatContributionCalculator.Range(1, 2), result);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 付与済み抽選範囲の照合
     * 検証契約: 旧ID、型変更、status変更、範囲外保存値、未知型、非有限値を推測で補完しない。
     */
    @Test
    void rejectsUnresolvedOrInconsistentSavedEffects() {
        var master = master(entry("effect", "ATTACK", "FLAT", "1~3"));
        for (var saved : List.of(enchant("old_master", "effect", "ATTACK", "FLAT", 2),
            enchant("master", "old_effect", "ATTACK", "FLAT", 2),
            enchant("master", "effect", "DEFENSE", "FLAT", 2),
            enchant("master", "effect", "ATTACK", "SCALAR", 2),
            enchant("master", "effect", "ATTACK", "unknown", 2),
            enchant("master", "effect", "ATTACK", "FLAT", 5),
            enchant("master", "effect", "ATTACK", "FLAT", Double.NaN))) {
            assertNull(EnchantRollRangeResolver.resolve(master, ItemEquipmentSlot.WEAPON, saved));
        }
        assertNull(EnchantRollRangeResolver.resolve(null, ItemEquipmentSlot.WEAPON,
            enchant("master", "effect", "ATTACK", "FLAT", 2)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 付与済み抽選範囲の照合
     * 検証契約: 固定値、同値端点、不正範囲、重複候補、対象外グループに架空範囲を返さない。
     */
    @Test
    void rejectsFixedMalformedAndAmbiguousRanges() {
        var saved = enchant("master", "effect", "ATTACK", "FLAT", 2);
        for (String value : List.of("2", "2~2", "1~bad", "1~3~4", "NaN~3", "1~Infinity")) {
            assertNull(EnchantRollRangeResolver.resolve(master(entry("effect", "ATTACK", "FLAT", value)),
                ItemEquipmentSlot.WEAPON, saved));
        }
        var entry = entry("effect", "ATTACK", "FLAT", "1~3");
        assertNull(EnchantRollRangeResolver.resolve(master(entry, entry), ItemEquipmentSlot.WEAPON, saved));
        assertNull(EnchantRollRangeResolver.resolve(master(entry), ItemEquipmentSlot.HEAD, saved));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 付与済み抽選範囲の照合
     * 検証契約: 同じIDで新masterが公開されても古い範囲をキャッシュせず、削除効果は解決しない。
     */
    @Test
    void eachCallUsesProvidedMasterGeneration() {
        var saved = enchant("master", "effect", "ATTACK", "FLAT", 2);
        assertEquals(new EquipmentStatContributionCalculator.Range(1, 3),
            EnchantRollRangeResolver.resolve(master(entry("effect", "ATTACK", "FLAT", "1~3")), ItemEquipmentSlot.WEAPON, saved));
        assertEquals(new EquipmentStatContributionCalculator.Range(0, 5),
            EnchantRollRangeResolver.resolve(master(entry("effect", "ATTACK", "FLAT", "0~5")), ItemEquipmentSlot.WEAPON, saved));
        assertNull(EnchantRollRangeResolver.resolve(master(entry("replacement", "ATTACK", "FLAT", "1~3")),
            ItemEquipmentSlot.WEAPON, saved));
    }

    private static EnchantMaster master(EnchantEntry... entries) {
        return new EnchantMaster(1, "master", List.of(new EnchantTarget(EnchantEquipmentType.WEAPON, List.of(entries))));
    }

    private static EnchantEntry entry(String id, String status, String type, String value) {
        return new EnchantEntry(id, status, type, value, 1);
    }

    private static EquipmentEnchant enchant(String master, String effect, String status, String type, double value) {
        return new EquipmentEnchant("e", "i", 0, master, effect, status, type, value);
    }
}
