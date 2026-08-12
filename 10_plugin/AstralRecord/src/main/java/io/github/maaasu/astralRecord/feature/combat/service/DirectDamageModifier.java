package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 管理戦闘の直接攻撃へ、被ダメージ倍率と反映完了後の処理を追加する拡張点です。 */
@FunctionalInterface
public interface DirectDamageModifier {

    /**
     * 直接攻撃へ適用する倍率と、元攻撃の反映完了後に実行する処理を返します。
     *
     * @param attacker 攻撃者。環境由来なら {@code null}
     * @param victim 被弾者
     * @param attackType 攻撃種別
     * @param source 発生元
     * @param calculated 防御・命中・一時補正を解決済みの結果
     * @return 倍率と反映完了後処理
     */
    @NotNull DirectDamageModification modify(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull DamageSource source,
            @NotNull DamageResult calculated
    );
}
