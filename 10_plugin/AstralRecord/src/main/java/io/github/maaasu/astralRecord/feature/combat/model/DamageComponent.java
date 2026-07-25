package io.github.maaasu.astralRecord.feature.combat.model;

import org.jetbrains.annotations.NotNull;

/**
 * スキルダメージを構成する属性別の攻撃倍率です。
 *
 * @param element 属性。属性指定なしは {@link DamageElement#NONE}
 * @param ratio 参照攻撃力へ掛ける倍率。0.8 は 80%
 */
public record DamageComponent(@NotNull DamageElement element, double ratio) {

    public DamageComponent {
        ratio = Math.max(0.0D, ratio);
    }

    /**
     * 後方互換用の無属性100%成分を返します。
     *
     * @return 無属性100%成分
     */
    public static @NotNull DamageComponent defaultComponent() {
        return new DamageComponent(DamageElement.NONE, 1.0D);
    }
}
