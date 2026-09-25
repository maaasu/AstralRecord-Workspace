package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** 発動者ごとに現存するプレイヤースキルの魔法陣を記録します。 */
public final class SkillMagicCircleRegistry {

    private final Map<UUID, UUID> ownersByCircle = new HashMap<>();
    private final Map<UUID, Set<UUID>> circlesByCaster = new HashMap<>();
    private final Map<UUID, Predicate<Player>> influenceByCircle = new HashMap<>();

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
     * プレイヤーへの作用条件を持つ魔法陣を登録します。条件は問い合わせ時に評価します。
     *
     * @param casterId 発動者UUID
     * @param influencesPlayer 現在そのプレイヤーが魔法陣の作用対象かを判定する条件
     * @return 解除時に使う一意の魔法陣UUID
     */
    public @NotNull UUID register(@NotNull UUID casterId, @NotNull Predicate<Player> influencesPlayer) {
        UUID circleId = register(casterId);
        influenceByCircle.put(circleId, influencesPlayer);
        return circleId;
    }

    /**
     * 発動者の現存する魔法陣が、攻撃可能なプレイヤーへ作用しているかを返します。
     *
     * @param casterId 魔法陣の発動者UUID
     * @param playerId 判定対象のプレイヤーUUID
     * @return 対象プレイヤーに作用する魔法陣が一つ以上あればtrue
     */
    public boolean isPlayerAffectedByCasterCircle(@NotNull UUID casterId, @NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead()) {
            return false;
        }
        Set<UUID> circles = circlesByCaster.get(casterId);
        if (circles == null) {
            return false;
        }
        return circles.stream().anyMatch(circleId -> {
            Predicate<Player> influence = influenceByCircle.get(circleId);
            return influence != null && influence.test(player);
        });
    }

    /**
     * 終了した魔法陣を解除します。重複解除は無視します。
     *
     * @param circleId 登録時に返された魔法陣UUID
     */
    public void remove(@NotNull UUID circleId) {
        influenceByCircle.remove(circleId);
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
        influenceByCircle.clear();
        ownersByCircle.clear();
        circlesByCaster.clear();
    }
}
