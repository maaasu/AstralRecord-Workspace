package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEnchantOperation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * プレイヤー待機中にAPIを呼ばず、オーブによる装備状態の完成形を計算する純粋な補助です。
 * 支払い・inventoryへのルーン返却・dirty保存は呼出側の同一state lockで確定します。
 */
final class OrbLocalMutationCalculator {
    private OrbLocalMutationCalculator() {
    }

    /**
     * 現在のマスタと装備個体から、指定オーブの次の完成状態を計算します。
     *
     * @param effect オーブ効果
     * @param model 対象装備マスタ
     * @param current state lock内で再取得した現在装備個体
     * @param enchantMaster エンチャント用共通マスタ。それ以外ではnull
     * @param runeItem ルーン装着対象。それ以外ではnull
     * @param runeSlotIndex ルーン脱着対象slot。それ以外ではnull
     * @return 再検証に成功した完成状態。条件不成立時はnull
     */
    static @Nullable LocalResult apply(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance current,
        @Nullable EnchantMaster enchantMaster,
        @Nullable ItemModel runeItem,
        @Nullable Integer runeSlotIndex
    ) {
        return switch (effect.getType()) {
            case ENHANCE -> enhance(effect, model, current);
            case REPAIR -> repair(effect, model, current);
            case TRANSCENDENCE -> transcendence(effect, model, current);
            case ENCHANT -> enchant(effect, model, current, enchantMaster);
            case RUNE_ATTACH -> attachRune(model, current, runeItem);
            case RUNE_DETACH -> detachRune(current, runeSlotIndex);
            case SIGIL_ATTACH, SIGIL_DETACH -> null;
        };
    }

    private static @Nullable LocalResult enhance(@NotNull ItemOrbEffect effect, @NotNull ItemModel model,
        @NotNull EquipmentInstance current) {
        OrbEligibility.EnhancementPlan plan = OrbEligibility.resolveEnhancement(effect, model, current);
        if (plan == null || model.getEquipment() == null) return null;
        ItemEquipment equipment = model.getEquipment();
        ItemEquipmentEnhanceLevel definition = plan.levelDefinition();
        double successRate = Math.max(0.0D, Math.min(1.0D, definition.getSuccessRate()));
        boolean succeeded = ThreadLocalRandom.current().nextDouble() < successRate;
        ItemEquipmentEnhanceFailAction failAction = definition.getFailAction();
        int maxLevel = OrbEligibility.effectiveEnhanceMaxLevel(equipment, current.getTranscendenceRank());
        int appliedLevel = succeeded ? plan.targetLevel() : switch (failAction) {
            case SET_LEVEL -> Math.max(0, Math.min(maxLevel, definition.getFailTargetLevel() == null
                ? current.getEnhanceLevel() : definition.getFailTargetLevel()));
            case DECREASE_ONE -> Math.max(0, current.getEnhanceLevel() - 1);
            case NONE -> current.getEnhanceLevel();
        };
        int durabilityDelta = durabilityBonus(equipment, appliedLevel) - durabilityBonus(equipment, current.getEnhanceLevel());
        int durabilityMax = current.getDurabilityMax() + durabilityDelta;
        int durabilityValue = Math.max(0, Math.min(durabilityMax, current.getDurabilityValue() + durabilityDelta));
        return LocalResult.enhancement(replace(current, appliedLevel, current.getRuneMaxSlots(), current.getTranscendenceRank(),
            durabilityMax, durabilityValue, current.getEnchants(), current.getRunes()), succeeded, successRate, failAction);
    }

