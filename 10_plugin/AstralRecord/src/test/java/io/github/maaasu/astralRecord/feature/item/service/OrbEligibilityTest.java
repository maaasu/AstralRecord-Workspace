package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnchantDef;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEnchantOperation;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbEligibilityTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 固定5フレームを2tickずつ表示して10tick演出し、さらに10tick後まで操作ロックを維持する。
     */
    @Test
    void fixedAnimationAndRefreshWaitLockForTwentyTicks() {
        assertEquals(10L, OrbService.animationDurationTicks());
        assertEquals(20L, OrbService.postMutationLockDurationTicks());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 状態変化一覧は数値ランク999を表示せず、状態変化名を「星鋼化へ変化」の形式で返す。
     */
    @Test
    void transitionListDescriptionUsesNameWithoutNumericRank() {
        ItemEquipmentTranscendence definition = transition("星鋼化", 999, 4, 6, 4);

        String description = OrbService.transitionListDescription(definition);

        assertEquals("星鋼化へ変化", description);
        assertFalse(description.contains("999"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 強化対象は現在ランクの完全一致または指定ランク以下だけを許可し、上位の完全一致オーブを拒否する。
     */
    @Test
    void enhancementUsesCurrentRankAndSupportsExactAndAtMost() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 3);
        EquipmentInstance rankTwo = instance(2, 1, 100, 80, List.of());
        ItemOrbEffect exactTwo = enhancementEffect(2, ItemOrbRankMode.EXACT);
        ItemOrbEffect exactFive = enhancementEffect(5, ItemOrbRankMode.EXACT);
        ItemOrbEffect atMostFive = enhancementEffect(5, ItemOrbRankMode.AT_MOST);

        assertNotNull(OrbEligibility.resolveEnhancement(exactTwo, model, rankTwo));
        assertNull(OrbEligibility.resolveEnhancement(exactFive, model, rankTwo));
        assertNotNull(OrbEligibility.resolveEnhancement(atMostFive, model, rankTwo));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 状態変化は現在強化上限未達なら拒否し、達成時は飛び級せず即時次ランクの定義を返す。
     */
    @Test
    void transcendenceUsesImmediateDestinationRankAndRequiresCurrentEnhanceCap() {
        ItemEquipmentTranscendence rankThree = transition("星鋼化", 3, 4, 6, 4);
        ItemEquipmentTranscendence rankFive = transition("星核化", 5, 6, 8, 5);
        ItemModel model = equipmentModel(ItemEquipmentSlot.CHEST, 5, List.of(rankThree, rankFive), 3);
        ItemOrbEffect atMostFive = transitionEffect(5, ItemOrbRankMode.AT_MOST);

        assertNull(OrbEligibility.resolveTranscendence(
            atMostFive, model, instance(2, 4, 100, 100, List.of())));
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(
            atMostFive, model, instance(2, 5, 100, 100, List.of()));

        assertNotNull(plan);
        assertTrue(plan.definition().getName().equals("星鋼化"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: rank未指定の固定値・全回復修理は損耗装備を候補化し、最大耐久の装備を候補から除外する。
     */
    @Test
    void repairRequiresMissingDurabilityAndConfiguredRecovery() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.ACCESSORY, 5, List.of(), 3);
        ItemOrbEffect fixedRepair = new ItemOrbEffect(
            ItemOrbEffectType.REPAIR,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            50,
            false,
            null,
            null
        );
        ItemOrbEffect fullRepair = new ItemOrbEffect(
            ItemOrbEffectType.REPAIR,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            true,
            null,
            null
        );

        assertTrue(OrbEligibility.canRepair(fixedRepair, model, instance(0, 0, 100, 25, List.of())));
        assertTrue(OrbEligibility.canRepair(fullRepair, model, instance(0, 0, 100, 25, List.of())));
        assertFalse(OrbEligibility.canRepair(fixedRepair, model, instance(0, 0, 100, 100, List.of())));
        assertFalse(OrbEligibility.canRepair(fullRepair, model, instance(0, 0, 100, 100, List.of())));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 全空枠付与は既存effectIdと重複しない異なる候補が空き枠数以上ある場合だけ許可する。
     */
    @Test
    void fillAllRequiresEnoughDistinctEffectsWithoutExistingDuplicates() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 3);
        EnchantMaster twoEffects = enchantMaster(List.of("attack", "critical"));
        EnchantMaster threeEffects = enchantMaster(List.of("attack", "critical", "speed"));
        EquipmentInstance oneExisting = instance(0, 0, 100, 100, List.of(
            enchant(0, "attack")
        ));
        ItemOrbEffect fillAll = enchantEffect(ItemOrbEnchantOperation.FILL_ALL_EMPTY);

        assertFalse(OrbEligibility.canEnchant(fillAll, model, oneExisting, twoEffects));
        assertTrue(OrbEligibility.canEnchant(fillAll, model, oneExisting, threeEffects));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: ランダム上書きは既存枠がある場合だけ許可し、空枠1件付与は空き枠がある場合だけ許可する。
     */
    @Test
    void overwriteRequiresExistingSlotWhileFillOneRequiresEmptySlot() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 1);
        EnchantMaster master = enchantMaster(List.of("attack"));
        EquipmentInstance empty = instance(0, 0, 100, 100, List.of());
        EquipmentInstance occupied = instance(0, 0, 100, 100, List.of(enchant(0, "attack")));

        assertFalse(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.OVERWRITE_RANDOM), model, empty, master));
        assertTrue(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.OVERWRITE_RANDOM), model, occupied, master));
        assertTrue(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.FILL_ONE_EMPTY), model, empty, master));
        assertFalse(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.FILL_ONE_EMPTY), model, occupied, master));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: legacy effectIdの既存行はstatus・type・値域が候補と意味一致する場合も重複として除外し、別効果がなければ付与不能にする。
     */
    @Test
    void legacyEnchantWithEquivalentMeaningIsExcludedAsDuplicate() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 2);
        EquipmentInstance legacyExisting = instance(0, 0, 100, 100, List.of(
            new EquipmentEnchant(
                "legacy-enchant",
                "00000000-0000-0000-0000-000000000001",
                0,
                "legacy-master",
                "legacy_attack_flat_15",
                "ATTACK",
                "FLAT",
                15.0D
            )
        ));
        EnchantMaster equivalentOnly = new EnchantMaster(
            1,
            "enchant001",
            List.of(new EnchantTarget(
                EnchantEquipmentType.WEAPON,
                List.of(new EnchantEntry("attack_range", "ATTACK", "FLAT", "10~20", 10))
            ))
        );
        EnchantMaster withDistinctAlternative = new EnchantMaster(
            1,
            "enchant001",
            List.of(new EnchantTarget(
                EnchantEquipmentType.WEAPON,
                List.of(
                    new EnchantEntry("attack_range", "ATTACK", "FLAT", "10~20", 10),
                    new EnchantEntry("critical", "CRITICAL", "FLAT", "1", 1)
                )
            ))
        );

        assertFalse(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.FILL_ONE_EMPTY),
            model,
            legacyExisting,
            equivalentOnly
        ));
        assertTrue(OrbEligibility.canEnchant(
            enchantEffect(ItemOrbEnchantOperation.FILL_ONE_EMPTY),
            model,
            legacyExisting,
            withDistinctAlternative
        ));
    }

    /** 指定ランク条件の武器強化オーブ効果を作成します。 */
    private static ItemOrbEffect enhancementEffect(int rank, ItemOrbRankMode mode) {
        return new ItemOrbEffect(
            ItemOrbEffectType.ENHANCE,
            List.of(ItemEquipmentSlot.WEAPON),
            rank,
            mode,
            null,
            false,
            null,
            null
        );
    }

    /** 指定目標ランク条件の状態変化オーブ効果を作成します。 */
    private static ItemOrbEffect transitionEffect(int rank, ItemOrbRankMode mode) {
        return new ItemOrbEffect(
            ItemOrbEffectType.TRANSCENDENCE,
            List.of(),
            rank,
            mode,
            null,
            false,
            null,
            null
        );
    }

    /** 指定枠操作のエンチャントオーブ効果を作成します。 */
    private static ItemOrbEffect enchantEffect(ItemOrbEnchantOperation operation) {
        return new ItemOrbEffect(
            ItemOrbEffectType.ENCHANT,
            List.of(),
            null,
            ItemOrbRankMode.EXACT,
            null,
            false,
            "enchant001",
            operation
        );
    }

    /** テスト用装備マスタを作成します。 */
    private static ItemModel equipmentModel(
        ItemEquipmentSlot slot,
        int maxEnhanceLevel,
        List<ItemEquipmentTranscendence> transitions,
        int enchantSlots
    ) {
        List<ItemEquipmentEnhanceLevel> levels = java.util.stream.IntStream.rangeClosed(1, 8)
            .mapToObj(level -> new ItemEquipmentEnhanceLevel(
                level,
                List.of(),
                null,
                1.0D,
                ItemEquipmentEnhanceFailAction.NONE,
                null
            ))
            .toList();
        ItemEquipment equipment = new ItemEquipment(
            slot,
            ItemEquipmentHandType.ONE,
            null,
            0,
            List.of(),
            null,
            List.of(),
            null,
            new ItemEquipmentEnhance(maxEnhanceLevel, levels),
            new ItemEquipmentEnchantDef(enchantSlots),
            null,
            transitions
        );
        return new ItemModel(
            1,
            "test_equipment",
            "equipment",
            "テスト装備",
            "IRON_SWORD",
            "common",
            1,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            equipment,
            null,
            null,
            null,
            null,
            null
        );
    }

    /** テスト用状態変化定義を作成します。 */
    private static ItemEquipmentTranscendence transition(
        String name,
        int rank,
        int requiredEnhance,
        Integer overrideEnhanceMax,
        Integer overrideEnchantSlots
    ) {
        return new ItemEquipmentTranscendence(
            name,
            rank,
            requiredEnhance,
            List.of(),
            0,
            null,
            overrideEnhanceMax,
            overrideEnchantSlots
        );
    }

    /** テスト用装備個体を作成します。 */
    private static EquipmentInstance instance(
        int rank,
        int enhanceLevel,
        int durabilityMax,
        int durabilityValue,
        List<EquipmentEnchant> enchants
    ) {
        return new EquipmentInstance(
            "00000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000002",
            "test_equipment",
            enhanceLevel,
            0,
            rank,
            durabilityMax,
            durabilityValue,
            "2026-08-10T00:00:00Z",
            "2026-08-10T00:00:00Z",
            List.of(),
            enchants,
            List.of()
        );
    }

    /** テスト用エンチャント個体を作成します。 */
    private static EquipmentEnchant enchant(int slot, String effectId) {
        return new EquipmentEnchant(
            "enchant-" + slot,
            "00000000-0000-0000-0000-000000000001",
            slot,
            "enchant001",
            effectId,
            "ATTACK",
            "FLAT",
            1.0D
        );
    }

    /** 武器向け効果IDを持つテスト用共通マスタを作成します。 */
    private static EnchantMaster enchantMaster(List<String> effectIds) {
        List<EnchantEntry> entries = effectIds.stream()
            .map(effectId -> new EnchantEntry(effectId, "ATTACK", "FLAT", "1", 1))
            .toList();
        return new EnchantMaster(
            1,
            "enchant001",
            List.of(new EnchantTarget(EnchantEquipmentType.WEAPON, entries))
        );
    }
}
