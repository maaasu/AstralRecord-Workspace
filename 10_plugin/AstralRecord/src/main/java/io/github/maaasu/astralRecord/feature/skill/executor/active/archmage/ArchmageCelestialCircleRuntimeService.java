package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMagicCircleRegistry;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** セレスティアルサークルの描画段階と、全発動者間で重複しない範囲バフを管理します。 */
public final class ArchmageCelestialCircleRuntimeService {

    private static final int ANIMATION_TICKS = 40;
    private static final String BUFF_ID = "archmage_celestial_circle";
    private final StatusService statusService;
    private final SkillMagicCircleRegistry circleRegistry;
    private final Map<UUID, Circle> circles = new HashMap<>();
    private final Map<UUID, Applied> applied = new HashMap<>();
    private long lastSynchronizeTick = -1L;
    private long lastFeedbackTick = -1L;

    /**
     * ステータス更新サービスを受け取ります。
     *
     * @param statusService バフの付与と解除に使うサービス
     * @param circleRegistry 発動者ごとの現存魔法陣
     */
    public ArchmageCelestialCircleRuntimeService(@NotNull StatusService statusService,
                                                 @NotNull SkillMagicCircleRegistry circleRegistry) {
        this.statusService = statusService;
        this.circleRegistry = circleRegistry;
    }

    /**
     * 発動者の旧魔法陣を即時削除し、新しい水平魔法陣の構築を開始します。
     *
     * @param casterId 発動者UUID
     * @param center 地面上の中心
     * @param level スキルレベル
     * @param durationTicks 完成後の持続tick
     * @param effects 対象への効果成立音と周期表示
     */
    public void start(@NotNull UUID casterId, @NotNull Location center, int level, int durationTicks,
                      @NotNull SkillEffectService effects) {
        Circle previous = circles.remove(casterId);
        if (previous != null) {
            circleRegistry.remove(previous.circleId);
        }
        synchronize(effects);
        circles.put(casterId, new Circle(center.clone(), level, durationTicks,
                circleRegistry.register(casterId, player -> {
                    Circle circle = circles.get(casterId);
                    return circle != null && circle.age >= ANIMATION_TICKS
                            && AstPlayerCache.get(player) != null && contains(circle, player);
                })));
    }

    /**
     * 発動者の魔法陣を1tick進め、完成後だけ範囲バフを同期します。
     *
     * @param casterId 発動者UUID
     * @param effects 実際にバフを得た対象の演出
     * @return 魔法陣が継続する場合はtrue
     */
    public boolean advance(@NotNull UUID casterId, @NotNull SkillEffectService effects) {
        Circle circle = circles.get(casterId);
        if (circle == null) {
            return false;
        }
        boolean completedNow = circle.age == ANIMATION_TICKS - 1;
        if (circle.age < ANIMATION_TICKS) {
            circle.age++;
        } else if (--circle.remainingTicks <= 0) {
            end(casterId, effects);
            return false;
        }
        long currentTick = Bukkit.getCurrentTick();
        if (completedNow || lastSynchronizeTick < 0L
                || currentTick - lastSynchronizeTick >= 4L) {
            synchronize(effects);
        }
        return true;
    }

    /**
     * 描画用の水平魔法陣の情報を返します。
     *
     * @param casterId 発動者UUID
     * @return 有効な魔法陣。存在しなければnull
     */
    public @Nullable Snapshot snapshot(@NotNull UUID casterId) {
        Circle circle = circles.get(casterId);
        return circle == null ? null : new Snapshot(circle.center.clone(), circle.level,
                Math.min(1.0D, (double) circle.age / ANIMATION_TICKS), circle.age >= ANIMATION_TICKS);
    }

    /**
     * 発動者の魔法陣を終了し、この魔法陣だけが支えていたバフを解除します。
     *
     * @param casterId 発動者UUID
     * @param effects 範囲内の対象へ移るバフの効果音
     */
    public void end(@NotNull UUID casterId, @NotNull SkillEffectService effects) {
        Circle circle = circles.remove(casterId);
        if (circle != null) {
            circleRegistry.remove(circle.circleId);
            synchronize(effects);
        }
    }

    /**
     * プラグイン停止処理で発動者の魔法陣を終了します。
     *
     * @param casterId 発動者UUID
     */
    public void end(@NotNull UUID casterId) {
        Circle circle = circles.remove(casterId);
        if (circle != null) {
            circleRegistry.remove(circle.circleId);
            synchronize(null);
        }
    }

    /** すべての魔法陣と付与済みバフを解除します。 */
    public void clearAll() {
        circles.values().forEach(circle -> circleRegistry.remove(circle.circleId));
        circles.clear();
        synchronize(null);
    }

