package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.pond.PondSurface;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 水鏡の守り主はプレイヤーの移動を記録し、その軌跡を水上の鏡片が再演します。
 * HP70%以下では20tick遅れた池中心の点対称を追加し、35%以下では鏡軌跡を逆再生します。
 * 通常AIは記録から反撃猶予まで停止し、同じ再演全体で各プレイヤーへの命中は一度だけです。
 * 同期5tick時計を使い、最大6人・13点/人・12鏡片・444予告点へ制限します。
 */
final class PondEncounterController {
    static final String BOSS_ID = "eriva_pond_mirror_keeper";
    private static final String EXPOSURE_ID = "eriva-pond-stillwater-exposure";
    private static final double ARENA_RADIUS = 18.0D;
    private static final double TRACE_RADIUS = 0.85D;
    private static final long RECORD_TICKS = 60L, WARNING_TICKS = 40L, REPLAY_TICKS = 60L, REST_TICKS = 60L;
    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hit;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private @Nullable TemporarySkillEffectService effects;

    /** @param mobs Mob存続・経路停止先 @param particles 共有描画先 @param participants 確定参加者解決先 @param hit 魔法・氷属性ダメージ適用先 */
    PondEncounterController(@NotNull MobService mobs, @NotNull ParticleDisplayService particles,
                            @NotNull Function<MobInstance, List<Player>> participants, @NotNull HitHandler hit) {
        this.mobs = mobs; this.particles = particles; this.participants = participants; this.hit = hit;
    }

