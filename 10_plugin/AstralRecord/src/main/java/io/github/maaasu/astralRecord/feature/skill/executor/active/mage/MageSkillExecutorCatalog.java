package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** メイジ用の発動スキル executor を列挙します。 */
public final class MageSkillExecutorCatalog {

    private MageSkillExecutorCatalog() {
    }

    /**
     * メイジ用 executor をスキル表示順で生成します。
     *
     * @param services 共有発動スキルサービス
     * @return 2個の executor
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of(
            new MageFireballExecutor(services),
            new MageHealAuraExecutor(services)
        );
    }
}
