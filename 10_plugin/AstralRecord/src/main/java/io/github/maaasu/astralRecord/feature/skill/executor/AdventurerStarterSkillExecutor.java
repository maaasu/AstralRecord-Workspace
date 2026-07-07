package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.item.executor.WeaponAttackSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.MobSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 冒険者が序盤に習得する発動スキルの演出と攻撃処理を担います。
 * <p>
 * 実ダメージは既存の武器攻撃 executor と同じ custom combat 経路へ委譲し、
 * ここでは通常攻撃と区別できる予兆・軌跡・着弾前演出を追加します。
 */
public final class AdventurerStarterSkillExecutor implements SkillExecutor {

    private static final String IMPLEMENTATION_ID = "adventurer_starter_attack";
    private static final String STYLE_PARAM = "starterStyle";

    private final ParticleDisplayService particleDisplayService;
    private final WeaponAttackSkillExecutor attackExecutor;

    /**
     * 冒険者序盤スキル executor を構築します。
     *
     * @param particleDisplayService パーティクル表示サービス
     * @param damageService          custom combat ダメージサービス
     */
    public AdventurerStarterSkillExecutor(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DamageService damageService
    ) {
        this(particleDisplayService, damageService, null);
    }

    public AdventurerStarterSkillExecutor(
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull DamageService damageService,
            @Nullable ConditionService conditionService
    ) {
        this.particleDisplayService = particleDisplayService;
        this.attackExecutor = new WeaponAttackSkillExecutor(particleDisplayService, damageService, conditionService);
    }

    @Override
    public @NotNull String implementationId() {
        return IMPLEMENTATION_ID;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        EffectOrigin origin = resolveEffectOrigin(context);
        if (origin != null) {
            playStarterPresentation(context.skill(), origin);
        }
        return attackExecutor.cast(context);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        readStyle(skill);
        attackExecutor.validateParams(skill);
    }

    private void playStarterPresentation(@NotNull SkillDefinition skill, @NotNull EffectOrigin origin) {
        StarterStyle style = readStyle(skill);
        switch (style) {
            case ANCHOR_BURST -> playAnchorBurst(origin);
            case SIGNAL_ARROW -> playSignalArrow(origin);
            case MANA_SPARK -> playManaSpark(origin);
        }
    }

    private void playAnchorBurst(@NotNull EffectOrigin origin) {
        Location center = origin.location().clone().add(origin.direction().clone().multiply(1.15D)).add(0.0D, -0.25D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(center, ringLocations(center, 1.05D, 18), endRodPoint("adventurer_anchor_ring", 1));
        particleDisplayService.spawnForNearbyViewers(center, Particle.CLOUD, 18, 0.35D, 0.12D, 0.35D, 0.02D);
        particleDisplayService.spawnForNearbyViewers(center.clone().add(0.0D, 0.45D, 0.0D), Particle.CRIT, 16, 0.28D, 0.2D, 0.28D, 0.15D);
        playSound(center, "entity.iron_golem.attack", 0.8F, 1.35F);
    }

    private void playSignalArrow(@NotNull EffectOrigin origin) {
        Location start = origin.location().clone().add(origin.direction().clone().multiply(0.9D));
        particleDisplayService.spawnForNearbyViewers(start, Particle.FIREWORK, 10, 0.14D, 0.14D, 0.14D, 0.03D);
        particleDisplayService.spawnForNearbyViewers(start, lineLocations(start, origin.direction(), 0.55D, 4), endRodPoint("adventurer_signal_arrow", 1));
        playSound(start, "entity.arrow.shoot", 0.75F, 1.55F);
    }

    private void playManaSpark(@NotNull EffectOrigin origin) {
        Location center = origin.location().clone().add(origin.direction().clone().multiply(0.8D));
        particleDisplayService.spawnForNearbyViewers(center, Particle.ENCHANT, 18, 0.22D, 0.22D, 0.22D, 0.08D);
        particleDisplayService.spawnForNearbyViewers(
                center,
                Particle.DUST,
                12,
                0.14D,
                0.14D,
                0.14D,
                0.0D,
                new Particle.DustOptions(Color.fromRGB(120, 210, 255), 0.9F)
        );
        particleDisplayService.spawnForNearbyViewers(center, ringLocations(center, 0.65D, 12), SharedParticleDefinitions.MAGIC_IMPACT_DUST);
        playSound(center, "block.amethyst_block.chime", 0.85F, 1.45F);
    }

    private @NotNull List<Location> ringLocations(@NotNull Location center, double radius, int points) {
        List<Location> locations = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.05D, Math.sin(angle) * radius));
        }
        return locations;
    }

    private @NotNull List<Location> lineLocations(
            @NotNull Location start,
            @NotNull Vector direction,
            double stepDistance,
            int points
    ) {
        List<Location> locations = new ArrayList<>(points);
        Vector normalized = direction.clone().normalize();
        for (int i = 1; i <= points; i++) {
            locations.add(start.clone().add(normalized.clone().multiply(stepDistance * i)));
        }
        return locations;
    }

    private @NotNull SharedParticleDefinition endRodPoint(@NotNull String id, int count) {
        return new SharedParticleDefinition(id, Particle.END_ROD, count, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    private void playSound(@NotNull Location location, @NotNull String soundKey, float volume, float pitch) {
        if (location.getWorld() == null) {
            return;
        }
        location.getWorld().playSound(location, soundKey, SoundCategory.PLAYERS, volume, pitch);
    }

    private @Nullable EffectOrigin resolveEffectOrigin(@NotNull SkillCastContext context) {
        if (context.caster() instanceof PlayerSkillCaster caster) {
            Player player = caster.player().getBukkit();
            Location location = player.getEyeLocation().clone();
            return new EffectOrigin(location, location.getDirection().normalize());
        }
        if (context.caster() instanceof MobSkillCaster) {
            Location location = context.castLocation().clone();
            Vector direction = location.getDirection();
            if (direction.lengthSquared() <= 1.0E-6D) {
                direction = resolveTargetDirection(location, context.primaryTarget());
            }
            return new EffectOrigin(location, direction.normalize());
        }
        return null;
    }

    private @NotNull Vector resolveTargetDirection(@NotNull Location origin, @Nullable LivingEntity primaryTarget) {
        if (primaryTarget != null && primaryTarget.isValid() && !primaryTarget.isDead()) {
            Vector direction = primaryTarget.getEyeLocation().toVector().subtract(origin.toVector());
            if (direction.lengthSquared() > 1.0E-6D) {
                return direction.normalize();
            }
        }
        return new Vector(0.0D, 0.0D, 1.0D);
    }

    private @NotNull StarterStyle readStyle(@NotNull SkillDefinition skill) {
        Object raw = skill.getParams().get(STYLE_PARAM);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new SkillParameterException(STYLE_PARAM, "starterStyle を指定してください");
        }
        try {
            return StarterStyle.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new SkillParameterException(STYLE_PARAM, "未対応の starterStyle です");
        }
    }

    private enum StarterStyle {
        ANCHOR_BURST,
        SIGNAL_ARROW,
        MANA_SPARK
    }

    private record EffectOrigin(@NotNull Location location, @NotNull Vector direction) {
    }
}
