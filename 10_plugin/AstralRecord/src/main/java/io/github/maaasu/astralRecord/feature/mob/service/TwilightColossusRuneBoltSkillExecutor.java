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
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 黄昏の巨像のルーン光弾を実行します。 */
public final class TwilightColossusRuneBoltSkillExecutor implements SkillExecutor {
    public static final String SKILL_ID = "twilight_colossus_rune_bolt";
    private final DamageService damageService; private final ParticleDisplayService particleDisplayService;
    /** ダメージと着弾演出の依存先を指定して executor を構築します。 */
    public TwilightColossusRuneBoltSkillExecutor(@NotNull DamageService damageService, @NotNull ParticleDisplayService particleDisplayService) { this.damageService = damageService; this.particleDisplayService = particleDisplayService; }
    /** 黄昏の巨像用の固定スキル定義を返します。 */
    public static @NotNull SkillDefinition definition() { return new SkillDefinition(SKILL_ID, SKILL_ID, "黄昏門の光弾", null, null, List.of(), 48L, 0.0D, 20L, 1, null, java.util.Map.of(), List.of("builtin", "boss"), SkillKind.ACTIVE, false, SkillResourceType.MANA, 0.0D); }
    @Override public @NotNull String implementationId() { return SKILL_ID; }
    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof MobSkillCaster caster) || context.primaryTarget() == null) return SkillCastResult.succeeded();
        Location impact = context.primaryTarget().getLocation().add(0.0D, 0.8D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(impact, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
        AstEntity victim = damageService.resolveEntity(context.primaryTarget());
        if (victim.isPlayer()) damageService.attack(AstEntity.mob(caster.mob()), victim, AttackType.MAGIC, List.of(new DamageComponent(DamageElement.LIGHTNING, 0.55D)));
        return SkillCastResult.succeeded();
    }
}
