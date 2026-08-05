package io.github.maaasu.astralRecord.feature.skill.executor.active.support;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー専用発動スキルが利用する型付き実行コンテキストです。
 *
 * @param source 共通スキル実行コンテキスト
 * @param caster プレイヤー発動者
 * @param services 発動スキル共有サービス
 */
public record PlayerActiveSkillContext(
        @NotNull SkillCastContext source,
        @NotNull PlayerSkillCaster caster,
        @NotNull ActiveSkillServices services
) {

    /**
     * Bukkit プレイヤーを返します。
     *
     * @return 発動プレイヤー
     */
    public @NotNull Player player() {
        return caster.player().getBukkit();
    }

    /**
     * custom combat 用の発動者を返します。
     *
     * @return 発動者エンティティ
     */
    public @NotNull AstEntity attacker() {
        return AstEntity.player(caster.player(), source.statusSnapshot());
    }

    /**
     * 発動者の目線位置を複製して返します。
     *
     * @return 目線位置
     */
    public @NotNull Location eyeLocation() {
        return player().getEyeLocation().clone();
    }

    /**
     * 発動時の視線方向を単位ベクトルで返します。
     *
     * @return 視線方向
     */
    public @NotNull Vector direction() {
        Vector direction = eyeLocation().getDirection();
        return direction.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : direction.normalize();
    }

    /**
     * 発動時に解決済みとなった params の型付きReaderを返します。
     *
     * @return 実行値のReader
     */
    public @NotNull SkillParamReader params() {
        return new SkillParamReader(source.skill().getId(), source.skill().getParams());
    }

    /**
     * 発動成功結果を返します。消費とクールダウンは共通サービスが適用します。
     *
     * @return 成功結果
     */
    public @NotNull SkillCastResult success() {
        return SkillCastResult.succeeded();
    }
}
