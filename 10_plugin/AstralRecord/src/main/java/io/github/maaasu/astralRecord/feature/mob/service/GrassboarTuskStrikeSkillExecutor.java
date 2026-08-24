package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
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
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** グラスボア固有の突進牙攻撃を実行します。 */
public final class GrassboarTuskStrikeSkillExecutor implements SkillExecutor {
    public static final String SKILL_ID = "grassboar_tusk_strike";
    private final DamageService damageService;
    private final ParticleDisplayService particleDisplayService;

    /** ダメージと演出の依存先を指定して executor を構築します。 */
    public GrassboarTuskStrikeSkillExecutor(@NotNull DamageService damageService, @NotNull ParticleDisplayService particleDisplayService) {
        this.damageService = damageService;
        this.particleDisplayService = particleDisplayService;
    }

    /** グラスボア用の固定スキル定義を返します。 */
    public static @NotNull SkillDefinition definition() {
        return new SkillDefinition(SKILL_ID, SKILL_ID, "グラスボアの牙突き", null, null, List.of(), 30L, 0.0D, 0L, 1, null, java.util.Map.of(), List.of("builtin", "mob"), SkillKind.ACTIVE, false, SkillResourceType.ENERGY, 0.0D);
    }

    @Override public @NotNull String implementationId() { return SKILL_ID; }

    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof MobSkillCaster caster) || context.primaryTarget() == null) return SkillCastResult.succeeded();
        LivingEntity target = context.primaryTarget();
        Location impact = target.getLocation().add(0.0D, 0.6D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(impact, SharedParticleDefinitions.WEAPON_ATTACK_DEFAULT);
        AstEntity victim = damageService.resolveEntity(target);
        if (victim.isPlayer()) damageService.attack(AstEntity.mob(caster.mob()), victim, AttackType.MELEE, List.of(DamageComponent.defaultComponent()));
        return SkillCastResult.succeeded();
    }
}
