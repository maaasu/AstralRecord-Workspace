package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤー単位のスキルツリー解放状態です。
 */
public final class SkillTreePlayerState {
    private final UUID accountId;
    private final Map<String, SkillTreeUnlockedNode> unlockedNodes;

    public SkillTreePlayerState(@NotNull UUID accountId, @NotNull Set<String> unlockedNodeIds) {
        this(accountId, unlockedNodeIds.stream()
                .map(nodeId -> new SkillTreeUnlockedNode(nodeId, null))
                .toList());
    }

    public SkillTreePlayerState(@NotNull UUID accountId, @NotNull List<SkillTreeUnlockedNode> unlockedNodes) {
        this.accountId = accountId;
        this.unlockedNodes = new LinkedHashMap<>();
        for (SkillTreeUnlockedNode unlockedNode : unlockedNodes) {
            if (!unlockedNode.nodeId().isBlank()) {
                this.unlockedNodes.putIfAbsent(unlockedNode.nodeId(), unlockedNode);
            }
        }
    }

    @NotNull
    public UUID accountId() {
        return accountId;
    }

    public boolean isUnlocked(@NotNull String nodeId) {
        return unlockedNodes.containsKey(nodeId);
    }

    public boolean unlock(@NotNull String nodeId) {
        return unlock(nodeId, null);
    }

    public boolean unlock(@NotNull String nodeId, @Nullable String consumedClassId) {
        return unlockedNodes.putIfAbsent(nodeId, new SkillTreeUnlockedNode(nodeId, consumedClassId)) == null;
    }

    public boolean relock(@NotNull String nodeId) {
        return unlockedNodes.remove(nodeId) != null;
    }

    public @Nullable SkillTreeUnlockedNode unlockedNode(@NotNull String nodeId) {
        return unlockedNodes.get(nodeId);
    }

    @NotNull
    public Set<String> unlockedNodeIds() {
        return Set.copyOf(unlockedNodes.keySet());
    }

    @NotNull
    public List<SkillTreeUnlockedNode> unlockedNodes() {
        return List.copyOf(unlockedNodes.values());
    }
}
