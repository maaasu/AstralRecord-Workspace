package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤー単位のスキルツリー解放状態です。
 */
public final class SkillTreePlayerState {
    private final UUID accountId;
    private final Set<String> unlockedNodeIds;

    public SkillTreePlayerState(@NotNull UUID accountId, @NotNull Set<String> unlockedNodeIds) {
        this.accountId = accountId;
        this.unlockedNodeIds = new LinkedHashSet<>(unlockedNodeIds);
    }

    @NotNull
    public UUID accountId() {
        return accountId;
    }

    public boolean isUnlocked(@NotNull String nodeId) {
        return unlockedNodeIds.contains(nodeId);
    }

    public boolean unlock(@NotNull String nodeId) {
        return unlockedNodeIds.add(nodeId);
    }

    public boolean relock(@NotNull String nodeId) {
        return unlockedNodeIds.remove(nodeId);
    }

    @NotNull
    public Set<String> unlockedNodeIds() {
        return Set.copyOf(unlockedNodeIds);
    }
}
