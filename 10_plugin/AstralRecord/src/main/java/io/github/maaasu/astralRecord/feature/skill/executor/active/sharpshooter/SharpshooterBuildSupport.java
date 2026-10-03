package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService.InheritanceEffect;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService.InheritanceImpact;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/** 個別射撃スキルが共有する命中処理です。 */
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
     * 通常攻撃へ固有効果を引き継ぐサービスを注入します。
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
     * 元スキルの成功時に、継承の心得が有効なら通常攻撃へ効果を引き継ぎます。
     * @param context 成功した元スキルの実行値
     * @param condition 通常攻撃の最初の着弾で満たすべき条件
     * @param effect 継承倍率を受けて実行する元スキル効果
     */
    protected final void grantInheritance(@NotNull PlayerActiveSkillContext context,
                                          @NotNull Predicate<InheritanceImpact> condition,
                                          @NotNull InheritanceEffect effect) {
        if (inheritanceBuffService != null) {
            inheritanceBuffService.grant(context, condition, effect);
        }
    }

    /**
     * 通常攻撃がMobに有効命中したか判定します。致死打も起点に含めます。
     * @param impact 最初の着弾
     * @return 回避されずHPかシールドへダメージを与えたならtrue
     */
    protected final boolean hasEffectiveMobImpact(@NotNull InheritanceImpact impact) {
        return impact.target() != null && impact.target().isMob()
                && impact.damageResult() != null && !impact.damageResult().evaded()
                && (impact.damageResult().finalDamage() > 0.0D
                || impact.damageResult().shieldDamage() > 0.0D);
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
