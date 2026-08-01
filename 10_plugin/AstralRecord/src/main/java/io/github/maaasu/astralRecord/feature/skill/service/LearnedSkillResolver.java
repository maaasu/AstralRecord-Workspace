package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillStatusModifierDefinition;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 習得レベルと装着済みシジルから、個体専用の発動定義を組み立てます。 */
public final class LearnedSkillResolver {
    private final ItemService itemService;

    public LearnedSkillResolver(@NotNull ItemService itemService) {
        this.itemService = itemService;
    }

    public @NotNull ResolvedLearnedSkill resolve(
        @NotNull SkillDefinition base,
        @NotNull LearnedSkillInstance learned
    ) {
        long cooldownDelta = 0L;
        double resourceDelta = 0.0D;
        long castTimeDelta = 0L;
        Map<String, Double> paramDeltas = new LinkedHashMap<>();
        Map<StatusType, Double> statusBonuses = new HashMap<>();

        for (SkillLevelDefinition level : base.getLevels()) {
            if (level.getLevel() > learned.getLevel()) continue;
            cooldownDelta += level.getCooldownTicksDelta();
            resourceDelta += level.getResourceCostDelta();
            castTimeDelta += level.getCastTimeTicksDelta();
            level.getParamDeltas().forEach((key, value) -> paramDeltas.merge(key, value, Double::sum));
            for (SkillStatusModifierDefinition modifier : level.getStatusModifiers()) {
                addStatusBonus(statusBonuses, modifier.getStatus(), modifier.getValue());
            }
        }

        Set<String> sigilIds = new LinkedHashSet<>();
        Set<String> equipGroups = new LinkedHashSet<>();
        int sigilSlots = base.getSigilSlotsByLevel().stream()
            .filter(slot -> slot.getLevel() <= learned.getLevel())
            .mapToInt(io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition::getSlots)
            .max()
            .orElse(0);
        for (LearnedSkillSigil attached : learned.getSigils()) {
            if (attached.getSlotIndex() < 0 || attached.getSlotIndex() >= sigilSlots) continue;
            if (base.getAllowedSigilIds().stream()
                .noneMatch(allowed -> allowed.equalsIgnoreCase(attached.getSigilId()))) continue;
            ItemModel item = itemService.findLoadedById(attached.getSigilId());
            if (item == null || item.getSigil() == null) continue;
            if (!item.getSigil().getEquipGroupId().equalsIgnoreCase(attached.getEquipGroupId())) continue;
            if (!equipGroups.add(item.getSigil().getEquipGroupId())) continue;
            sigilIds.add(attached.getSigilId());
            for (ItemSigilModifier modifier : item.getSigil().getModifiers()) {
                addStatusBonus(statusBonuses, modifier.getStatus(), modifier.getValue());
            }
        }

        Map<String, Object> params = new LinkedHashMap<>(base.getParams());
        paramDeltas.forEach((key, delta) -> {
            Object current = params.get(key);
            double baseValue = current instanceof Number number ? number.doubleValue() : 0.0D;
            params.put(key, baseValue + delta);
        });

        Double resourceCost = base.getResourceCost();
        double resolvedResourceCost = Math.max(0.0D, (resourceCost == null ? base.getManaCost() : resourceCost) + resourceDelta);
        SkillDefinition resolved = new SkillDefinition(
            base.getId(),
            base.getImplementationId(),
            base.getName(),
            base.getDescription(),
            base.getIcon(),
            base.getLore(),
            Math.max(0L, base.getCooldownTicks() + cooldownDelta),
            base.getManaCost(),
            Math.max(0L, base.getCastTimeTicks() + castTimeDelta),
            base.getRequiredLevel(),
            base.getOnCastSound(),
            params,
            base.getTags(),
            base.getKind(),
            base.getPassiveBindRequired(),
            base.getResourceType(),
            resolvedResourceCost,
            base.getCooldownId(),
            base.getMaxLevel(),
            base.getLevels(),
            base.getSigilSlotsByLevel(),
            base.getAllowedSigilIds()
        );
        return new ResolvedLearnedSkill(learned, resolved, statusBonuses, sigilIds);
    }

    private static void addStatusBonus(Map<StatusType, Double> target, String rawStatus, double value) {
        StatusType type = StatusType.fromId(rawStatus.trim().toUpperCase(java.util.Locale.ROOT));
        if (type != null && Double.isFinite(value)) {
            target.merge(type, value, Double::sum);
        }
    }
}
