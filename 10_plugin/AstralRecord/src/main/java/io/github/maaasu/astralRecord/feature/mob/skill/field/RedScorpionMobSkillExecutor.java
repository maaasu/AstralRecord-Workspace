package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * アカサソリの毒針。24tick予告後、固定した長さ3.5m/半幅0.32mの直線へ一回刺突し、
 * 近接無属性85%と毒70tick（SUPPORT_POWERの12%/20tick）を主対象だけへ適用します。
 * 横移動と遮蔽物で回避可能。paramsなし、射程0.5〜3.5m、CD180tick以上、詠唱0のみ。
 * 中央の破棄listener/onDisableからhandleMobDestroyed/stopを呼ぶ必要があります。
 */
public final class RedScorpionMobSkillExecutor extends MesaDebuffSkillSupport {
    public static final String SKILL_ID = "mob_field_red_scorpion_tail_sting";
    private static final double LENGTH = 3.5D;
    private static final double HALF_WIDTH = 0.32D;

    /** 共通ダメージ・期限付き状態異常・有限予告の依存先を指定します。 */
    public RedScorpionMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        super(mobService, damageService, conditionService, particles, 24, 0.85D, ConditionType.POISON, 70L,
                SharedParticleDefinitions.MOB_ERIVA_THORNFANG_TRAIL, SharedParticleDefinitions.CONDITION_POISON_DUST,
                Sound.ENTITY_SPIDER_AMBIENT, Sound.ENTITY_SPIDER_HURT);
    }

    /** @return Mobマスター専用の毒針ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 頭上表示する技名 */
    @Override public @NotNull String displayName() { return "毒針"; }

    /** @return 3.5m、再使用9秒、Executor内予告のため詠唱0 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(LENGTH, 180L, 0L); }

    /** 二本の外枠と中央筋、終端の横棒で細い刺突領域を27点以内に示します。 */
    @Override protected @NotNull List<Location> warningPoints(Location origin, Vector direction) {
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
        List<Location> points = new ArrayList<>(27);
        for (int i = 0; i <= 7; i++) {
            for (double width : new double[]{-HALF_WIDTH, 0.0D, HALF_WIDTH}) {
                points.add(origin.clone().add(direction.clone().multiply(i * 0.5D))
                        .add(side.clone().multiply(width)).add(0.0D, 0.08D, 0.0D));
            }
        }
        for (double width : new double[]{-0.16D, 0.0D, 0.16D}) points.add(origin.clone()
                .add(direction.clone().multiply(LENGTH)).add(side.clone().multiply(width)).add(0.0D, 0.08D, 0.0D));
        return points;
    }

    /** 主対象足元の前後・横距離を固定直線に投影し、背後や横回避を除外します。 */
    @Override protected boolean contains(Location origin, Vector direction, Location target) {
        double dx = target.getX() - origin.getX(), dz = target.getZ() - origin.getZ();
        double forward = dx * direction.getX() + dz * direction.getZ();
        double sideways = -dx * direction.getZ() + dz * direction.getX();
        return forward >= 0.0D && forward <= LENGTH && Math.abs(sideways) <= HALF_WIDTH;
    }
}
