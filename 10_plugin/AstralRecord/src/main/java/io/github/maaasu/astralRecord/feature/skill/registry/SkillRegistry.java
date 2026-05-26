package io.github.maaasu.astralRecord.feature.skill.registry;

import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code implementationId} ⇔ {@link SkillExecutor} と {@code skillId} ⇔ {@link SkillDefinition}
 * の対応を一括管理するレジストリ。
 * <p>
 * 実行クラスは Plugin 起動時に登録される。スキル定義は
 * {@link io.github.maaasu.astralRecord.feature.skill.service.SkillService#reloadDefinitions()}
 * で生成された新マップを一括スワップする運用とする。
 */
public final class SkillRegistry {

    private final Map<String, SkillExecutor> executors = new LinkedHashMap<>();
    private Map<String, SkillDefinition> definitionsById = new LinkedHashMap<>();

    /**
     * 実行クラスを登録します。
     * 同一 {@code implementationId} の executor が既に登録済みの場合は
     * Warning ログを出して新規登録を拒否する（後勝ち禁止）。
     *
     * @param executor 登録対象
     * @return 登録できたら {@code true}
     */
    public boolean registerExecutor(@NotNull SkillExecutor executor) {
        String id = executor.implementationId();
        if (executors.containsKey(id)) {
            Logger.log(LogId.W_5802, id);
            return false;
        }
        executors.put(id, executor);
        return true;
    }

    /**
     * {@code implementationId} に対応する実行クラスを返します。
     *
     * @param implementationId 実装解決キー
     * @return 実行クラス。未登録なら {@code null}
     */
    @Nullable
    public SkillExecutor getExecutor(@NotNull String implementationId) {
        return executors.get(implementationId);
    }

    /**
     * 現在の executors を変更不可コレクションで返します。
     *
     * @return executors
     */
    @NotNull
    public Collection<SkillExecutor> executors() {
        return Collections.unmodifiableCollection(executors.values());
    }

    /**
     * 新しい定義マップで既存マップを一括置換します。
     * 反映後の件数を {@link LogId#D_5801} で出力します。
     *
     * @param newDefinitions 検証済みの新しい定義一覧（{@code skillId} → 定義）
     */
    public void replaceDefinitions(@NotNull Map<String, SkillDefinition> newDefinitions) {
        this.definitionsById = new LinkedHashMap<>(newDefinitions);
        Logger.log(LogId.D_5801, definitionsById.size());
    }

    /**
     * {@code skillId} に対応する定義を返します。
     *
     * @param skillId スキル ID
     * @return 定義。未登録なら {@code null}
     */
    @Nullable
    public SkillDefinition getDefinition(@NotNull String skillId) {
        return definitionsById.get(skillId);
    }

    /**
     * 現在ロード済みの定義件数を返します。
     *
     * @return 件数
     */
    public int definitionCount() {
        return definitionsById.size();
    }

    /**
     * 現在ロード済みの全定義を返します。
     *
     * @return 定義リスト（変更不可）
     */
    @NotNull
    public Collection<SkillDefinition> definitions() {
        return Collections.unmodifiableCollection(definitionsById.values());
    }
}
