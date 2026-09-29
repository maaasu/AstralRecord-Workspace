package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiConsumer;

/** 個別射撃スキルが共有する命中処理と継承バフ判定です。 */
public abstract class SharpshooterBuildSupport extends PlayerActiveSkillExecutor {
    private InheritanceBuffService inheritanceBuffService;

    /**
     * 対応する実装IDと共通サービスで構築します。
     * @param id 実装ID
     * @param services 対象・戦闘・演出サービス
     */
    protected SharpshooterBuildSupport(@NotNull String id, @NotNull ActiveSkillServices services) {
        super(id, services);
    }

    /**
     * 既存継承バフの所持判定を注入します。
     * @param service 継承バフサービス
     */
    public final void setInheritanceBuffService(@NotNull InheritanceBuffService service) {
        inheritanceBuffService = service;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : new String[]{"range", "damageRatio", "projectileSpeed", "projectileHitRadius"}) {
            positive(p, key);
        }
    }

    /**
     * 既存の遮蔽・Mob対象規則を通る有限飛翔体を発射します。
     * @param c 発動者と共有サービス
     * @param origin 発射位置
     * @param direction 発射方向
     * @param maxHits 貫通を含む最大命中数
     * @param trail 軌跡の共有粒子定義
     * @param onHit Mob命中時に実行する処理
     */
    protected final void projectile(@NotNull PlayerActiveSkillContext c, @NotNull Location origin,
                                    @NotNull Vector direction, int maxHits,
                                    @NotNull SharedParticleDefinition trail,
                                    @NotNull BiConsumer<AstEntity, Location> onHit) {
        SkillParamReader p = c.params();
        c.services().projectiles().launch(c.player(), origin, direction,
                new SkillProjectileSpec(p.getDouble("range", 18.0D), p.getDouble("projectileSpeed", 1.6D),
                        p.getDouble("projectileHitRadius", 0.6D), maxHits > 1, maxHits, trail,
                        SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_IMPACT),
                onHit, ignored -> { });
    }

    /**
     * 継承の心得が有効で継承バフを一つ以上保有しているかを返します。
     * @param c 発動者
     * @return 有効な継承バフがある場合はtrue
     */
    protected final boolean inherited(@NotNull PlayerActiveSkillContext c) {
        return inheritanceBuffService != null
                && inheritanceBuffService.canUseInheritanceBonus(c.caster().player());
    }

    /**
     * 元スキルを記録する既存の間接スキル攻撃を行います。
     * @param c 発動元
     * @param target Mob対象
     * @param element ダメージ属性
     * @param ratio 間接攻撃倍率
     * @param conditions 有効命中時に判定する状態異常
     * @return 回避、HP、シールドを含む結果
     */
    protected final @NotNull DamageResult hit(@NotNull PlayerActiveSkillContext c,
                                              @NotNull AstEntity target, @NotNull DamageElement element,
                                              double ratio, @NotNull ActiveSkillCondition... conditions) {
        return c.services().combat().hit(c.source().skill(), c.attacker(), target,
                AttackType.RANGED, element, ratio, conditions);
    }

    /**
     * 既存の状態異常耐性規則に渡す付与条件を作ります。
     * @param p 発動時に解決した数値
     * @param type 状態異常種別
     * @return 付与率と持続を含む条件
     */
    protected final @NotNull ActiveSkillCondition condition(@NotNull SkillParamReader p,
                                                             @NotNull ConditionType type) {
        return new ActiveSkillCondition(type, p.getDouble("conditionChance", 35.0D),
                p.getInt("conditionTicks", 100), 1.0D);
    }

    /**
     * 正の有限値を検証します。
     * @param p 検証対象
     * @param key 数値キー
     * @throws SkillParameterException 非正数または非有限値の場合
     */
    protected final void positive(@NotNull SkillParamReader p, @NotNull String key) {
        double value = p.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) throw new SkillParameterException(key, "正数が必要です");
    }

    /**
     * 0超1以下の有限割合を検証します。
     * @param p 検証対象
     * @param key 割合キー
     * @throws SkillParameterException 範囲外または非有限値の場合
     */
    protected final void fraction(@NotNull SkillParamReader p, @NotNull String key) {
        double value = p.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D || value > 1.0D) {
            throw new SkillParameterException(key, "0より大きく1以下が必要です");
        }
    }

    /**
     * 0以上100以下の付与率を検証します。
     * @param p 検証対象
     * @param key 付与率キー
     * @throws SkillParameterException 範囲外または非有限値の場合
     */
    protected final void percentage(@NotNull SkillParamReader p, @NotNull String key) {
        double value = p.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value < 0.0D || value > 100.0D) {
            throw new SkillParameterException(key, "0以上100以下が必要です");
        }
    }

    /**
     * 上下限付き整数を検証します。
     * @param p 検証対象
     * @param key 整数キー
     * @param min 最小値
     * @param max 最大値
     * @throws SkillParameterException 範囲外または非整数の場合
     */
    protected final void count(@NotNull SkillParamReader p, @NotNull String key, int min, int max) {
        int value = p.getInt(key, -1);
        if (value < min || value > max) throw new SkillParameterException(key, min + "以上" + max + "以下が必要です");
    }
}
