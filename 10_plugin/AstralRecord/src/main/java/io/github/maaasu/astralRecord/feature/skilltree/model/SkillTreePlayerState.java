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
    private final int persistedVersion;
    /** 状態を最後に確定したスキルツリー定義世代。旧データは null のまま保留します。 */
    private final @Nullable String definitionGenerationId;

    public SkillTreePlayerState(@NotNull UUID accountId, @NotNull Set<String> unlockedNodeIds) {
        this(accountId, unlockedNodeIds.stream()
                .map(nodeId -> new SkillTreeUnlockedNode(nodeId, null))
                .toList(), 0);
    }

    public SkillTreePlayerState(@NotNull UUID accountId, @NotNull List<SkillTreeUnlockedNode> unlockedNodes) {
        this(accountId, unlockedNodes, 0);
    }

    public SkillTreePlayerState(
            @NotNull UUID accountId,
            @NotNull List<SkillTreeUnlockedNode> unlockedNodes,
            int persistedVersion
    ) {
        this(accountId, unlockedNodes, persistedVersion, null);
    }

    /**
     * APIから取得したスキルツリー状態を生成します。
     *
     * @param accountId 所有アカウント
     * @param unlockedNodes 解放済みノードとCP消費元
     * @param persistedVersion APIの楽観ロック版数
     * @param definitionGenerationId 確定時の実ロード世代。旧データでは null
     */
    public SkillTreePlayerState(
            @NotNull UUID accountId,
            @NotNull List<SkillTreeUnlockedNode> unlockedNodes,
            int persistedVersion,
            @Nullable String definitionGenerationId
    ) {
        this.accountId = accountId;
        this.unlockedNodes = new LinkedHashMap<>();
        this.persistedVersion = Math.max(0, persistedVersion);
        this.definitionGenerationId = definitionGenerationId == null || definitionGenerationId.isBlank()
                ? null
                : definitionGenerationId.trim().toLowerCase(java.util.Locale.ROOT);
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

    public int persistedVersion() {
        return persistedVersion;
    }

    /** @return 状態を確定した実ロード世代。旧データなら null */
    public @Nullable String definitionGenerationId() {
        return definitionGenerationId;
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
