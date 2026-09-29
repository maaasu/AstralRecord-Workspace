package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ファントムアーチャー専用の発動スキルを列挙します。 */
public final class PhantomArcherSkillExecutorCatalog {
    private PhantomArcherSkillExecutorCatalog() { }

    /**
     * 登録済みの専用スキル実装を生成します。
     * @param services 発動スキルの共有サービス
     * @return 六つの専用発動スキル
     */
    public static @NotNull List<SkillExecutor> create(@NotNull ActiveSkillServices services) {
        return List.of("shadow_stitch", "soul_pierce", "dread_bloom", "spectral_sentry",
                        "echo_volley", "mist_retreat").stream()
                .map(action -> (SkillExecutor) new PhantomArcherSkillExecutor(action, services))
                .toList();
    }
}
