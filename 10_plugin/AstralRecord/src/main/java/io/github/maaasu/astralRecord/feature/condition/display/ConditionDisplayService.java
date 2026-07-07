package io.github.maaasu.astralRecord.feature.condition.display;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Comparator;

/**
 * 状態異常の短時間表示と recurring particle を扱います。
 */
public final class ConditionDisplayService {
    private static final int BURNING_VISUAL_TICKS = 30;

    private final ParticleDisplayService particleDisplayService;
    private final MobVanillaEffectProtectionService mobVanillaEffectProtectionService;

    public ConditionDisplayService(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull MobVanillaEffectProtectionService mobVanillaEffectProtectionService
    ) {
        this.particleDisplayService = particleDisplayService;
        this.mobVanillaEffectProtectionService = mobVanillaEffectProtectionService;
    }

    /**
     * 状態異常付与時の即時表示を行います。
     *
     * @param condition 付与または更新された状態異常
     * @param activeConditions 対象に残っている状態異常一覧
     */
    public void showApplied(
            @NotNull ActiveCondition condition,
            @NotNull Collection<ActiveCondition> activeConditions
    ) {
        refreshTargetDisplay(condition.target(), activeConditions);
        pulse(condition);
    }

    /**
     * 状態異常対象の継続表示を更新します。
     *
     * @param target 対象 entity
     * @param activeConditions 対象に残っている状態異常一覧
     */
    public void refreshTargetDisplay(
            @NotNull AstEntity target,
            @NotNull Collection<ActiveCondition> activeConditions
    ) {
        if (activeConditions.isEmpty()) {
            clearAll(target);
            return;
        }

        if (target.isPlayer() && target.player() != null) {
            target.player().getBukkit().sendActionBar(Component.text(formatTopConditions(activeConditions)));
        }

        for (ActiveCondition condition : activeConditions) {
            if (condition.type() == ConditionType.BURNING) {
                applyBurningVisual(target, BURNING_VISUAL_TICKS);
            }
            spawnRecurringParticle(condition);
        }
    }

    /**
     * 状態異常の単発 pulse 表示を行います。
     *
     * @param condition 表示対象状態異常
     */
    public void pulse(@NotNull ActiveCondition condition) {
        Location location = condition.target().location().clone().add(0.0D, 0.9D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(location, particle(condition.type()));
    }

    /**
     * 対象の指定状態異常表示を解除します。
     *
     * @param target 対象 entity
     * @param type 解除する状態異常種別
     * @param remainingConditions 対象に残っている状態異常一覧
     */
    public void clearCondition(
            @NotNull AstEntity target,
            @NotNull ConditionType type,
            @NotNull Collection<ActiveCondition> remainingConditions
    ) {
        if (type == ConditionType.BURNING) {
            applyBurningVisual(target, 0);
        }
        refreshTargetDisplay(target, remainingConditions);
    }

    /**
     * 対象の全状態異常表示を解除します。
     *
     * @param target 対象 entity
     */
    public void clearAll(@NotNull AstEntity target) {
        applyBurningVisual(target, 0);
        if (target.isPlayer() && target.player() != null) {
            target.player().getBukkit().sendActionBar(Component.empty());
        }
    }

    private void applyBurningVisual(@NotNull AstEntity target, int visualTicks) {
        if (target.isPlayer() && target.player() != null) {
            target.player().getBukkit().setFireTicks(visualTicks);
            return;
        }
        if (target.isMob() && target.mob() != null) {
            Entity entity = target.mob().bukkitEntityId() == null
                    ? null
                    : org.bukkit.Bukkit.getEntity(target.mob().bukkitEntityId());
            if (entity != null) {
                mobVanillaEffectProtectionService.applyPluginFireTicks(entity, visualTicks);
            }
        }
    }

    private void spawnRecurringParticle(@NotNull ActiveCondition condition) {
        Location location = condition.target().location().clone().add(0.0D, 0.8D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(location, particle(condition.type()));
    }

    private @NotNull SharedParticleDefinition particle(@NotNull ConditionType type) {
        return switch (type) {
            case BURNING -> SharedParticleDefinitions.CONDITION_BURNING_FLAME;
            case POISON -> SharedParticleDefinitions.CONDITION_POISON_DUST;
            case BLEEDING -> SharedParticleDefinitions.CONDITION_BLEEDING_DUST;
            case CHILLED, FROZEN -> SharedParticleDefinitions.CONDITION_ICE_DUST;
            case STUNNED -> SharedParticleDefinitions.CONDITION_STUN_SPARK;
            case SILENCED -> SharedParticleDefinitions.CONDITION_SILENCE_DUST;
            case ATTACK_DISABLED -> SharedParticleDefinitions.CONDITION_ATTACK_DISABLED_DUST;
            case INVULNERABLE -> SharedParticleDefinitions.CONDITION_INVULNERABLE_DUST;
            case VULNERABLE -> SharedParticleDefinitions.CONDITION_VULNERABLE_DUST;
        };
    }

    private @NotNull String formatTopConditions(@NotNull Collection<ActiveCondition> conditions) {
        return conditions.stream()
                .sorted(Comparator.comparingInt(condition -> priority(condition.type())))
                .limit(3)
                .map(this::formatCondition)
                .reduce((left, right) -> left + "  " + right)
                .orElse("");
    }

    private @NotNull String formatCondition(@NotNull ActiveCondition condition) {
        long remainingSeconds = Math.max(0L, (condition.expiresAtMs() - System.currentTimeMillis() + 999L) / 1000L);
        String stack = condition.stack() > 1 ? " x" + condition.stack() : "";
        return icon(condition.type()) + " " + condition.type().displayName() + stack + " " + remainingSeconds + "s";
    }

    private @NotNull String icon(@NotNull ConditionType type) {
        return switch (type) {
            case BURNING -> "[火]";
            case POISON -> "[毒]";
            case BLEEDING -> "[血]";
            case CHILLED -> "[冷]";
            case FROZEN -> "[氷]";
            case STUNNED -> "[痺]";
            case SILENCED -> "[黙]";
            case ATTACK_DISABLED -> "[封]";
            case INVULNERABLE -> "[無]";
            case VULNERABLE -> "[脆]";
        };
    }

    private int priority(@NotNull ConditionType type) {
        return switch (type) {
            case FROZEN -> 0;
            case STUNNED -> 1;
            case INVULNERABLE -> 2;
            case BURNING -> 3;
            case POISON -> 4;
            case BLEEDING -> 5;
            case CHILLED -> 6;
            case SILENCED -> 7;
            case ATTACK_DISABLED -> 8;
            case VULNERABLE -> 9;
        };
    }
}
