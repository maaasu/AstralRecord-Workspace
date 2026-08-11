package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEnchantOperation;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbRankMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * オーブ一覧の表示条件と実行直前の再検証で共有する純粋な判定ロジックです。
 */
final class OrbEligibility {

    /** インスタンス化を禁止します。 */
    private OrbEligibility() {
    }

    /**
     * 指定装備に対する次の強化定義を解決します。
     *
     * @param effect 使用するオーブ効果
     * @param model 装備マスタ
     * @param instance 装備個体
     * @return 強化可能な場合は次レベル計画、条件外なら {@code null}
     */
    static @Nullable EnhancementPlan resolveEnhancement(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
        ItemEquipment equipment = model.getEquipment();
        if (equipment == null || equipment.getSlot() == null || equipment.getEnhance() == null) {
            return null;
        }
        if (effect.getTargetSlots().isEmpty() || !effect.getTargetSlots().contains(equipment.getSlot())) {
            return null;
        }
        if (!matchesRank(instance.getTranscendenceRank(), effect)) {
            return null;
        }

        int targetLevel = instance.getEnhanceLevel() + 1;
        if (targetLevel > effectiveEnhanceMaxLevel(equipment, instance.getTranscendenceRank())) {
            return null;
        }
        ItemEquipmentEnhanceLevel definition = equipment.getEnhance().getLevels().stream()
            .filter(level -> level.getLevel() == targetLevel)
            .findFirst()
            .orElse(null);
        return definition == null ? null : new EnhancementPlan(targetLevel, definition);
    }

