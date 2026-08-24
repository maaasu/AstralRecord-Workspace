package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.MobSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 黄昏の巨像の門衛大槌を実行します。 */
public final class TwilightColossusGateSlamSkillExecutor implements SkillExecutor {
    public static final String SKILL_ID = "twilight_colossus_gate_slam";
    private static final double RANGE = 4.0D;
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

    /** ダメージと範囲演出の依存先を指定して executor を構築します。 */
    public TwilightColossusGateSlamSkillExecutor(@NotNull DamageService damageService, @NotNull ParticleDisplayService particleDisplayService) { this.damageService = damageService; this.particleDisplayService = particleDisplayService; }
    /** 黄昏の巨像用の固定スキル定義を返します。 */
    public static @NotNull SkillDefinition definition() { return new SkillDefinition(SKILL_ID, SKILL_ID, "門衛の大槌", null, null, List.of(), 52L, 0.0D, 20L, 1, null, java.util.Map.of(), List.of("builtin", "boss"), SkillKind.ACTIVE, false, SkillResourceType.ENERGY, 0.0D); }
    @Override public @NotNull String implementationId() { return SKILL_ID; }
    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof MobSkillCaster caster)) return SkillCastResult.succeeded();
        Location center = context.castLocation();
        renderImpact(center);
        for (Entity entity : center.getWorld().getNearbyEntities(center, RANGE, RANGE, RANGE)) {
            AstEntity victim = damageService.resolveEntity(entity);
            if (victim.isPlayer() && victim.location().distanceSquared(center) <= RANGE * RANGE) damageService.attack(AstEntity.mob(caster.mob()), victim, AttackType.MELEE, List.of(new DamageComponent(DamageElement.NONE, 0.65D)));
        }
        return SkillCastResult.succeeded();
    }
    private void renderImpact(@NotNull Location center) {
        List<Location> points = new ArrayList<>();
        for (int index = 0; index < 28; index++) { double angle = Math.PI * 2.0D * index / 28; points.add(center.clone().add(Math.cos(angle) * RANGE, 0.15D, Math.sin(angle) * RANGE)); }
        particleDisplayService.spawnForNearbyViewers(center, points, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
        particleDisplayService.spawnForNearbyViewers(center, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
    }
}
