package io.github.maaasu.astralRecord.feature.mob.skill.twilightcolossus;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 黄昏の巨像の門衛大槌を実行します。 */
public final class TwilightColossusGateSlamSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_twilight_colossus_gate_slam";
    private static final double RANGE = 4.0D;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

    /** ダメージと範囲演出の依存先を指定して executor を構築します。 */
    public TwilightColossusGateSlamSkillExecutor(@NotNull DamageService damageService, @NotNull ParticleDisplayService particleDisplayService) { this.damageService = damageService; this.particleDisplayService = particleDisplayService; }
    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "門衛の大槌"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(4.0D, 52L, 20L); }
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Location center = context.origin();
        renderImpact(center);
        for (Entity entity : center.getWorld().getNearbyEntities(center, RANGE, RANGE, RANGE)) {
            AstEntity victim = damageService.resolveEntity(entity);
            if (victim.isPlayer() && victim.location().distanceSquared(center) <= RANGE * RANGE) damageService.attack(AstEntity.mob(context.mob()), victim, AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 0.65D)));
        }
        return true;
    }
    private void renderImpact(@NotNull Location center) {
        List<Location> points = new ArrayList<>();
        for (int index = 0; index < 28; index++) { double angle = Math.PI * 2.0D * index / 28; points.add(center.clone().add(Math.cos(angle) * RANGE, 0.15D, Math.sin(angle) * RANGE)); }
        particleDisplayService.spawnForNearbyViewers(center, points, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
        particleDisplayService.spawnForNearbyViewers(center, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
    }
}
