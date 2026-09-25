package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 現存する自身の魔法陣を力に変え、上空の傾いた魔法陣から無属性の光線を放ちます。 */
public final class ArchmageAstralRayExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_astral_ray";
    private static final int FRAME_TICKS = 2;
    private static final int FIRE_TICKS = 10;
    private static final int LIFETIME_TICKS = 20;

    /**
     * 共有サービスで発動処理を構成します。
     * @param services 魔法陣集計、対象判定、戦闘、演出とタスクのサービス
     */
    public ArchmageAstralRayExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : List.of("range", "damageRatio")) {
            double value = params.getDouble(key, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0D) {
                throw new SkillParameterException(key, "アストラルレイの params[" + key + "] は正数が必要です");
            }
        }
        for (String key : List.of("circleCount", "circleIntervalTicks")) {
            if (params.getInt(key, 0) < 1) {
                throw new SkillParameterException(key, "アストラルレイの params[" + key + "] は1以上が必要です");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        UUID casterId = context.player().getUniqueId();
        SkillParamReader params = context.params();
        // この発動が作る円を登録する前に一度だけ数え、生成途中の増減を総数へ反映しません。
        int count = Math.addExact(params.getInt("circleCount", 1),
                Math.multiplyExact(context.services().circles().count(casterId), 2));
        double range = params.getDouble("range", 18.0D);
        double ratio = params.getDouble("damageRatio", 1.2D);
        context.services().tasks().repeat(casterId, ID + ":volley:" + UUID.randomUUID(),
                0L, params.getInt("circleIntervalTicks", 5), count,
                index -> summon(context, range, ratio));
        return context.success();
    }

    /**
     * 上空に一つの円を生成し、構築・発射・消滅の有限アニメーションを開始します。
     * @param context 発動時のステータスと現在のプレイヤー
     * @param range 発射時点のプレイヤーを中心とする敵の探索半径
     * @param ratio 一発の無属性魔法攻撃倍率
     */
    private static void summon(@NotNull PlayerActiveSkillContext context, double range, double ratio) {
        UUID casterId = context.player().getUniqueId();
        AstralRayParticleVisual visual = new AstralRayParticleVisual(context.player().getLocation());
        UUID circleId = context.services().circles().register(casterId);
        Location[] beamEnd = {null};
        try {
            context.services().tasks().repeat(casterId, ID + ":circle:" + circleId,
                    0L, FRAME_TICKS, LIFETIME_TICKS / FRAME_TICKS + 1,
                    frame -> {
                        int age = frame * FRAME_TICKS;
                        visual.drawCircle(context.services(), age, FIRE_TICKS, LIFETIME_TICKS);
                        if (age == FIRE_TICKS) {
                            beamEnd[0] = fire(context, visual, range, ratio);
                        }
                        if (beamEnd[0] != null && age >= FIRE_TICKS && age < LIFETIME_TICKS) {
                            visual.drawBeam(context.services(), beamEnd[0],
                                    (double) (age - FIRE_TICKS) / (LIFETIME_TICKS - FIRE_TICKS));
                        }
                    },
                    () -> context.services().circles().remove(circleId));
        } catch (RuntimeException exception) {
            context.services().circles().remove(circleId);
            throw exception;
        }
    }

    /**
     * 発射時に有効な敵を等確率で選び、一発だけダメージを与えます。過去の命中対象も再選択できます。
     * @param context 戦闘と対象判定に必要な発動コンテキスト
     * @param visual 光線の始点を持つ魔法陣
     * @param range 現在位置からの探索半径
     * @param ratio 魔法攻撃倍率
     * @return 光線の着弾地点。見通せる敵がいなければnull
     */
    private static @Nullable Location fire(@NotNull PlayerActiveSkillContext context,
                                 @NotNull AstralRayParticleVisual visual, double range, double ratio) {
        List<AstEntity> targets = context.services().targeting()
                .inSphere(context.player(), context.player().getLocation(), range, Integer.MAX_VALUE, false)
                .stream()
                .filter(target -> context.services().targeting().hasLineOfSight(
                        visual.center(), context.services().targeting().center(target)))
                .toList();
        if (targets.isEmpty()) {
            return null;
        }
        AstEntity target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
        Location impact = context.services().targeting().center(target);
        context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.MAGIC, DamageElement.NONE, ratio);
        context.services().effects().point(impact, SharedParticleDefinitions.SKILL_ASTRAL_RAY_IMPACT);
        context.services().effects().sound(visual.center(), Sound.BLOCK_BEACON_ACTIVATE, 0.65F, 1.8F);
        return impact;
    }
}
