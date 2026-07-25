package io.github.maaasu.astralRecord.feature.condition.display;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 状態異常の短時間表示と recurring particle を扱います。 */
public final class ConditionDisplayService {
    private static final int BURNING_VISUAL_TICKS = 30;
    private static final int POTION_VISUAL_MIN_TICKS = 30;

    private final ParticleDisplayService particleDisplayService;
    private final MobVanillaEffectProtectionService mobVanillaEffectProtectionService;
    private final Map<UUID, UUID> frozenDisplays = new ConcurrentHashMap<>();

    public ConditionDisplayService(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull MobVanillaEffectProtectionService mobVanillaEffectProtectionService
    ) {
        this.particleDisplayService = particleDisplayService;
        this.mobVanillaEffectProtectionService = mobVanillaEffectProtectionService;
    }

    /** 状態異常付与時の即時表示を行います。 */
    public void showApplied(
            @NotNull ActiveCondition condition,
            @NotNull Collection<ActiveCondition> activeConditions
    ) {
        refreshTargetDisplay(condition.target(), activeConditions);
        pulse(condition);
    }

    /** 状態異常対象の継続表示を更新します。 */
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
            applyVanillaVisual(condition);
            if (condition.type() == ConditionType.FROZEN) {
                refreshFrozenDisplay(target);
            }
            spawnRecurringParticle(condition);
        }
    }

    /** 状態異常の単発 pulse 表示を行います。 */
    public void pulse(@NotNull ActiveCondition condition) {
        SharedParticleDefinition particle = particle(condition.type());
        if (particle != null) {
            Location location = condition.target().location().clone().add(0.0D, 0.9D, 0.0D);
            particleDisplayService.spawnForNearbyViewers(location, particle);
        }
        if (condition.type() == ConditionType.POISON && condition.target().location().getWorld() != null) {
            condition.target().location().getWorld().playSound(
                    condition.target().location(),
                    Sound.ENTITY_WITCH_DRINK,
                    SoundCategory.PLAYERS,
                    0.45F,
                    1.35F
            );
        }
    }

    /** 対象の指定状態異常表示を解除します。 */
    public void clearCondition(
            @NotNull AstEntity target,
            @NotNull ConditionType type,
            @NotNull Collection<ActiveCondition> remainingConditions
    ) {
        clearVanillaVisual(target, type);
        if (type == ConditionType.FROZEN) {
            clearFrozenDisplay(target.id());
        }
        refreshTargetDisplay(target, remainingConditions);
    }

    /** 対象の全状態異常表示を解除します。 */
    public void clearAll(@NotNull AstEntity target) {
        applyBurningVisual(target, 0);
        removePotionVisual(target, PotionEffectType.POISON);
        removePotionVisual(target, PotionEffectType.BLINDNESS);
        clearFrozenDisplay(target.id());
        if (target.isPlayer() && target.player() != null) {
            target.player().getBukkit().sendActionBar(Component.empty());
        }
    }

    private void applyVanillaVisual(@NotNull ActiveCondition condition) {
        switch (condition.type()) {
            case BURNING -> applyBurningVisual(condition.target(), BURNING_VISUAL_TICKS);
            case POISON -> applyPotionVisual(condition, PotionEffectType.POISON);
            case BLINDNESS -> applyPotionVisual(condition, PotionEffectType.BLINDNESS);
            default -> {
            }
        }
    }

    private void clearVanillaVisual(@NotNull AstEntity target, @NotNull ConditionType type) {
        switch (type) {
            case BURNING -> applyBurningVisual(target, 0);
            case POISON -> removePotionVisual(target, PotionEffectType.POISON);
            case BLINDNESS -> removePotionVisual(target, PotionEffectType.BLINDNESS);
            default -> {
            }
        }
    }

    private void applyPotionVisual(@NotNull ActiveCondition condition, @NotNull PotionEffectType type) {
        LivingEntity living = resolveLivingEntity(condition.target());
        if (living == null) {
            return;
        }
        long remainingMs = Math.max(0L, condition.expiresAtMs() - System.currentTimeMillis());
        int durationTicks = Math.max(POTION_VISUAL_MIN_TICKS, (int) Math.ceil(remainingMs / 50.0D));
        living.addPotionEffect(new PotionEffect(type, durationTicks, 0, true, false, true));
    }

    private void removePotionVisual(@NotNull AstEntity target, @NotNull PotionEffectType type) {
        LivingEntity living = resolveLivingEntity(target);
        if (living != null) {
            living.removePotionEffect(type);
        }
    }

    private void applyBurningVisual(@NotNull AstEntity target, int visualTicks) {
        Entity entity = resolveEntity(target);
        if (entity == null) {
            return;
        }
        if (target.isPlayer()) {
            entity.setFireTicks(visualTicks);
        } else {
            mobVanillaEffectProtectionService.applyPluginFireTicks(entity, visualTicks);
        }
    }

    private void refreshFrozenDisplay(@NotNull AstEntity target) {
        Entity current = frozenDisplays.containsKey(target.id())
                ? Bukkit.getEntity(frozenDisplays.get(target.id()))
                : null;
        Location location = target.location();
        if (location.getWorld() == null) {
            return;
        }

        BlockDisplay display;
        if (current instanceof BlockDisplay existing && existing.isValid()) {
            display = existing;
            display.teleport(location);
        } else {
            display = location.getWorld().spawn(location, BlockDisplay.class);
            display.setBlock(Material.ICE.createBlockData());
            display.setTeleportDuration(10);
            display.setViewRange(40.0F);
            display.addScoreboardTag("astralrecord_condition_frozen");
            frozenDisplays.put(target.id(), display.getUniqueId());
        }

        Entity targetEntity = resolveEntity(target);
        float width = targetEntity == null ? 1.0F : (float) Math.max(0.9D, targetEntity.getWidth() + 0.35D);
        float height = targetEntity == null ? 2.0F : (float) Math.max(1.0D, targetEntity.getHeight() + 0.25D);
        display.setTransformation(new Transformation(
                new Vector3f(-width / 2.0F, 0.0F, -width / 2.0F),
                new Quaternionf(),
                new Vector3f(width, height, width),
                new Quaternionf()
        ));
    }

    private void clearFrozenDisplay(@NotNull UUID targetId) {
        UUID displayId = frozenDisplays.remove(targetId);
        Entity display = displayId == null ? null : Bukkit.getEntity(displayId);
        if (display != null) {
            display.remove();
        }
    }

    private void spawnRecurringParticle(@NotNull ActiveCondition condition) {
        SharedParticleDefinition particle = particle(condition.type());
        if (particle == null) {
            return;
        }
        Location location = condition.target().location().clone().add(0.0D, 0.8D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(location, particle);
    }

    private @Nullable SharedParticleDefinition particle(@NotNull ConditionType type) {
        return switch (type) {
            case BURNING -> SharedParticleDefinitions.CONDITION_BURNING_FLAME;
            case POISON -> SharedParticleDefinitions.CONDITION_POISON_DUST;
            case CHILLED, FROZEN -> SharedParticleDefinitions.CONDITION_ICE_DUST;
            case SHOCKED -> SharedParticleDefinitions.CONDITION_SHOCKED_SPARK;
            case WEAKNESS -> SharedParticleDefinitions.CONDITION_WEAKNESS_DUST;
            case HEALING_INHIBITION -> SharedParticleDefinitions.CONDITION_HEALING_INHIBITION_DUST;
            case BLINDNESS -> null;
        };
    }

    private @Nullable Entity resolveEntity(@NotNull AstEntity target) {
        if (target.isPlayer() && target.player() != null) {
            return target.player().getBukkit();
        }
        if (target.isMob() && target.mob() != null && target.mob().bukkitEntityId() != null) {
            return Bukkit.getEntity(target.mob().bukkitEntityId());
        }
        return target.bukkitEntity();
    }

    private @Nullable LivingEntity resolveLivingEntity(@NotNull AstEntity target) {
        Entity entity = resolveEntity(target);
        return entity instanceof LivingEntity living ? living : null;
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
        return icon(condition.type()) + " " + condition.type().displayName() + " " + remainingSeconds + "s";
    }

    private @NotNull String icon(@NotNull ConditionType type) {
        return switch (type) {
            case BURNING -> "[火]";
            case FROZEN -> "[氷]";
            case CHILLED -> "[冷]";
            case SHOCKED -> "[雷]";
            case POISON -> "[毒]";
            case BLINDNESS -> "[盲]";
            case WEAKNESS -> "[衰]";
            case HEALING_INHIBITION -> "[阻]";
        };
    }

    private int priority(@NotNull ConditionType type) {
        return switch (type) {
            case FROZEN -> 0;
            case SHOCKED -> 1;
            case BURNING -> 2;
            case POISON -> 3;
            case CHILLED -> 4;
            case BLINDNESS -> 5;
            case WEAKNESS -> 6;
            case HEALING_INHIBITION -> 7;
        };
    }
}
