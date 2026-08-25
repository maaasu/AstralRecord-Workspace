package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 黄昏の巨像のルーン光弾を実行します。 */
public final class TwilightColossusRuneBoltSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_twilight_colossus_rune_bolt";
    private final DamageService damageService; private final ParticleDisplayService particleDisplayService;
    /** ダメージと着弾演出の依存先を指定して executor を構築します。 */
    public TwilightColossusRuneBoltSkillExecutor(@NotNull DamageService damageService, @NotNull ParticleDisplayService particleDisplayService) { this.damageService = damageService; this.particleDisplayService = particleDisplayService; }
    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "黄昏門の光弾"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(16.0D, 48L, 20L); }
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Location impact = context.target().getLocation().add(0.0D, 0.8D, 0.0D);
        particleDisplayService.spawnForNearbyViewers(impact, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
        AstEntity victim = damageService.resolveEntity(context.target());
        if (victim.isPlayer()) damageService.attack(AstEntity.mob(context.mob()), victim, AttackType.MAGIC, List.of(new DamageComponent(DamageElement.LIGHTNING, 0.55D)));
        return true;
    }
}
