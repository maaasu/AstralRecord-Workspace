package io.github.maaasu.astralRecord.feature.mob.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mob 専用スキル ID と Executor の対応を保持します。 */
public final class MobSkillRegistry {

    private final Map<String, MobSkillExecutor> executors = new LinkedHashMap<>();

    /**
     * Mob 専用スキルExecutorを登録します。
     *
     * @param executor 登録するExecutor
     * @throws IllegalArgumentException IDが空または重複する場合
     */
    public void register(@NotNull MobSkillExecutor executor) {
        String id = executor.id().trim();
        if (id.isEmpty() || executors.putIfAbsent(id, executor) != null) {
            throw new IllegalArgumentException("Mob skill ID が重複または空です: " + id);
        }
    }

    /**
     * IDに対応するExecutorを返します。
     *
     * @param id Mob スキル ID
     * @return 未登録の場合は {@code null}
     */
    public @Nullable MobSkillExecutor find(@NotNull String id) {
        return executors.get(id);
    }

    /** @return 登録済みExecutorの不変一覧 */
    public @NotNull Collection<MobSkillExecutor> executors() {
        return java.util.List.copyOf(executors.values());
    }
}
