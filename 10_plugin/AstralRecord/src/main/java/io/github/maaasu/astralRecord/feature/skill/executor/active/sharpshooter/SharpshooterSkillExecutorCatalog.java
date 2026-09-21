package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** シャープシューター用の発動スキル executor を列挙します。 */
public final class SharpshooterSkillExecutorCatalog {
    private SharpshooterSkillExecutorCatalog() {
    }

    /**
     * 実装済みのシャープシューター用 executor を生成します。
     *
     * @param services 共通発動サービス
     * @return シャープシューター用 executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
                new SharpshooterFireArrowExecutor(services),
                new SharpshooterIceArrowExecutor(services)
        );
    }
}
