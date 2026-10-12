package io.github.maaasu.astralRecord.feature.mob.skill.witchforest;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/**
 * {@code mob_witch_forest_hex_bolt}: 詠唱開始方向へ黄緑の呪灯弾を放ち、魔法・無属性100%を与えます。
 * 弾速0.65m/tick、射程16m、命中半径0.2m。追尾・状態異常はなく、壁で消滅します。
 * paramsは受け付けません。発動距離14m、48tick再使用、20tick詠唱が既定です。
 */
public final class WitchForestHexBoltMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_witch_forest_hex_bolt";
    private final WitchForestSkillSupport support;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobProjectileService projectiles;

    /**
     * @param support 発動者と主対象の継続確認先
     * @param damageService 命中判定とダメージ適用先
     * @param conditionService 行動不能の判定先
     * @param projectiles 見える呪灯弾の実行・回収先
     */
    public WitchForestHexBoltMobSkillExecutor(@NotNull WitchForestSkillSupport support,
            @NotNull DamageService damageService, @NotNull ConditionService conditionService,
            @NotNull MobProjectileService projectiles) {
        this.support = support;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.projectiles = projectiles;
    }

    /** @return Mobマスターから参照する専用技ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱バーの技名 */
    @Override public @NotNull String displayName() { return "呪灯弾"; }
    /** @return 発動距離14m・48tick再使用・20tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(14.0D, 48L, 20L); }
    /** @return 上下へ照準できるためtrue */
    @Override public boolean allowsVerticalTargeting() { return true; }

    /** @param context 詠唱開始時の方向と主対象 @return 呪灯弾を発射できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        if (support.activeCaster(context) == null || context.direction().lengthSquared() < 0.01D) return false;
        projectiles.launchWitchForestHexBolt(context, damageService, conditionService);
        context.origin().getWorld().playSound(context.origin(), Sound.ENTITY_WITCH_THROW, 0.8F, 0.7F);
        return true;
    }
}