    /**
     * 全円の最大レベルを選び、有効バフを持つ対象だけに演出します。
     *
     * @param effects 対象への表示と効果音。停止中の同期ではnull
     */
    private void synchronize(@Nullable SkillEffectService effects) {
        lastSynchronizeTick = Bukkit.getCurrentTick();
        LocalDateTime now = LocalDateTime.now();
        Map<UUID, Target> desired = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) {
                continue;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                continue;
            }
            int bestLevel = 0;
            for (Circle circle : circles.values()) {
                if (circle.age >= ANIMATION_TICKS && circle.level > bestLevel && contains(circle, player)) {
                    bestLevel = circle.level;
                }
            }
            if (bestLevel > 0) {
                desired.put(player.getUniqueId(), new Target(astPlayer, bestLevel));
            }
        }
        for (UUID playerId : Set.copyOf(applied.keySet())) {
            Applied previous = applied.get(playerId);
            Target target = desired.get(playerId);
            boolean present = previous.player.getActiveBuffs().stream().anyMatch(buff ->
                    buffId(previous.level).equals(buff.getType().getId())
                            && buff.getExpiresAt().isAfter(now));
            if (target == null || previous.player != target.player || previous.level != target.level || !present) {
                if (present) {
                    statusService.removeBuff(previous.player, buffId(previous.level));
                }
                applied.remove(playerId);
            }
        }
        Map<World, List<Location>> entrySounds = new HashMap<>();
        for (Map.Entry<UUID, Target> entry : desired.entrySet()) {
            if (applied.containsKey(entry.getKey())) {
                continue;
            }
            Target target = entry.getValue();
            statusService.applyBuff(target.player, buffId(target.level));
            boolean present = target.player.getActiveBuffs().stream().anyMatch(buff ->
                    buffId(target.level).equals(buff.getType().getId())
                            && buff.getExpiresAt().isAfter(now));
            if (present) {
                applied.put(entry.getKey(), new Applied(target.player, target.level));
                if (effects != null) {
                    Location position = target.player.getBukkit().getLocation();
                    List<Location> nearbySounds = entrySounds.computeIfAbsent(
                            position.getWorld(), ignored -> new ArrayList<>());
                    if (nearbySounds.stream().noneMatch(played -> played.distanceSquared(position) < 144.0D)) {
                        nearbySounds.add(position);
                    }
                }
            }
        }
        if (effects != null) {
            entrySounds.values().forEach(positions -> positions.forEach(position ->
                    effects.sound(position, Sound.BLOCK_BEACON_ACTIVATE, 0.55F, 1.6F)));
        }
        if (effects != null && !applied.isEmpty()
                && (lastFeedbackTick < 0L || lastSynchronizeTick - lastFeedbackTick >= 20L)) {
            lastFeedbackTick = lastSynchronizeTick;
            Map<World, List<Location>> positions = new HashMap<>();
            for (Map.Entry<UUID, Applied> entry : applied.entrySet()) {
                Applied active = entry.getValue();
                if (desired.containsKey(entry.getKey()) && active.player.getBukkit().isOnline()) {
                    Location position = active.player.getBukkit().getLocation();
                    positions.computeIfAbsent(position.getWorld(), ignored -> new ArrayList<>()).add(position);
                }
            }
            positions.values().forEach(worldPositions -> ArchmageCircleFeedback.celestial(effects, worldPositions));
        }
    }

    private static boolean contains(@NotNull Circle circle, @NotNull Player player) {
        Location position = player.getLocation();
        if (position.getWorld() == null || !position.getWorld().equals(circle.center.getWorld())) {
            return false;
        }
        double dx = position.getX() - circle.center.getX();
        double dy = position.getY() - circle.center.getY();
        double dz = position.getZ() - circle.center.getZ();
        return Math.abs(dy) <= 2.5D && dx * dx + dz * dz <= circle.level * circle.level;
    }

    private static @NotNull String buffId(int level) {
        return level == 1 ? BUFF_ID : BUFF_ID + "_lv" + level;
    }

    /** 描画対象の円の不変情報です。 */
    public record Snapshot(@NotNull Location center, int level, double progress, boolean complete) {
    }

    private static final class Circle {
        private final UUID circleId;
        private final Location center;
        private final int level;
        private int age;
        private int remainingTicks;

        private Circle(Location center, int level, int durationTicks, UUID circleId) {
            this.circleId = circleId;
            this.center = center;
            this.level = level;
            this.remainingTicks = durationTicks;
        }
    }

    private record Target(AstPlayer player, int level) {
    }

    private record Applied(AstPlayer player, int level) {
    }
}
