package io.github.maaasu.astralRecord.feature.skill.active.model;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/**
 * 一斉射撃へ追加する重力付き仮想飛翔体の始点と仕様です。
 *
 * @param origin 発射位置
 * @param spec 飛翔体仕様
 */
public record SkillBallisticProjectileLaunch(
        @NotNull Location origin,
        @NotNull SkillBallisticProjectileSpec spec
) {

    /** 可変なLocationを防御的に複製します。 */
    public SkillBallisticProjectileLaunch {
        origin = origin.clone();
    }

    /**
     * 外部変更の影響を受けない発射位置の複製を返します。
     *
     * @return 発射位置の複製
     */
    @Override
    public @NotNull Location origin() {
        return origin.clone();
    }
}