    /**
     * 指定装備の即時次段階となる状態変化を解決します。
     *
     * @param effect 使用するオーブ効果
     * @param model 装備マスタ
     * @param instance 装備個体
     * @return 状態変化可能な場合は遷移計画、条件外なら {@code null}
     */
    static @Nullable TranscendencePlan resolveTranscendence(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
        ItemEquipment equipment = model.getEquipment();
        if (equipment == null) {
            return null;
        }
        ItemEquipmentTranscendence next = equipment.getTranscendence().stream()
            .filter(candidate -> candidate.getRank() > instance.getTranscendenceRank())
            .min(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
            .orElse(null);
        if (next == null
            || effect.getRank() == null
            || !matchesRank(next.getRank(), effect)
            || instance.getEnhanceLevel() < effectiveEnhanceMaxLevel(
                equipment, instance.getTranscendenceRank())
            || instance.getEnhanceLevel() < next.getRequiredEnhanceLevel()) {
            return null;
        }
        return new TranscendencePlan(next);
    }

    /**
     * 指定装備が修理オーブの対象になるか判定します。
     *
     * @param effect 使用するオーブ効果
     * @param model 装備マスタ
     * @param instance 装備個体
     * @return 耐久値を回復できる場合 {@code true}
     */
    static boolean canRepair(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
        return model.getEquipment() != null
            && (effect.getTargetSlots().isEmpty()
                || effect.getTargetSlots().contains(model.getEquipment().getSlot()))
            && matchesRank(instance.getTranscendenceRank(), effect)
            && instance.getDurabilityMax() > 0
            && instance.getDurabilityValue() < instance.getDurabilityMax()
            && (effect.getRepairFull()
                || effect.getRepairAmount() != null && effect.getRepairAmount() > 0);
    }

    /**
     * 共通マスタと現在枠を照合し、指定エンチャント操作を一度以上実行できるか判定します。
     *
     * @param effect 使用するオーブ効果
     * @param model 装備マスタ
     * @param instance 装備個体
     * @param master 共通エンチャントマスタ
     * @return 空き枠・候補・重複禁止条件を満たす場合 {@code true}
     */
    static boolean canEnchant(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        @Nullable EnchantMaster master
    ) {
        ItemEquipment equipment = model.getEquipment();
        ItemOrbEnchantOperation operation = effect.getEnchantOperation();
        if (equipment == null || equipment.getEnchant() == null || operation == null || master == null) {
            return false;
        }
        EnchantEquipmentType equipmentType = enchantEquipmentType(equipment.getSlot());
        if (equipmentType == null) {
            return false;
        }
        EnchantTarget target = master.getTargets().stream()
            .filter(candidate -> candidate.getEquipmentType() == equipmentType)
            .findFirst()
            .orElse(null);
        if (target == null) {
            return false;
        }

        Map<String, EnchantEntry> uniqueCandidates = new LinkedHashMap<>();
        target.getEntries().stream()
            .filter(OrbEligibility::isValidEnchantEntry)
            .forEach(entry -> uniqueCandidates.putIfAbsent(normalize(entry.getEffectId()), entry));
        if (uniqueCandidates.isEmpty()) {
            return false;
        }

        int maxSlots = effectiveEnchantMaxSlots(equipment, instance.getTranscendenceRank());
        if (maxSlots <= 0) {
            return false;
        }
        Set<Integer> occupiedSlots = new HashSet<>();
        instance.getEnchants().forEach(enchant -> {
            if (enchant.getSlotIndex() >= 0 && enchant.getSlotIndex() < maxSlots) {
                occupiedSlots.add(enchant.getSlotIndex());
            }
        });

        if (operation == ItemOrbEnchantOperation.OVERWRITE_RANDOM) {
            if (occupiedSlots.isEmpty()) {
                return false;
            }
            // APIと同じく、上書き対象自身だけを重複比較集合から除外して候補を判定する。
            return instance.getEnchants().stream().anyMatch(overwriteTarget ->
                uniqueCandidates.values().stream().anyMatch(candidate ->
                    instance.getEnchants().stream().noneMatch(other ->
                        !other.getEnchantId().equalsIgnoreCase(overwriteTarget.getEnchantId())
                            && isSameEnchantEffect(candidate, other))));
        }

        int emptySlots = maxSlots - occupiedSlots.size();
        if (emptySlots <= 0) {
            return false;
        }
        long availableEffects = uniqueCandidates.values().stream()
            .filter(candidate -> instance.getEnchants().stream()
                .noneMatch(current -> isSameEnchantEffect(candidate, current)))
            .count();
        int requiredCandidates = operation == ItemOrbEnchantOperation.FILL_ALL_EMPTY ? emptySlots : 1;
        return availableEffects >= requiredCandidates;
    }

    private static boolean isValidEnchantEntry(@NotNull EnchantEntry entry) {
        return entry.getWeight() > 0
            && !entry.getEffectId().isBlank()
            && !entry.getStatus().isBlank()
            && !entry.getType().isBlank()
            && !entry.getValue().isBlank();
    }

    /** APIのlegacy行互換判定と同じ effectId または status/type/value範囲の意味一致。 */
    private static boolean isSameEnchantEffect(
        @NotNull EnchantEntry candidate,
        @NotNull EquipmentEnchant current
    ) {
        if (candidate.getEffectId().equalsIgnoreCase(current.getEffectId())) {
            return true;
        }
        if (!current.getEffectId().toLowerCase(Locale.ROOT).startsWith("legacy_")
            || !candidate.getStatus().equalsIgnoreCase(current.getStatus())
            || !candidate.getType().equalsIgnoreCase(current.getType())) {
            return false;
        }
        String[] range = candidate.getValue().trim().split("~", 2);
        try {
            BigDecimal min = new BigDecimal(range[0].trim());
            BigDecimal max = new BigDecimal((range.length == 2 ? range[1] : range[0]).trim());
            if (min.compareTo(max) > 0) {
                BigDecimal swap = min;
                min = max;
                max = swap;
            }
            BigDecimal value = BigDecimal.valueOf(current.getValue());
            return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * 現在状態までに適用される上書き定義を反映した強化上限を返します。
     *
     * @param equipment 装備マスタ定義
     * @param transcendenceRank 現在状態ランク
     * @return 有効な強化上限
     */
    static int effectiveEnhanceMaxLevel(@NotNull ItemEquipment equipment, int transcendenceRank) {
        ItemEquipmentEnhance enhance = equipment.getEnhance();
        int maxLevel = enhance == null ? 0 : enhance.getMaxLevel();
        List<ItemEquipmentTranscendence> ordered = equipment.getTranscendence().stream()
            .sorted(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
            .toList();
        for (ItemEquipmentTranscendence transcendence : ordered) {
            if (transcendence.getRank() > transcendenceRank) {
                break;
            }
            if (transcendence.getOverridesEnhanceMaxLevel() != null) {
                maxLevel = transcendence.getOverridesEnhanceMaxLevel();
            }
        }
        return Math.max(0, maxLevel);
    }

    /**
     * 現在状態までに適用される上書き定義を反映したエンチャント枠数を返します。
     *
     * @param equipment 装備マスタ定義
     * @param transcendenceRank 現在状態ランク
     * @return 有効な最大枠数
     */
    static int effectiveEnchantMaxSlots(@NotNull ItemEquipment equipment, int transcendenceRank) {
        int maxSlots = equipment.getEnchant() == null ? 0 : equipment.getEnchant().getMaxSlots();
        List<ItemEquipmentTranscendence> ordered = equipment.getTranscendence().stream()
            .sorted(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
            .toList();
        for (ItemEquipmentTranscendence transcendence : ordered) {
            if (transcendence.getRank() > transcendenceRank) {
                break;
            }
            if (transcendence.getOverridesEnchantMaxSlots() != null) {
                maxSlots = transcendence.getOverridesEnchantMaxSlots();
            }
        }
        return Math.max(0, maxSlots);
    }

    /**
     * オーブの一致方式で対象ランクを判定します。
     *
     * @param candidateRank 装備側で判定するランク
     * @param effect オーブ効果
     * @return 完全一致または上限以内なら {@code true}
     */
    private static boolean matchesRank(int candidateRank, @NotNull ItemOrbEffect effect) {
        Integer rank = effect.getRank();
        if (rank == null) {
            return true;
        }
        return effect.getRankMode() == ItemOrbRankMode.AT_MOST
            ? candidateRank <= rank
            : candidateRank == rank;
    }

    /**
     * 装備スロットを共通エンチャントマスタの装備グループへ変換します。
     *
     * @param slot 装備スロット
     * @return 対応グループ。非対応スロットは {@code null}
     */
    private static @Nullable EnchantEquipmentType enchantEquipmentType(@Nullable ItemEquipmentSlot slot) {
        if (slot == null) {
            return null;
        }
        return switch (slot) {
            case WEAPON, SUBWEAPON -> EnchantEquipmentType.WEAPON;
            case HEAD, CHEST, LEGS, FEET -> EnchantEquipmentType.ARMOR;
            case ACCESSORY -> EnchantEquipmentType.ACCESSORY;
            default -> null;
        };
    }

    /**
     * 効果IDを大小文字非依存比較用へ正規化します。
     *
     * @param value 効果ID
     * @return trim済み小文字ID
     */
    private static @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** 次の装備強化に使用するレベル定義です。 */
    record EnhancementPlan(int targetLevel, @NotNull ItemEquipmentEnhanceLevel levelDefinition) {
    }

    /** 即時次段階へ進める状態変化定義です。 */
    record TranscendencePlan(@NotNull ItemEquipmentTranscendence definition) {
    }
}
