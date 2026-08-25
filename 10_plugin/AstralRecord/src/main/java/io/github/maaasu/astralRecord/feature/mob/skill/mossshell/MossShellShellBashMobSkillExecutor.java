package io.github.maaasu.astralRecord.feature.mob.skill.mossshell;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code mob_moss_shell_shell_bash}: ミッドガルズ・モスシェルが近接で行う甲羅打ちです。
 *
 * <p>個別パラメーターは受け付けません。通常攻撃と同じ反撃型近接 AI の中で低頻度に使い、
 * 攻撃力の60%を近接スキルとして与えます。</p>
 */
public final class MossShellShellBashMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_moss_shell_shell_bash";
    private static final double RANGE = 2.0D;
    private static final double DAMAGE_RATIO = 0.60D;

    private final DamageService damageService;

    /**
     * ダメージ適用先を指定して executor を構築します。
     *
     * @param damageService 管理対象への近接ダメージ適用先
     */
    public MossShellShellBashMobSkillExecutor(@NotNull DamageService damageService) {
        this.damageService = damageService;
    }

    @Override
    public @NotNull String id() {
        return SKILL_ID;
    }

    @Override
    public @NotNull String displayName() {
        return "甲羅打ち";
    }

    @Override
    public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(RANGE, 100L, 8L);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        if (context.origin().getWorld() != context.target().getWorld()
                || !isWithinHorizontalRange(context.origin(), context.target().getLocation())) {
            return false;
        }
        damageService.attack(
                AstEntity.mob(context.mob()),
                damageService.resolveEntity(context.target()),
                AttackType.MELEE,
                List.of(new DamageComponent(DamageElement.NONE, DAMAGE_RATIO)),
                DamageSource.SKILL
        );
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_TURTLE_HURT, 0.7F, 0.8F);
        return true;
    }

    private boolean isWithinHorizontalRange(@NotNull org.bukkit.Location origin, @NotNull org.bukkit.Location target) {
        double x = origin.getX() - target.getX();
        double z = origin.getZ() - target.getZ();
        return x * x + z * z <= RANGE * RANGE;
    }
}
