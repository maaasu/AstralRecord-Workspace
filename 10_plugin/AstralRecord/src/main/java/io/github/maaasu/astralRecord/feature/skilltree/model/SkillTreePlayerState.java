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
    private int skillPoints;
    private final Set<String> unlockedNodeIds;

    public SkillTreePlayerState(@NotNull UUID accountId, int skillPoints, @NotNull Set<String> unlockedNodeIds) {
        this.accountId = accountId;
        this.skillPoints = Math.max(0, skillPoints);
        this.unlockedNodeIds = new LinkedHashSet<>(unlockedNodeIds);
    }

    @NotNull
    public UUID accountId() {
        return accountId;
    }

    public int skillPoints() {
        return skillPoints;
    }

    public void setSkillPoints(int skillPoints) {
        this.skillPoints = Math.max(0, skillPoints);
    }

    public void addSkillPoints(int delta) {
        setSkillPoints(skillPoints + delta);
    }

    public boolean isUnlocked(@NotNull String nodeId) {
        return unlockedNodeIds.contains(nodeId);
    }

    public boolean unlock(@NotNull String nodeId) {
        if (skillPoints <= 0 || !unlockedNodeIds.add(nodeId)) {
            return false;
        }
        skillPoints--;
        return true;
    }

    public boolean relock(@NotNull String nodeId) {
        if (!unlockedNodeIds.remove(nodeId)) {
            return false;
        }
        skillPoints++;
        return true;
    }

    @NotNull
    public Set<String> unlockedNodeIds() {
        return Set.copyOf(unlockedNodeIds);
    }
}