    private static int durabilityBonus(@NotNull ItemEquipment equipment, int level) {
        return equipment.getEnhance().getLevels().stream().filter(definition -> definition.getLevel() <= level)
            .map(ItemEquipmentEnhanceLevel::getDurabilityBonus).filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue).sum();
    }

    private static @Nullable LocalResult repair(@NotNull ItemOrbEffect effect, @NotNull ItemModel model,
        @NotNull EquipmentInstance current) {
        if (!OrbEligibility.canRepair(effect, model, current)) return null;
        int repaired = effect.getRepairFull() ? current.getDurabilityMax() - current.getDurabilityValue()
            : Math.min(current.getDurabilityMax() - current.getDurabilityValue(), effect.getRepairAmount());
        if (repaired <= 0) return null;
        return LocalResult.repair(replace(current, current.getEnhanceLevel(), current.getRuneMaxSlots(),
            current.getTranscendenceRank(), current.getDurabilityMax(), current.getDurabilityValue() + repaired,
            current.getEnchants(), current.getRunes()), repaired);
    }

    private static @Nullable LocalResult transcendence(@NotNull ItemOrbEffect effect, @NotNull ItemModel model,
        @NotNull EquipmentInstance current) {
        OrbEligibility.TranscendencePlan plan = OrbEligibility.resolveTranscendence(effect, model, current);
        if (plan == null) return null;
        String name = plan.definition().getName();
        if (name == null || name.isBlank()) name = "次の状態";
        return LocalResult.transcendence(replace(current, current.getEnhanceLevel(), current.getRuneMaxSlots(),
            plan.definition().getRank(), current.getDurabilityMax(), current.getDurabilityValue(),
            current.getEnchants(), current.getRunes()), name);
    }

    private static @Nullable LocalResult enchant(@NotNull ItemOrbEffect effect, @NotNull ItemModel model,
        @NotNull EquipmentInstance current, @Nullable EnchantMaster master) {
        if (!OrbEligibility.canEnchant(effect, model, current, master) || master == null || model.getEquipment() == null) return null;
        EnchantEquipmentType type = enchantEquipmentType(model.getEquipment().getSlot());
        EnchantTarget target = master.getTargets().stream().filter(candidate -> candidate.getEquipmentType() == type)
            .findFirst().orElse(null);
        if (target == null) return null;
        Map<String, EnchantEntry> unique = new LinkedHashMap<>();
        target.getEntries().stream().filter(OrbLocalMutationCalculator::isValidEnchantEntry)
            .forEach(entry -> unique.putIfAbsent(normalize(entry.getEffectId()), entry));
        List<EquipmentEnchant> existing = new ArrayList<>(current.getEnchants());
        ItemOrbEnchantOperation operation = effect.getEnchantOperation();
        List<Integer> slots;
        List<EnchantEntry> candidates;
        if (operation == ItemOrbEnchantOperation.OVERWRITE_RANDOM) {
            List<OverwriteChoice> viable = existing.stream().map(overwrite -> new OverwriteChoice(overwrite,
                unique.values().stream().filter(candidate -> existing.stream().noneMatch(other ->
                    !other.getEnchantId().equalsIgnoreCase(overwrite.getEnchantId()) && sameEnchantEffect(candidate, other)))
                    .toList())).filter(choice -> !choice.candidates().isEmpty()).toList();
            if (viable.isEmpty()) return null;
            OverwriteChoice chosen = viable.get(ThreadLocalRandom.current().nextInt(viable.size()));
            existing.removeIf(enchant -> enchant.getEnchantId().equalsIgnoreCase(chosen.target().getEnchantId()));
            slots = List.of(chosen.target().getSlotIndex());
            candidates = chosen.candidates();
        } else {
            int maxSlots = OrbEligibility.effectiveEnchantMaxSlots(model.getEquipment(), current.getTranscendenceRank());
            Set<Integer> occupied = new HashSet<>();
            existing.forEach(enchant -> occupied.add(enchant.getSlotIndex()));
            List<Integer> empty = java.util.stream.IntStream.range(0, maxSlots).filter(slot -> !occupied.contains(slot)).boxed().toList();
            slots = switch (operation) {
                case FILL_ONE_EMPTY -> empty.isEmpty() ? List.of() : List.of(empty.getFirst());
                case FILL_ALL_EMPTY -> empty;
                default -> List.of();
            };
            candidates = unique.values().stream().filter(candidate -> existing.stream()
                .noneMatch(enchant -> sameEnchantEffect(candidate, enchant))).toList();
        }
        List<EnchantEntry> selected = selectWeightedWithoutReplacement(candidates, slots.size());
        if (slots.isEmpty() || selected.size() != slots.size()) return null;
        for (int index = 0; index < selected.size(); index++) {
            EnchantEntry entry = selected.get(index);
            existing.add(new EquipmentEnchant(UUID.randomUUID().toString(), current.getEquipmentInstanceId(), slots.get(index),
                master.getId(), entry.getEffectId(), entry.getStatus(), entry.getType(), resolveValue(entry.getValue())));
        }
        existing.sort(Comparator.comparingInt(EquipmentEnchant::getSlotIndex));
        return LocalResult.enchant(replace(current, current.getEnhanceLevel(), current.getRuneMaxSlots(),
            current.getTranscendenceRank(), current.getDurabilityMax(), current.getDurabilityValue(), existing, current.getRunes()));
    }

    private static @Nullable LocalResult attachRune(@NotNull ItemModel model, @NotNull EquipmentInstance current,
        @Nullable ItemModel runeItem) {
        if (runeItem == null || runeItem.getRune() == null || model.getEquipment() == null || model.getEquipment().getRune() == null
            || current.getRuneMaxSlots() <= current.getRunes().size() || current.getEnhanceLevel() < runeItem.getRune().getRequiredEnhanceLevel()
            || !RuneTargetMatcher.matches(runeItem.getRune(), model.getEquipment())) return null;
        int slot = nextRuneSlot(current.getRunes(), current.getRuneMaxSlots());
        if (slot < 0) return null;
        List<EquipmentRune> runes = new ArrayList<>(current.getRunes());
        runes.add(new EquipmentRune(UUID.randomUUID().toString(), current.getEquipmentInstanceId(), slot, runeItem.getId()));
        return LocalResult.rune(replace(current, current.getEnhanceLevel(), current.getRuneMaxSlots(), current.getTranscendenceRank(),
            current.getDurabilityMax(), current.getDurabilityValue(), current.getEnchants(), runes), null);
    }

    private static @Nullable LocalResult detachRune(@NotNull EquipmentInstance current, @Nullable Integer slotIndex) {
        if (slotIndex == null) return null;
        EquipmentRune target = current.getRunes().stream().filter(rune -> rune.getSlotIndex() == slotIndex).findFirst().orElse(null);
        if (target == null) return null;
        List<EquipmentRune> runes = current.getRunes().stream().filter(rune -> rune != target).toList();
        return LocalResult.rune(replace(current, current.getEnhanceLevel(), current.getRuneMaxSlots(), current.getTranscendenceRank(),
            current.getDurabilityMax(), current.getDurabilityValue(), current.getEnchants(), runes), target.getItemId());
    }

    private static @NotNull EquipmentInstance replace(@NotNull EquipmentInstance current, int enhanceLevel, int runeMaxSlots,
        int transcendenceRank, int durabilityMax, int durabilityValue, @NotNull List<EquipmentEnchant> enchants,
        @NotNull List<EquipmentRune> runes) {
        return new EquipmentInstance(current.getEquipmentInstanceId(), current.getAccountId(), current.getItemId(), enhanceLevel,
            runeMaxSlots, transcendenceRank, durabilityMax, durabilityValue, current.getCreatedAt(), LocalDateTime.now().toString(),
            current.getStatRolls(), List.copyOf(enchants), List.copyOf(runes));
    }

    private static @Nullable EnchantEquipmentType enchantEquipmentType(@Nullable ItemEquipmentSlot slot) {
        if (slot == null) return null;
        return switch (slot) {
            case WEAPON, SUBWEAPON -> EnchantEquipmentType.WEAPON;
            case HEAD, CHEST, LEGS, FEET -> EnchantEquipmentType.ARMOR;
            case ACCESSORY -> EnchantEquipmentType.ACCESSORY;
            default -> null;
        };
    }

    private static boolean isValidEnchantEntry(@NotNull EnchantEntry entry) {
        return entry.getWeight() > 0 && !entry.getEffectId().isBlank() && !entry.getStatus().isBlank()
            && !entry.getType().isBlank() && !entry.getValue().isBlank();
    }

    private static boolean sameEnchantEffect(@NotNull EnchantEntry candidate, @NotNull EquipmentEnchant current) {
        if (candidate.getEffectId().equalsIgnoreCase(current.getEffectId())) return true;
        if (!current.getEffectId().toLowerCase(Locale.ROOT).startsWith("legacy_") || !candidate.getStatus().equalsIgnoreCase(current.getStatus())
            || !candidate.getType().equalsIgnoreCase(current.getType())) return false;
        String[] range = candidate.getValue().trim().split("~", 2);
        try {
            BigDecimal first = new BigDecimal(range[0].trim());
            BigDecimal second = new BigDecimal((range.length == 2 ? range[1] : range[0]).trim());
            BigDecimal value = BigDecimal.valueOf(current.getValue());
            return value.compareTo(first.min(second)) >= 0 && value.compareTo(first.max(second)) <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static @NotNull List<EnchantEntry> selectWeightedWithoutReplacement(@NotNull List<EnchantEntry> candidates, int count) {
        List<EnchantEntry> remaining = new ArrayList<>(candidates);
        List<EnchantEntry> selected = new ArrayList<>();
        while (selected.size() < count && !remaining.isEmpty()) {
            long total = remaining.stream().mapToLong(EnchantEntry::getWeight).sum();
            if (total <= 0) return List.of();
            long rolled = ThreadLocalRandom.current().nextLong(total);
            int index = 0;
            while (rolled >= remaining.get(index).getWeight()) rolled -= remaining.get(index++).getWeight();
            selected.add(remaining.remove(index));
        }
        return selected;
    }

    private static double resolveValue(@NotNull String source) {
        String[] range = source.trim().split("~", 2);
        try {
            double first = Double.parseDouble(range[0].trim());
            double second = Double.parseDouble((range.length == 2 ? range[1] : range[0]).trim());
            double min = Math.min(first, second), max = Math.max(first, second);
            return min == max ? min : ThreadLocalRandom.current().nextDouble(min, Math.nextUp(max));
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("Invalid enchant value: " + source);
        }
    }

    private static int nextRuneSlot(@NotNull List<EquipmentRune> runes, int maxSlots) {
        Set<Integer> occupied = new HashSet<>();
        runes.forEach(rune -> occupied.add(rune.getSlotIndex()));
        for (int index = 0; index < Math.max(0, maxSlots); index++) if (!occupied.contains(index)) return index;
        return -1;
    }

    private static @NotNull String normalize(@NotNull String value) { return value.trim().toLowerCase(Locale.ROOT); }

    private record OverwriteChoice(@NotNull EquipmentEnchant target, @NotNull List<EnchantEntry> candidates) { }

    /** ローカル確定した完成状態とGUI結果に必要な付帯情報です。 */
    record LocalResult(@NotNull EquipmentInstance instance, boolean enhancementSucceeded,
                       @Nullable ItemEquipmentEnhanceFailAction failAction, double successRate, int repairedAmount,
                       @Nullable String transitionName, @Nullable String returnedRuneItemId) {
        private static @NotNull LocalResult enhancement(@NotNull EquipmentInstance instance, boolean succeeded,
            double successRate, @NotNull ItemEquipmentEnhanceFailAction failAction) {
            return new LocalResult(instance, succeeded, failAction, successRate, 0, null, null);
        }
        private static @NotNull LocalResult repair(@NotNull EquipmentInstance instance, int repairedAmount) {
            return new LocalResult(instance, false, null, 0.0D, repairedAmount, null, null);
        }
        private static @NotNull LocalResult enchant(@NotNull EquipmentInstance instance) {
            return new LocalResult(instance, false, null, 0.0D, 0, null, null);
        }
        private static @NotNull LocalResult rune(@NotNull EquipmentInstance instance, @Nullable String returnedRuneItemId) {
            return new LocalResult(instance, false, null, 0.0D, 0, null, returnedRuneItemId);
        }
        private static @NotNull LocalResult transcendence(@NotNull EquipmentInstance instance, @NotNull String name) {
            return new LocalResult(instance, false, null, 0.0D, 0, name, null);
        }
    }
}