    /** @param effects 反撃猶予の被ダメージ20%増加を所有する一時効果サービス */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService effects) { this.effects = effects; }

    /** @param boss 同期走査中のボス @param entity 有効な実体 @param tick 単調な5tick時計 */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        try {
            List<Player> players = active(boss);
            if (players.isEmpty() || !inArena(entity.getLocation(), state.center)) { cancelCast(boss.instanceId()); return; }
            int observed = boss.currentHealth() <= boss.maxHealth() * 0.35D ? 3
                : boss.currentHealth() <= boss.maxHealth() * 0.70D ? 2 : 1;
            state.phase = Math.max(state.phase, observed);
            if (state.stage == null) {
                if (tick < state.nextAt || boss.scriptedAction()) return;
                state.stage = state.introduced ? Stage.RECORD : Stage.INTRO;
                state.introduced = true;
                state.castPhase = state.phase;
                state.started = tick;
                state.ends = tick + (state.stage == Stage.INTRO ? 60L : RECORD_TICKS);
                state.bar = Bukkit.createBossBar(title(state), BarColor.BLUE, BarStyle.SOLID);
                state.wasGlowing = entity.isGlowing();
                boss.scriptedAction(true);
                mobs.stopPathfinding(boss);
                sound(state.center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F);
            }
            boss.scriptedAction(true);
            mobs.stopPathfinding(boss);
            if (state.stage == Stage.RECORD) record(state, players);
            if (state.stage == Stage.REPLAY) replay(state, players, tick);
            if (state.stage == null || mobs.getInstance(boss.instanceId()) != boss || boss.currentHealth() <= 0.0D) return;
            if (tick % 10L == 0L) draw(state, tick);
            updateBar(state, players, tick);
            if (tick < state.ends) return;
            switch (state.stage) {
                case INTRO -> beginRecord(state, tick);
                case RECORD -> beginWarning(state, players, tick);
                case WARNING -> {
                    state.stage = Stage.REPLAY; state.started = tick;
                    state.ends = tick + REPLAY_TICKS + (state.castPhase >= 2 ? 20L : 0L);
                    sound(state.center, Sound.BLOCK_GLASS_BREAK, 0.75F);
                }
                case REPLAY -> beginRest(state, entity, tick);
                case REST -> {
                    removeCast(state);
                    state.nextAt = tick + 40L;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6510, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 40L;
        }
    }

    /** 記録は全員同じ時間で始め、途中参加の軌跡は遡って作りません。 */
    private void beginRecord(Encounter state, long tick) {
        state.stage = Stage.RECORD; state.started = tick; state.ends = tick + RECORD_TICKS;
        state.recorded.clear();
        sound(state.center, Sound.BLOCK_CONDUIT_ACTIVATE, 1.15F);
    }

    /** 5tick刻みで水面へ投影します。転送・3m超の移動は線で結ばず新しい記録へ分けます。 */
    private void record(Encounter state, List<Player> players) {
        Set<UUID> live = new HashSet<>();
        for (Player player : players) live.add(player.getUniqueId());
        state.recorded.keySet().retainAll(live);
        for (Player player : players.stream().sorted(java.util.Comparator.comparing(Player::getUniqueId)).limit(6).toList()) {
            Location point = PondSurface.at(player.getLocation());
            if (point == null || !inArena(point, state.center)) continue;
            List<Location> points = state.recorded.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
            if (!points.isEmpty() && (PondSurface.squared(points.getLast(), point) > 9.0D
                || !PondSurface.walkable(points.getLast(), point))) points.clear();
            if (points.size() < 13) points.add(point);
        }
    }

    /**
     * 移動履歴を固定し、全参加者が3.4m以内の退避点へ歩いて移動可能な場合だけ再演します。
     * 不適切な鏡面や安全域のない混雑では末尾の軌跡を外し、最後まで確保できなければ反撃猶予へ移ります。
     */
    private void beginWarning(Encounter state, List<Player> players, long tick) {
        state.trails.clear();
        for (List<Location> points : state.recorded.values()) {
            if (points.isEmpty()) continue;
            state.trails.add(new Trail(List.copyOf(points), 0L));
            if (state.castPhase >= 2) {
                List<Location> reflected = new ArrayList<>();
                for (int i = 0; i < points.size(); i++) {
                    Location source = points.get(state.castPhase == 3 ? points.size() - 1 - i : i);
                    Location mirror = PondSurface.at(new Location(state.center.getWorld(),
                        2 * state.center.getX() - source.getX(), state.center.getY(),
                        2 * state.center.getZ() - source.getZ()));
                    if (mirror == null || !inArena(mirror, state.center)
                        || !reflected.isEmpty() && !PondSurface.walkable(reflected.getLast(), mirror)) {
                        reflected.clear(); break;
                    }
                    reflected.add(mirror);
                }
                if (!reflected.isEmpty()) state.trails.add(new Trail(List.copyOf(reflected), 20L));
            }
        }
        state.recorded.clear();
        while (!state.trails.isEmpty() && !resolveShelters(state, players)) state.trails.removeLast();
        if (state.trails.isEmpty()) {
            Entity entity = mobs.entityController().getEntity(state.boss);
            if (entity != null) beginRest(state, entity, tick); else cancelCast(state.boss.instanceId());
            return;
        }
        state.stage = Stage.WARNING; state.started = tick; state.ends = tick + WARNING_TICKS;
        for (Trail trail : state.trails) {
            BlockDisplay display = state.center.getWorld().spawn(trail.points.getFirst(), BlockDisplay.class);
            state.displays.add(display.getUniqueId());
            display.setPersistent(false);
            display.setTeleportDuration(5);
            display.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            display.setTransformation(new Transformation(new Vector3f(-0.2F, 0.12F, -0.2F),
                new Quaternionf(), new Vector3f(0.4F, 0.4F, 0.4F), new Quaternionf()));
        }
        sound(state.center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F);
    }

    /** 参加者ごとに予告全軌跡から離れ、通路で到達できる退避位置を1つ求めます。 */
    private boolean resolveShelters(Encounter state, List<Player> players) {
        state.shelters.clear();
        for (Player player : players) {
            Location from = PondSurface.at(player.getLocation());
            if (from == null) return false;
            Location safe = safeAt(state, from) ? from : null;
            for (double radius : new double[]{1.8D, 2.6D, 3.4D}) {
                for (int i = 0; i < 24 && safe == null; i++) {
                    double angle = Math.PI * 2 * i / 24;
                    Location candidate = PondSurface.at(from.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                    if (candidate != null && safeAt(state, candidate) && PondSurface.walkable(from, candidate)) safe = candidate;
                }
                if (safe != null) break;
            }
            if (safe == null) return false;
            state.shelters.add(safe);
        }
        return true;
    }

    /** 退避小円まで危険軌跡が侵入しない、同階の歩行可能な位置かを判定します。 */
    private boolean safeAt(Encounter state, Location point) {
        if (!inArena(point, state.center)) return false;
        for (Trail trail : state.trails) for (int i = 0; i < trail.points.size(); i++) {
            Location from = trail.points.get(Math.max(0, i - 1)), to = trail.points.get(i);
            if (PondSurface.segmentSquared(point, from, to) < 2.89D) return false;
        }
        return true;
    }

    /** 水鏡の頭を記録順に進め、5tickの移動区間を掃引してすり抜けと重複命中を防ぎます。 */
    private void replay(Encounter state, List<Player> players, long tick) {
        double ratio = state.castPhase == 1 ? 0.75D : state.castPhase == 2 ? 0.85D : 0.95D;
        List<Location> heads = new ArrayList<>();
        for (int t = 0; t < state.trails.size(); t++) {
            Trail trail = state.trails.get(t);
            long elapsed = tick - state.started - trail.delay;
            if (elapsed < 0L || elapsed > REPLAY_TICKS) continue;
            Location from = interpolated(trail.points, Math.max(0L, elapsed - 5L)), to = interpolated(trail.points, elapsed);
            if (!PondSurface.clear(from, to)) continue;
            heads.add(to);
            Entity display = Bukkit.getEntity(state.displays.get(t));
            if (display != null) display.teleport(to);
            for (Player player : players) {
                Location feet = player.getLocation();
                if (state.hitPlayers.contains(player.getUniqueId()) || Math.abs(feet.getY() - to.getY()) > 2.0D
                    || PondSurface.segmentSquared(feet, from, to) > TRACE_RADIUS * TRACE_RADIUS
                    || !PondSurface.clear(to, feet)) continue;
                state.hitPlayers.add(player.getUniqueId());
                hit.apply(state.boss, player, ratio);
                if (mobs.getInstance(state.boss.instanceId()) != state.boss || state.boss.currentHealth() <= 0) return;
            }
        }
        if (tick % 10L == 0L && !heads.isEmpty()) particles.spawnForNearbyViewers(state.center, heads, SharedParticleDefinitions.POND_IMPACT);
    }

    /** 同じ60tickを全軌跡で使い、停止して記録点が少ない場合も再演速度を増加させません。 */
    private static Location interpolated(List<Location> points, long elapsed) {
        double position = Math.clamp((double) elapsed / REPLAY_TICKS, 0, 1) * (points.size() - 1);
        int index = (int) Math.floor(position), next = Math.min(points.size() - 1, index + 1);
        Location a = points.get(index), b = points.get(next);
        return a.clone().add(b.toVector().subtract(a.toVector()).multiply(position - index));
    }

    /** 3秒の静水では通常攻撃せず、甲羅が光り被ダメージが20%増加します。 */
    private void beginRest(Encounter state, Entity entity, long tick) {
        removeDisplays(state);
        state.trails.clear(); state.shelters.clear(); state.recorded.clear();
        state.stage = Stage.REST; state.started = tick; state.ends = tick + REST_TICKS;
        entity.setGlowing(true);
        if (effects != null) effects.apply(state.boss.instanceId(), EXPOSURE_ID, REST_TICKS, 1.20D, 1.0D, 1.0D);
        sound(state.center, Sound.BLOCK_CONDUIT_DEACTIVATE, 1.25F);
    }

    /** 軌跡は点列、再演中は前進する鏡片、安全域は緑の小円で表し、色だけに依存しません。 */
    private void draw(Encounter state, long tick) {
        List<Location> trace = new ArrayList<>();
        if (state.stage == Stage.RECORD) for (List<Location> points : state.recorded.values()) trace.addAll(points);
        else if (state.stage == Stage.WARNING || state.stage == Stage.REPLAY) for (Trail trail : state.trails) {
            for (int i = 0; i < trail.points.size(); i++) {
                Location point = trail.points.get(i);
                if (i > 0) {
                    Location previous = trail.points.get(i - 1);
                    var delta = point.toVector().subtract(previous.toVector());
                    trace.add(previous.clone().add(delta.clone().multiply(1.0D / 3.0D)));
                    trace.add(previous.clone().add(delta.clone().multiply(2.0D / 3.0D)));
                }
                trace.add(point);
            }
        }
        if (!trace.isEmpty()) particles.spawnForNearbyViewers(state.center, trace, state.stage == Stage.RECORD
            ? SharedParticleDefinitions.POND_RECORD : SharedParticleDefinitions.POND_WARNING);
        if (state.stage == Stage.WARNING || state.stage == Stage.REPLAY) {
            List<Location> safe = new ArrayList<>();
            for (Location center : state.shelters) for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12;
                safe.add(center.clone().add(Math.cos(a) * 0.7D, 0, Math.sin(a) * 0.7D));
            }
            if (!safe.isEmpty()) particles.spawnForNearbyViewers(state.center, safe, SharedParticleDefinitions.POND_SAFE);
        }
        if (state.stage == Stage.INTRO || state.stage == Stage.REST) {
            List<Location> halo = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                double a = Math.PI * 2 * i / 20 + tick * 0.03D;
                halo.add(state.center.clone().add(Math.cos(a) * 2.8D, 0.4D, Math.sin(a) * 2.8D));
            }
            particles.spawnForNearbyViewers(state.center, halo, SharedParticleDefinitions.POND_SAFE);
        }
    }

    /** 進行バーの対象を現在の参加者へ同期し、退出したプレイヤーを直ちに外します。 */
    private void updateBar(Encounter state, List<Player> players, long tick) {
        if (state.bar == null) return;
        for (Player old : List.copyOf(state.bar.getPlayers())) if (!players.contains(old)) state.bar.removePlayer(old);
        for (Player player : players) state.bar.addPlayer(player);
        state.bar.setTitle(title(state));
        state.bar.setColor(state.stage == Stage.REST ? BarColor.GREEN : state.stage == Stage.WARNING ? BarColor.PURPLE : BarColor.BLUE);
        state.bar.setProgress(Math.clamp((double) (state.ends - tick) / Math.max(1L, state.ends - state.started), 0, 1));
    }

    /** @return 対象ボスの確定名簿のうち同階・生存・通常ゲームモードの参加者 */
    private List<Player> active(MobInstance boss) {
        return participants.apply(boss).stream().filter(Player::isOnline).filter(p -> !p.isDead())
            .filter(p -> p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR)
            .filter(p -> inArena(p.getLocation(), boss.spawnLocation())).toList();
    }

    /** 同じworldの水面階±2m・半径18mに限ります。 */
    static boolean inArena(Location point, Location center) {
        return point.getWorld() == center.getWorld() && Math.abs(point.getY() - center.getY()) <= 2.0D
            && PondSurface.squared(point, center) <= ARENA_RADIUS * ARENA_RADIUS;
    }

    /** 内部状態ではなく、各段階の対処をバーへ表示します。 */
    private static String title(Encounter state) {
        PlayerMsgId id = switch (state.stage) {
            case INTRO -> PlayerMsgId.P_7740;
            case RECORD -> PlayerMsgId.P_7741;
            case WARNING -> state.castPhase == 1 ? PlayerMsgId.P_7742 : state.castPhase == 2 ? PlayerMsgId.P_7743 : PlayerMsgId.P_7744;
            case REPLAY -> PlayerMsgId.P_7745;
            case REST -> PlayerMsgId.P_7746;
        };
        return PlayerMsgResource.getMessage(id.name());
    }

    /** @param playerId 転送した参加者。記録中の旧軌跡だけを捨て、予告済み攻撃は変えません */
    void forgetParticipantPosition(@NotNull UUID playerId) {
        for (Encounter state : encounters.values()) state.recorded.remove(playerId);
    }

    /** @param bossId CC・拘束・参加者喪失で停止するボス。表示・一時倍率・通常AIを復元します */
    void cancelCast(@NotNull UUID bossId) {
        Encounter state = encounters.get(bossId);
        if (state != null) removeCast(state);
    }

    /** @param bossId 死亡・離脱・待機・帰還したボス個体 */
    void clear(@NotNull UUID bossId) { cancelCast(bossId); encounters.remove(bossId); }
    /** Plugin停止時に所有するすべての個体状態を回収します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }
    /** @param liveIds 今回の走査で生存している専用ボス */
    void retain(@NotNull Set<UUID> liveIds) { for (UUID id : List.copyOf(encounters.keySet())) if (!liveIds.contains(id)) clear(id); }

    /** 終了・中断で同じ経路を使い、既存の発光状態を保存して戻します。 */
    private void removeCast(Encounter state) {
        boolean owned = state.stage != null;
        if (state.bar != null) { state.bar.removeAll(); state.bar.setVisible(false); state.bar = null; }
        removeDisplays(state);
        if (effects != null) effects.clear(state.boss.instanceId(), EXPOSURE_ID);
        if (owned) {
            Entity entity = mobs.entityController().getEntity(state.boss);
            if (entity != null) entity.setGlowing(state.wasGlowing);
            state.boss.scriptedAction(false);
        }
        state.stage = null; state.recorded.clear(); state.trails.clear(); state.shelters.clear(); state.hitPlayers.clear();
    }

    /** UUIDで所有する鏡片だけを回収し、繰り返し呼び出しても安全に終了します。 */
    private static void removeDisplays(Encounter state) {
        for (UUID id : state.displays) { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
        state.displays.clear();
    }

    /** 水鏡の区切り音を同じ会場で再生します。 */
    private static void sound(Location center, Sound sound, float pitch) { center.getWorld().playSound(center, sound, 0.9F, pitch); }

    @FunctionalInterface
    interface HitHandler {
        /** @param boss 発生元 @param player 生存参加者 @param ratio 魔法・氷属性の攻撃倍率 */
        void apply(MobInstance boss, Player player, double ratio);
    }
    private enum Stage { INTRO, RECORD, WARNING, REPLAY, REST }
    private record Trail(List<Location> points, long delay) { }
    private static final class Encounter {
        private final MobInstance boss;
        private final Location center;
        private final Map<UUID, List<Location>> recorded = new LinkedHashMap<>();
        private final List<Trail> trails = new ArrayList<>();
        private final List<Location> shelters = new ArrayList<>();
        private final List<UUID> displays = new ArrayList<>();
        private final Set<UUID> hitPlayers = new HashSet<>();
        private int phase = 1, castPhase = 1;
        private long started, ends, nextAt;
        private boolean introduced, wasGlowing;
        private @Nullable Stage stage;
        private @Nullable BossBar bar;
        private Encounter(MobInstance boss) { this.boss = boss; this.center = boss.spawnLocation().clone(); }
    }
}
