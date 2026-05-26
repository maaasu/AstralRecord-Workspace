package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * スキル実行クラスに引き渡す発動時コンテキスト。
 * <p>
 * 共通制御層（{@link io.github.maaasu.astralRecord.feature.skill.service.SkillService}）が
 * 発動条件を満たしていることを保証してから構築する。実装クラスはここから
 * {@code params} とステータスを参照して個別ロジックを処理する。
 *
 * @param skill           対象スキル定義
 * @param caster          発動主体
 * @param primaryTarget   主対象。単体対象スキルで使用。指定がなければ {@code null}
 * @param targets         範囲・複数対象の解決結果（変更不可）
 * @param castLocation    発動位置
 * @param statusSnapshot  発動者のステータススナップショット（発動時点）
 * @param trigger         発動契機
 * @param now             判定基準時刻
 */
public record SkillCastContext(
        @NotNull SkillDefinition skill,
        @NotNull SkillCaster caster,
        @Nullable LivingEntity primaryTarget,
        @NotNull List<LivingEntity> targets,
        @NotNull Location castLocation,
        @NotNull StatusSnapshot statusSnapshot,
        @NotNull SkillCastTrigger trigger,
        @NotNull Instant now
) {

    /**
     * targets を変更不可リストとして保持するため、コンパクトコンストラクタで防御的コピーを取る。
     */
    public SkillCastContext {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
