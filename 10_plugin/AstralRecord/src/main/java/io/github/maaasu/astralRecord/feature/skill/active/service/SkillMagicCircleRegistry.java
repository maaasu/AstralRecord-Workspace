package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 発動者ごとに現存するプレイヤースキルの魔法陣を記録します。 */
public final class SkillMagicCircleRegistry {

    private final Map<UUID, UUID> ownersByCircle = new HashMap<>();
    private final Map<UUID, Set<UUID>> circlesByCaster = new HashMap<>();

    /**
     * 新しい魔法陣を発動者へ登録します。
     *
     * @param casterId 発動者UUID
     * @return 解除時に使う一意の魔法陣UUID
     */
    public @NotNull UUID register(@NotNull UUID casterId) {
        UUID circleId;
        do {
            circleId = UUID.randomUUID();
        } while (ownersByCircle.containsKey(circleId));
        ownersByCircle.put(circleId, casterId);
        circlesByCaster.computeIfAbsent(casterId, ignored -> new HashSet<>()).add(circleId);
        return circleId;
    }

    /**
     * 終了した魔法陣を解除します。重複解除は無視します。
     *
     * @param circleId 登録時に返された魔法陣UUID
     */
    public void remove(@NotNull UUID circleId) {
        UUID casterId = ownersByCircle.remove(circleId);
        if (casterId == null) {
            return;
        }
        Set<UUID> circles = circlesByCaster.get(casterId);
        if (circles != null) {
            circles.remove(circleId);
            if (circles.isEmpty()) {
                circlesByCaster.remove(casterId);
            }
        }
    }

    /**
     * 発動者が現在維持している魔法陣の数を返します。
     *
     * @param casterId 発動者UUID
     * @return 現存する魔法陣の数
     */
    public int count(@NotNull UUID casterId) {
        Set<UUID> circles = circlesByCaster.get(casterId);
        return circles == null ? 0 : circles.size();
    }

    /** 全発動者の登録をプラグイン終了時に消去します。 */
    public void clear() {
        ownersByCircle.clear();
        circlesByCaster.clear();
    }
}
