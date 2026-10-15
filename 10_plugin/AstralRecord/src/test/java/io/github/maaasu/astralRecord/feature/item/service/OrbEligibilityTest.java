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
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbEligibilityTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 成功率は10進数の小数第一位を四捨五入した整数百分率として表示し、0.5%境界を正しく扱う。
     */
    @Test
    void successRateIsRoundedAtDecimalBoundaryBeforeIntegerDisplay() {
        assertAll(
            () -> assertEquals("1", OrbService.formatPercent(0.005D)),
            () -> assertEquals("15", OrbService.formatPercent(0.145D)),
            () -> assertEquals("57", OrbService.formatPercent(0.565D)),
            () -> assertEquals("100", OrbService.formatPercent(0.995D)),
            () -> assertEquals("69", OrbService.formatPercent(0.694D)),
            () -> assertEquals("70", OrbService.formatPercent(0.695D)),
            () -> assertEquals("70", OrbService.formatPercent(0.6999999988079071D))
        );
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: エンチャント全空枠付与はAPIを待たず、空き枠を異なるeffectIdのローカル確定結果で満たす。
     */
    @Test
    void localEnchantFillsEveryEmptySlotWithDistinctEffects() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 2);
        ItemOrbEffect effect = enchantEffect(ItemOrbEnchantOperation.FILL_ALL_EMPTY);

        OrbLocalMutationCalculator.LocalResult result = OrbLocalMutationCalculator.apply(
            effect, model, instance(0, 0, 100, 100, List.of()),
            enchantMaster(List.of("attack", "critical")), null, null
        );

        assertNotNull(result);
        assertEquals(2, result.instance().getEnchants().size());
        assertEquals(2, result.instance().getEnchants().stream()
            .map(EquipmentEnchant::getEffectId).distinct().count());
        assertEquals(List.of(0, 1), result.instance().getEnchants().stream()
            .map(EquipmentEnchant::getSlotIndex).toList());
        assertEquals("2026-08-10T00:00:00Z", result.instance().getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: ルーン装着と指定slotの脱着はローカル完成状態を作り、脱着時は返却するルーンitem IDを返す。
     */
    @Test
    void localRuneOperationsReturnUpdatedEquipmentAndDetachedRuneId() {
        ItemModel equipment = equipmentModelWithRuneSlots(1);
        ItemModel rune = runeModel("test_rune");
        ItemOrbEffect attach = new ItemOrbEffect(ItemOrbEffectType.RUNE_ATTACH, List.of(), null,
            ItemOrbRankMode.EXACT, null, false, null, null);
        ItemOrbEffect detach = new ItemOrbEffect(ItemOrbEffectType.RUNE_DETACH, List.of(), null,
            ItemOrbRankMode.EXACT, null, false, null, null);

        OrbLocalMutationCalculator.LocalResult attached = OrbLocalMutationCalculator.apply(
            attach, equipment, instanceWithRuneSlots(1), null, rune, null);
        assertNotNull(attached);
        assertEquals("test_rune", attached.instance().getRunes().getFirst().getItemId());

        OrbLocalMutationCalculator.LocalResult detached = OrbLocalMutationCalculator.apply(
            detach, equipment, attached.instance(), null, null, 0);
        assertNotNull(detached);
        assertTrue(detached.instance().getRunes().isEmpty());
        assertEquals("test_rune", detached.returnedRuneItemId());
        assertEquals("2026-08-10T00:00:00Z", detached.instance().getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 強化・修理・状態変化のローカル完成形は表示用の現在時刻をupdatedAtへ書かず、snapshot競合制御に使うAPI base timestampを保持する。
     */
    @Test
    void localEquipmentMutationsKeepApiBaseTimestamp() {
        ItemModel enhanced = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 0);
        ItemOrbEffect repair = new ItemOrbEffect(ItemOrbEffectType.REPAIR, List.of(), null,
            ItemOrbRankMode.EXACT, null, true, null, null);
        ItemEquipmentTranscendence transition = transition("星鋼化", 1, 5, null, null);
        ItemModel transcended = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(transition), 0);

        OrbLocalMutationCalculator.LocalResult enhancedResult = OrbLocalMutationCalculator.apply(
            enhancementEffect(0, ItemOrbRankMode.EXACT), enhanced, instance(0, 0, 100, 70, List.of()),
            null, null, null);
        OrbLocalMutationCalculator.LocalResult repairedResult = OrbLocalMutationCalculator.apply(
            repair, enhanced, instance(0, 0, 100, 70, List.of()), null, null, null);
        OrbLocalMutationCalculator.LocalResult transcendedResult = OrbLocalMutationCalculator.apply(
            transitionEffect(1, ItemOrbRankMode.EXACT), transcended, instance(0, 5, 100, 100, List.of()),
            null, null, null);

        assertNotNull(enhancedResult);
        assertNotNull(repairedResult);
        assertNotNull(transcendedResult);
        assertAll(
            () -> assertEquals("2026-08-10T00:00:00Z", enhancedResult.instance().getUpdatedAt()),
            () -> assertEquals("2026-08-10T00:00:00Z", repairedResult.instance().getUpdatedAt()),
            () -> assertEquals("2026-08-10T00:00:00Z", transcendedResult.instance().getUpdatedAt())
        );
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 固定操作は空枠・固定済みを飛ばし、最小スロットの未固定行だけを固定し、効果と値を保持する。
     */
    @Test
    void locksLowestUnlockedSlotWithoutReorderingRemainingSlots() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 5);
        EquipmentEnchant locked = lockedEnchant(0, "attack");
        EquipmentEnchant later = enchant(4, "speed");
        EquipmentEnchant first = enchant(2, "critical");
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(later, locked, first));
        var result = OrbLocalMutationCalculator.apply(enchantEffect(ItemOrbEnchantOperation.LOCK_FIRST), model, current, null, null, null);
        assertNotNull(result);
        var enchants = result.instance().getEnchants();
        assertEquals(List.of(0, 2, 4), enchants.stream().map(EquipmentEnchant::getSlotIndex).toList());
        assertEquals(locked, enchants.get(0));
        assertEquals(first.copy(first.getEnchantId(), first.getEquipmentInstanceId(), first.getSlotIndex(), first.getEnchantMasterId(),
            first.getEffectId(), first.getStatus(), first.getType(), first.getValue(), true), enchants.get(1));
        assertEquals(later, enchants.get(2));
        assertFalse(current.getEnchants().get(2).isLocked());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 削除は未固定行だけを対象とし、残存行のスロットと固定状態を保全する。
     */
    @Test
    void removalLeavesLockedSlotAndDoesNotCompactGaps() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 4);
        EquipmentEnchant locked = lockedEnchant(3, "attack");
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(enchant(0, "speed"), locked));
        var result = OrbLocalMutationCalculator.apply(enchantEffect(ItemOrbEnchantOperation.REMOVE_RANDOM), model, current, null, null, null);
        assertNotNull(result);
        assertEquals(List.of(locked), result.instance().getEnchants());
        assertEquals(2, current.getEnchants().size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 全固定の加工は上書き・再抽選・削除・固定のいずれも無変更として拒否し、支払い対象の完成状態を返さない。
     */
    @Test
    void allLockedRejectsRandomMutationsAndFurtherLocking() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 1);
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(lockedEnchant(0, "attack")));
        for (var operation : List.of(ItemOrbEnchantOperation.OVERWRITE_RANDOM, ItemOrbEnchantOperation.REROLL_VALUE_RANDOM,
            ItemOrbEnchantOperation.REMOVE_RANDOM, ItemOrbEnchantOperation.LOCK_FIRST)) {
            ItemOrbEffect effect = enchantEffect(operation);
            assertFalse(OrbEligibility.canEnchant(effect, model, current, enchantMaster(List.of("attack"))), operation.name());
            assertNull(OrbLocalMutationCalculator.apply(effect, model, current, enchantMaster(List.of("attack")), null, null), operation.name());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 数値再抽選は固定済みを保全し、未固定行のID・枠・効果を保持しながら数値と由来だけを上下のマスタで更新する。
     */
    @Test
    void rerollPreservesIdentityAndLockedRowsAcrossDifferentMasterRanges() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 3);
        EquipmentEnchant locked = lockedEnchant(0, "critical");
        EquipmentEnchant currentEnchant = enchant(2, "attack");
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(locked, currentEnchant));
        ItemOrbEffect effect = enchantEffect(ItemOrbEnchantOperation.REROLL_VALUE_RANDOM);
        for (double value : List.of(8.0D, 0.25D, 1.0D)) {
            EnchantMaster master = new EnchantMaster(1, "reroll-master", List.of(new EnchantTarget(EnchantEquipmentType.WEAPON,
                List.of(new EnchantEntry("attack", "ATTACK", "FLAT", Double.toString(value), 1)))));
            var result = OrbLocalMutationCalculator.apply(effect, model, current, master, null, null);
            assertNotNull(result);
            assertEquals(locked, result.instance().getEnchants().getFirst());
            assertEquals(currentEnchant.copy(currentEnchant.getEnchantId(), currentEnchant.getEquipmentInstanceId(), currentEnchant.getSlotIndex(),
                master.getId(), currentEnchant.getEffectId(), currentEnchant.getStatus(), currentEnchant.getType(), value, false),
                result.instance().getEnchants().get(1));
        }
        assertEquals(1.0D, current.getEnchants().get(1).getValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 数値再抽選は全未固定行の効果ID・status・typeが使用マスタにある場合だけ許可し、種類の置換を防ぐ。
     */
    @Test
    void rerollRejectsMissingOrDifferentEffectDefinitions() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 2);
        ItemOrbEffect effect = enchantEffect(ItemOrbEnchantOperation.REROLL_VALUE_RANDOM);
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(enchant(0, "attack"), enchant(1, "critical")));
        assertFalse(OrbEligibility.canEnchant(effect, model, current, enchantMaster(List.of("attack"))));
        EnchantMaster incompatible = new EnchantMaster(1, "master", List.of(new EnchantTarget(EnchantEquipmentType.WEAPON,
            List.of(new EnchantEntry("attack", "ATTACK", "SCALAR", "1", 1), new EnchantEntry("critical", "ATTACK", "FLAT", "1", 1)))));
        assertFalse(OrbEligibility.canEnchant(effect, model, current, incompatible));
        assertNull(OrbLocalMutationCalculator.apply(effect, model, current, incompatible, null, null));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 上書きは未固定行だけを対象にし、固定済みの効果との重複を禁止する。
     */
    @Test
    void overwriteProtectsLockedRowsAndIncludesThemInDuplicateChecks() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 2);
        EquipmentEnchant locked = lockedEnchant(0, "attack");
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(locked, enchant(1, "critical")));
        ItemOrbEffect effect = enchantEffect(ItemOrbEnchantOperation.OVERWRITE_RANDOM);
        assertFalse(OrbEligibility.canEnchant(effect, model, current, enchantMaster(List.of("attack"))));
        var result = OrbLocalMutationCalculator.apply(effect, model, current, enchantMaster(List.of("attack", "speed")), null, null);
        assertNotNull(result);
        assertEquals(locked, result.instance().getEnchants().getFirst());
        assertEquals("speed", result.instance().getEnchants().get(1).getEffectId());
        assertFalse(result.instance().getEnchants().get(1).isLocked());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 空き枠への付与では固定行を占有枠・重複集合へ含めて維持し、新規付与行は未固定とする。
     */
    @Test
    void fillRespectsLockedOccupancyAndEffects() {
        ItemModel model = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 2);
        EquipmentEnchant locked = lockedEnchant(1, "attack");
        EquipmentInstance current = instance(0, 0, 100, 100, List.of(locked));
        var result = OrbLocalMutationCalculator.apply(enchantEffect(ItemOrbEnchantOperation.FILL_ALL_EMPTY), model, current,
            enchantMaster(List.of("attack", "critical")), null, null);
        assertNotNull(result);
        assertEquals("critical", result.instance().getEnchants().getFirst().getEffectId());
        assertEquals(0, result.instance().getEnchants().getFirst().getSlotIndex());
        assertFalse(result.instance().getEnchants().getFirst().isLocked());
        assertEquals(locked, result.instance().getEnchants().get(1));
    }

    private static EquipmentEnchant lockedEnchant(int slot, String effectId) {
        EquipmentEnchant current = enchant(slot, effectId);
        return current.copy(current.getEnchantId(), current.getEquipmentInstanceId(), current.getSlotIndex(), current.getEnchantMasterId(),
            current.getEffectId(), current.getStatus(), current.getType(), current.getValue(), true);
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
            null
        );
    }

    private static ItemModel equipmentModelWithRuneSlots(int slots) {
        ItemEquipment base = equipmentModel(ItemEquipmentSlot.WEAPON, 5, List.of(), 0).getEquipment();
        ItemEquipment equipment = new ItemEquipment(
            base.getSlot(), base.getHandType(), base.getTag(), base.getRequiredLevel(), base.getRequiredClasses(),
            base.getSetId(), base.getStats(), base.getDurability(), base.getEnhance(), base.getEnchant(),
            new io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentRuneDef(String.valueOf(slots)),
            base.getTranscendence()
        );
        return new ItemModel(1, "test_equipment", "equipment", "テスト装備", "IRON_SWORD", "common", 1,
            0, null, null, List.of(), false, false, null, null, equipment, null, null, null, null);
    }

    private static ItemModel runeModel(String id) {
        return new ItemModel(1, id, "rune", "テストルーン", "AMETHYST_SHARD", "common", 64,
            0, null, null, List.of(), false, false, null, null, null,
            new ItemRune(List.of("WEAPON"), 0, List.of(), List.of()), null, null, null);
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

    private static EquipmentInstance instanceWithRuneSlots(int runeMaxSlots) {
        EquipmentInstance base = instance(0, 0, 100, 100, List.of());
        return new EquipmentInstance(base.getEquipmentInstanceId(), base.getAccountId(), base.getItemId(),
            base.getEnhanceLevel(), runeMaxSlots, base.getTranscendenceRank(), base.getDurabilityMax(),
            base.getDurabilityValue(), base.getCreatedAt(), base.getUpdatedAt(), base.getStatRolls(),
            base.getEnchants(), base.getRunes());
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
