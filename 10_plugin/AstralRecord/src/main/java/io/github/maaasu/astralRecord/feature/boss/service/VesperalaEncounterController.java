package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 幽翠の館主の固定予兆、順番を保持する境界儀式、一時表示の同期進行を担当します。 */
final class VesperalaEncounterController {
    static final String BOSS_ID = "vesperala_manor_keeper";
    private static final double ARENA_RADIUS = 16.0D;
    private static final long DRAW_PERIOD = 10L;
    private static final String EXPOSURE_EFFECT = "vesperala_shield_exposure";
    private static final Stage[] NORMAL = {Stage.FAN, Stage.THORNS, Stage.LINE};
    private static final Stage[] FIRST_RITE = {Stage.OUTER, Stage.INNER, Stage.CROSS};
    private static final Stage[] SECOND_RITE = {Stage.CORRIDOR, Stage.CROSS, Stage.THORNS};

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private @Nullable TemporarySkillEffectService effects;

    /**
     * 同期進行で利用する既存サービスを受け取ります。
     * @param mobs 実体・無敵状態の同期先
     * @param particles 閲覧者の表示設定を適用する共通サービス
     * @param participants 生存する確定参加者を解決する関数
     * @param hitHandler 共通魔法ダメージへの委譲先
     */
    VesperalaEncounterController(@NotNull MobService mobs, @NotNull ParticleDisplayService particles,
        @NotNull Function<MobInstance, List<Player>> participants, @NotNull HitHandler hitHandler) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hitHandler = hitHandler;
    }

    /** @param service 盾破壊後の短い被ダメージ倍率を管理する既存サービス */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) { effects = service; }

    /**
     * 生存個体を5 tick周期で進めます。大技を一波ずつ確定し、HP境界の追い越しを禁止します。
     * @param boss 行動可能な個体
     * @param entity 同一worldの有効な実体
     * @param tick BossMechanicServiceの同期時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        try {
            if (tick % 20L == 0L) drawBoundary(state.center);
            List<Player> players = active(boss);
            if (players.isEmpty()) {
                cancelCast(boss.instanceId());
                return;
            }
            if (!inArena(entity.getLocation(), state.center)) {
                cancelCast(boss.instanceId());
                returnToArena(state, entity);
                return;
            }
            int observed = boss.maxHealth() <= 0.0D ? 1
                : boss.currentHealth() <= boss.maxHealth() * 0.35D ? 3
                : boss.currentHealth() <= boss.maxHealth() * 0.70D ? 2 : 1;
            state.requestedPhase = Math.max(state.requestedPhase, observed);
            boolean shieldBroken = state.lastShield > 0.0D && boss.currentShield() <= 0.0D;
            state.lastShield = boss.currentShield();
            if (shieldBroken) state.exposurePending = true;
            if (state.pose != null && tick >= state.poseEnds) restorePose(state);
            if (state.cast != null) {
                advance(state, entity, players, tick);
                return;
            }
            if (tick < state.nextAt) {
                boss.scriptedAction(true);
                return;
            }
            clearExposure(state);
            if (state.exposurePending) {
                if (begin(state, entity, players.getFirst(), Stage.EXPOSURE, tick, false)) {
                    state.exposurePending = false;
                    if (effects != null) effects.apply(boss.instanceId(), EXPOSURE_EFFECT, 60L, 1.35D, 1.0D, 1.0D);
                }
                return;
            }
            Player target = players.stream().min((a, b) -> Double.compare(
                horizontalSquared(a.getLocation(), state.center), horizontalSquared(b.getLocation(), state.center)))
                .orElseThrow();
            if (!state.introduced) {
                begin(state, entity, target, Stage.INTRO, tick, false);
            } else if (state.completedPhase < state.requestedPhase) {
                Stage[] rite = state.completedPhase == 1 ? FIRST_RITE : SECOND_RITE;
                begin(state, entity, target, rite[state.ritualWave], tick, true);
            } else {
                if (begin(state, entity, target, NORMAL[state.actionIndex % NORMAL.length], tick, false)) {
                    state.actionIndex++;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6508, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 20L;
        }
    }

    /**
     * CC・拘束・参加者喪失時の予兆を回収します。発動済みの儀式波は再実行しません。
     * @param bossId 中断する個体
     */
    void cancelCast(@NotNull UUID bossId) {
        Encounter state = encounters.get(bossId);
        if (state == null) return;
        boolean casting = state.cast != null;
        removeCast(state);
        restorePose(state);
        clearExposure(state);
        state.boss.scriptedAction(false);
        if (casting) state.nextAt = 0L;
    }

    /** @param bossId 死亡・待機・帰還した個体の全状態を回収します */
    void clear(@NotNull UUID bossId) {
        cancelCast(bossId);
        encounters.remove(bossId);
    }

    /** Plugin停止時にすべての表示と行動・無敵状態を復元します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }

    /** @param liveIds 今回の同期走査で生存している専用ボス個体 */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!liveIds.contains(id)) clear(id);
    }

    /** 対象足元と向きを固定し、到達可能な安全地点がある技だけを予約します。 */
    private boolean begin(Encounter state, Entity entity, Player target, Stage stage, long tick, boolean ritual) {
        Location origin = state.center.clone();
        Location mark = ground(target.getLocation());
        if (mark == null || !inArena(mark, origin)) { state.nextAt = tick + 20L; return false; }
        Vector direction = mark.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) direction = new Vector(1.0D, 0.0D, 0.0D);
        direction.normalize();
        if (ritual && state.completedPhase == 2 && state.ritualWave == 1) {
            direction = state.ritualDirection.clone().rotateAroundY(Math.PI / 4.0D);
        }
        Cast cast = new Cast(stage, origin, mark, direction, tick,
            tick + (ritual && state.ritualWave == 0 ? 70L : stage.warning), ritual);
        cast.safeSpot = stage.harmful ? reachableSafe(cast, mark) : null;
        if (stage.harmful && cast.safeSpot == null) { state.nextAt = tick + 20L; return false; }
        state.cast = cast;
        if (ritual && state.ritualWave == 0) state.ritualDirection = direction.clone();
        cast.bar = Bukkit.createBossBar(title(state, cast), ritual ? BarColor.PURPLE : BarColor.GREEN,
            BarStyle.SEGMENTED_10);
        state.boss.scriptedAction(true);
        if (stage == Stage.INTRO || (ritual && state.ritualWave == 0)) leap(state, entity, tick);
        spawnDisplays(cast);
        updateBar(state, cast, active(state.boss), tick);
        draw(cast);
        entity.getWorld().playSound(entity.getLocation(), ritual ? Sound.ENTITY_EVOKER_PREPARE_SUMMON
            : Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.15F, ritual ? 0.65F : 0.85F);
        return true;
    }

    /** 予兆の完了前後で同一の固定形状を使い、一波ごとのcheckpointを命中処理より先に進めます。 */
    private void advance(Encounter state, Entity entity, List<Player> players, long tick) {
        Cast cast = state.cast;
        if (cast == null) return;
        if (!loaded(cast.center) || !loaded(cast.mark)) { cancelCast(state.boss.instanceId()); return; }
        updateBar(state, cast, players, tick);
        if ((tick - cast.started) % DRAW_PERIOD == 0L) draw(cast);
        if (tick < cast.ends) return;
        if (cast.ritual) {
            state.ritualWave++;
            if (state.ritualWave == 3) {
                state.completedPhase++;
                state.ritualWave = 0;
                state.actionIndex = 0;
            }
        }
        if (cast.stage.harmful) {
            List<Location> points = BossDamageAreaEffect.sampleArea(cast.center, ARENA_RADIUS, 1.0D,
                point -> contains(cast, point), point -> clearSight(cast, point));
            particles.spawnForNearbyViewers(cast.center, points, SharedParticleDefinitions.VESPERALA_IMPACT);
            entity.getWorld().playSound(cast.center, Sound.BLOCK_WOOD_BREAK, 1.25F, 0.6F);
            for (Player player : players) {
                if (!contains(cast, player.getLocation()) || !clearSight(cast, player.getLocation())) continue;
                try {
                    hitHandler.hit(state.boss, player, cast.ritual ? 0.90D : cast.stage.ratio);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6508, exception, state.boss.instanceId());
                }
                if (state.cast != cast || state.boss.currentHealth() <= 0.0D) return;
            }
        }
        boolean completedRite = cast.ritual && state.ritualWave == 0;
        if (cast.stage == Stage.INTRO) state.introduced = true;
        removeCast(state);
        restorePose(state);
        state.nextAt = tick + (completedRite || cast.stage == Stage.INTRO ? 60L
            : cast.ritual ? 0L : cast.stage == Stage.EXPOSURE ? 0L : 50L - state.completedPhase * 10L);
        state.boss.scriptedAction(true);
    }

    /** 40 tickだけ小さく浮上し、開始前の重力・座標・無敵状態を保存します。 */
    private void leap(Encounter state, Entity entity, long tick) {
        Location origin = entity.getLocation().clone();
        state.pose = new Pose(entity, origin, entity.hasGravity(), state.boss.damageImmune());
        state.poseEnds = tick + 40L;
        mobs.setDamageImmune(state.boss, true);
        entity.setGravity(false);
        entity.setVelocity(new Vector());
        Location hover = origin.clone().add(0.0D, 2.5D, 0.0D);
        if (loaded(hover) && hover.getBlock().isPassable() && hover.clone().add(0, 1, 0).getBlock().isPassable()) {
            entity.teleport(hover);
            state.boss.currentLocation(hover);
        }
    }

    /** 浮遊を終了し、40 tick以降の儀式予兆と反撃時間を被攻撃可能に戻します。 */
    private void restorePose(Encounter state) {
        Pose pose = state.pose;
        if (pose == null) return;
        state.pose = null;
        if (pose.entity.isValid() && !pose.entity.isDead()) {
            pose.entity.setGravity(pose.gravity);
            if (loaded(pose.origin) && pose.entity.getWorld() == pose.origin.getWorld()) {
                pose.entity.teleport(pose.origin);
                state.boss.currentLocation(pose.origin);
            }
        }
        mobs.setDamageImmune(state.boss, pose.immune);
    }

    /**
     * 場内参加者が戻った後だけ、通常AIで場外へ退避したボスを保存された会場内の安全床へ戻します。
     * CCは呼び出し元で除外し、無参加者・別world・配置不能時は強制移動しません。境界と儀式進行は保持します。
     * @param state 浮遊・無敵を復元済みの遭遇状態
     * @param entity 同一worldの有効なボス実体
     */
    private void returnToArena(Encounter state, Entity entity) {
        if (entity.getWorld() != state.center.getWorld()) return;
        Location destination = ground(state.center);
        if (destination == null) return;
        mobs.stopPathfinding(state.boss);
        entity.setVelocity(new Vector());
        if (entity.teleport(destination)) {
            state.boss.currentLocation(destination);
            state.boss.scriptedAction(true);
        }
    }

    /** 現在の波に属する非永続DisplayとBossBarだけを即時解除します。 */
    private void removeCast(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        if (cast == null) return;
        if (cast.bar != null) cast.bar.removeAll();
        for (BlockDisplay display : cast.displays) if (display.isValid()) display.remove();
        cast.displays.clear();
    }

    /** 盾露出の一時倍率をこの個体からだけ解除します。 */
    private void clearExposure(Encounter state) {
        if (effects != null) effects.clear(state.boss.instanceId(), EXPOSURE_EFFECT);
    }

    /** 非永続のトウヒ・苔片を最大8個配置します。表示物は衝突・討伐対象になりません。 */
    private static void spawnDisplays(Cast cast) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0D;
            Location location = cast.center.clone().add(Math.cos(angle) * 2.5D, 2.0D, Math.sin(angle) * 2.5D);
            if (!loaded(location)) continue;
            BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, value -> {
                value.setBlock(displayMaterial(cast.stage).createBlockData());
                value.setPersistent(false);
                value.setInvulnerable(true);
                value.setGravity(false);
            });
            cast.displays.add(display);
        }
    }

    /** 技の装飾材を既存Materialから選びます。 */
    private static Material displayMaterial(Stage stage) {
        return stage == Stage.CORRIDOR || stage == Stage.LINE ? Material.SPRUCE_LOG : Material.MOSS_BLOCK;
    }

    /** 命中判定と同じ形状から危険面・安全面を抽出し、10 tick周期の有限バッチで描画します。 */
    private void draw(Cast cast) {
        if (!cast.stage.harmful) {
            List<Location> ring = new ArrayList<>();
            ringPoints(cast.center, 2.5D, 24, ring);
            particles.spawnForNearbyViewers(cast.center, ring, SharedParticleDefinitions.VESPERALA_SAFE);
            return;
        }
        List<Location> danger = BossDamageAreaEffect.sampleArea(cast.center, ARENA_RADIUS, 1.5D,
            point -> contains(cast, point), point -> clearSight(cast, point));
        particles.spawnForNearbyViewers(cast.center, danger, SharedParticleDefinitions.VESPERALA_WARNING);
        List<Location> outline = boundaryPoints(cast);
        if (!outline.isEmpty()) particles.spawnForNearbyViewers(cast.center, outline, SharedParticleDefinitions.VESPERALA_WARNING);
        List<Location> safe = new ArrayList<>();
        Location safeSpot = cast.safeSpot;
        if (safeSpot != null) ringPoints(safeSpot, 0.7D, 16, safe);
        if (cast.stage == Stage.CORRIDOR) {
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            for (double distance = -15.0D; distance <= 15.0D; distance += 1.0D) {
                for (double across : new double[]{-1.8D, 0.0D, 1.8D}) {
                    Location point = cast.center.clone().add(cast.direction.clone().multiply(distance))
                        .add(side.clone().multiply(across)).add(0, 0.15D, 0);
                    if (inArena(point, cast.center) && clearSight(cast, point)) safe.add(point);
                }
            }
        }
        if (!safe.isEmpty()) particles.spawnForNearbyViewers(cast.center, safe, SharedParticleDefinitions.VESPERALA_SAFE);
    }

    /** 16mの戦闘会場を1秒周期・2層128地点で示し、会場外から攻撃できない境界を可視化します。 */
    private void drawBoundary(Location center) {
        List<Location> points = new ArrayList<>();
        ringPoints(center, ARENA_RADIUS, 64, points);
        ringPoints(center.clone().add(0, 1.2D, 0), ARENA_RADIUS, 64, points);
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.VESPERALA_BOUNDARY);
    }

    /** 技ごとの境界を正確な半径・幅・角度で描き、色以外でも形状を判別できるようにします。 */
    private static List<Location> boundaryPoints(Cast cast) {
        List<Location> result = new ArrayList<>();
        if (cast.stage == Stage.THORNS) {
            ringPoints(cast.mark, 3.2D, 48, result);
        } else if (cast.stage == Stage.INNER || cast.stage == Stage.OUTER) {
            ringPoints(cast.center, 5.5D, 64, result);
        } else if (cast.stage == Stage.FAN) {
            double facing = Math.atan2(cast.direction.getZ(), cast.direction.getX());
            for (int degree = -35; degree <= 35; degree += 5) {
                double angle = facing + Math.toRadians(degree);
                result.add(cast.center.clone().add(Math.cos(angle) * 10.0D, 0.15D, Math.sin(angle) * 10.0D));
            }
            for (double distance = 0.5D; distance <= 10.0D; distance += 0.5D) {
                for (int sign : new int[]{-1, 1}) {
                    double angle = facing + sign * Math.toRadians(35.0D);
                    result.add(cast.center.clone().add(Math.cos(angle) * distance, 0.15D, Math.sin(angle) * distance));
                }
            }
        } else {
            double width = cast.stage == Stage.CORRIDOR ? 2.0D : cast.stage == Stage.CROSS ? 1.5D : 1.4D;
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            for (double distance = -16.0D; distance <= 16.0D; distance += 0.75D) {
                for (int sign : new int[]{-1, 1}) {
                    Location point = cast.center.clone().add(cast.direction.clone().multiply(distance))
                        .add(side.clone().multiply(width * sign)).add(0, 0.15D, 0);
                    if (inArena(point, cast.center)) result.add(point);
                    if (cast.stage == Stage.CROSS) {
                        point = cast.center.clone().add(side.clone().multiply(distance))
                            .add(cast.direction.clone().multiply(width * sign)).add(0, 0.15D, 0);
                        if (inArena(point, cast.center)) result.add(point);
                    }
                }
            }
        }
        return result.stream().filter(point -> inArena(point, cast.center) && clearSight(cast, point)).limit(176).toList();
    }

    /** 波のBossBarと参加者集合を更新し、完了時刻に合わせて表示を減らします。 */
    private static void updateBar(Encounter state, Cast cast, List<Player> players, long tick) {
        if (cast.bar == null) return;
        cast.bar.setTitle(title(state, cast));
        cast.bar.setProgress(Math.clamp((cast.ends - tick) / (double) Math.max(1L, cast.ends - cast.started), 0.0D, 1.0D));
        Set<UUID> wanted = new HashSet<>();
        for (Player player : players) {
            wanted.add(player.getUniqueId());
            if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player);
        }
        for (Player player : List.copyOf(cast.bar.getPlayers())) if (!wanted.contains(player.getUniqueId())) cast.bar.removePlayer(player);
    }

    /** 現在波に対応する回避説明を既存のメッセージ書式処理で表示します。 */
    private static String title(Encounter state, Cast cast) {
        return PlayerMsgResource.format(cast.stage.message.getId(), cast.ritual ? state.ritualWave + 1 : 1);
    }

    /** 現在有効な参加者を足元半径16m・上下3mへ制限し、跳躍だけで攻撃域から消えないようにします。 */
    private List<Player> active(MobInstance boss) {
        Location center = boss.spawnLocation();
        return participants.apply(boss).stream().filter(Player::isOnline).filter(Player::isValid).filter(player -> !player.isDead())
            .filter(player -> player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR)
            .filter(player -> inArena(player.getLocation(), center)).toList();
    }

    /** 同一会場内かを水平距離と高さで判定します。 */
    static boolean inArena(Location point, Location center) {
        return point.getWorld() == center.getWorld() && Math.abs(point.getY() - center.getY()) <= 3.0D
            && horizontalSquared(point, center) <= ARENA_RADIUS * ARENA_RADIUS;
    }

    /** 予兆・命中・着弾表示が共有する固定形状です。通常ジャンプは高さ制限内に含めます。 */
    private static boolean contains(Cast cast, Location point) {
        if (!inArena(point, cast.center)) return false;
        double x = point.getX() - cast.center.getX(), z = point.getZ() - cast.center.getZ();
        double radiusSq = x * x + z * z;
        double along = x * cast.direction.getX() + z * cast.direction.getZ();
        double across = Math.abs(x * cast.direction.getZ() - z * cast.direction.getX());
        return switch (cast.stage) {
            case FAN -> radiusSq <= 100.0D && (radiusSq < 0.01D || along / Math.sqrt(radiusSq) >= Math.cos(Math.toRadians(35.0D)));
            case THORNS -> horizontalSquared(point, cast.mark) <= 3.2D * 3.2D;
            case LINE -> Math.abs(along) <= 16.0D && across <= 1.4D;
            case OUTER -> radiusSq > 5.5D * 5.5D;
            case INNER -> radiusSq <= 5.5D * 5.5D;
            case CROSS -> Math.abs(along) <= 1.5D || across <= 1.5D;
            case CORRIDOR -> across > 2.0D;
            default -> false;
        };
    }

    /** 同じ固定面内の壁越し攻撃と着弾表示を除外します。 */
    private static boolean clearSight(Cast cast, Location point) {
        Location from = (cast.stage == Stage.THORNS ? cast.mark : cast.center).clone().add(0, 1.0D, 0);
        Vector delta = point.clone().add(0, 1.0D, 0).toVector().subtract(from.toVector());
        if (delta.lengthSquared() <= 0.01D) return true;
        return from.getWorld().rayTraceBlocks(from, delta.clone().normalize(), delta.length(),
            FluidCollisionMode.NEVER, true) == null;
    }

    /** 固定対象から歩いて届く安全な1地点を探索し、穴・危険床・別階を使わないようにします。 */
    private static @Nullable Location reachableSafe(Cast cast, Location from) {
        if (safeCircle(cast, from)) return from.clone();
        for (double distance = 1.0D; distance <= 16.0D; distance += 1.0D) {
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI / 12.0D;
                Location candidate = ground(from.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance));
                if (candidate == null || !inArena(candidate, cast.center) || contains(cast, candidate)) continue;
                if (safeCircle(cast, candidate) && walkablePath(from, candidate)) return candidate;
            }
        }
        return null;
    }

    /** 白い安全輪の中心と円周全体が会場内の歩ける非危険面であることを確認します。 */
    private static boolean safeCircle(Cast cast, Location center) {
        if (!inArena(center, cast.center) || contains(cast, center) || !walkable(center)) return false;
        for (int edge = 0; edge < 12; edge++) {
            Location rim = center.clone().add(Math.cos(edge * Math.PI / 6.0D) * 0.7D, 0,
                Math.sin(edge * Math.PI / 6.0D) * 0.7D);
            if (!inArena(rim, cast.center) || contains(cast, rim) || !walkable(rim)) return false;
        }
        return true;
    }

    /** 頭上の別階を採用せず、足元付近の歩行面へ投影します。 */
    private static @Nullable Location ground(Location point) {
        if (!loaded(point)) return null;
        World world = point.getWorld();
        for (int y = point.getBlockY(); y >= Math.max(world.getMinHeight(), point.getBlockY() - 2); y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid()) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (top > point.getY() + 0.05D || top < point.getY() - 1.5D) continue;
            Location result = point.clone();
            result.setY(top);
            if (walkable(result)) return result;
        }
        return null;
    }

    /** 穴・液体・接触ダメージ床と、頭上2mの壁を除外します。 */
    private static boolean walkable(Location point) {
        if (!loaded(point)) return false;
        Block floor = point.clone().add(0, -0.05D, 0).getBlock();
        Material material = floor.getType();
        if (!material.isSolid() || floor.isLiquid() || material == Material.MAGMA_BLOCK
            || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE || material == Material.CACTUS) return false;
        for (double y = 0.1D; y <= 1.9D; y += 0.6D) {
            Block space = point.clone().add(0, y, 0).getBlock();
            if (!space.isPassable() || space.isLiquid() || space.getType() == Material.FIRE
                || space.getType() == Material.SOUL_FIRE || space.getType() == Material.SWEET_BERRY_BUSH) return false;
        }
        return true;
    }

    /** 0.5m間隔で移動経路と左右の体幅を検査します。 */
    private static boolean walkablePath(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector()).setY(0);
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.5D));
        Vector side = delta.lengthSquared() > 0.01D ? new Vector(-delta.getZ(), 0, delta.getX()).normalize().multiply(0.35D) : new Vector();
        double previousY = from.getY();
        for (int i = 0; i <= steps; i++) {
            Location probe = from.clone().add(delta.clone().multiply(i / (double) steps));
            probe.setY(previousY);
            Location floor = ground(probe);
            if (floor == null || Math.abs(floor.getY() - previousY) > 1.0D
                || !walkable(floor.clone().add(side)) || !walkable(floor.clone().subtract(side))) return false;
            previousY = floor.getY();
        }
        return true;
    }

    /** 読み込み済みchunkだけで演出・安全床を確認します。 */
    private static boolean loaded(Location point) {
        return point.getWorld() != null && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }

    /** 有限個の円周点を足元表示へ追加します。 */
    private static void ringPoints(Location center, double radius, int count, List<Location> out) {
        for (int i = 0; i < count; i++) {
            double angle = 2.0D * Math.PI * i / count;
            out.add(center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius));
        }
    }

    /** 二地点の水平距離の二乗を返します。 */
    private static double horizontalSquared(Location a, Location b) {
        double x = a.getX() - b.getX(), z = a.getZ() - b.getZ();
        return x * x + z * z;
    }

    /** 共通DamageServiceへ魔法・無属性の倍率を渡します。 */
    @FunctionalInterface interface HitHandler { void hit(@NotNull MobInstance boss, @NotNull Player player, double ratio); }

    private enum Stage {
        INTRO(40L, 0, false, PlayerMsgId.P_7640),
        FAN(40L, 0.70D, true, PlayerMsgId.P_7641),
        THORNS(45L, 0.74D, true, PlayerMsgId.P_7642),
        LINE(35L, 0.76D, true, PlayerMsgId.P_7643),
        OUTER(45L, 0.90D, true, PlayerMsgId.P_7644),
        INNER(45L, 0.90D, true, PlayerMsgId.P_7645),
        CROSS(45L, 0.90D, true, PlayerMsgId.P_7646),
        CORRIDOR(45L, 0.90D, true, PlayerMsgId.P_7647),
        EXPOSURE(60L, 0, false, PlayerMsgId.P_7648);
        private final long warning;
        private final double ratio;
        private final boolean harmful;
        private final PlayerMsgId message;
        Stage(long warning, double ratio, boolean harmful, PlayerMsgId message) {
            this.warning = warning; this.ratio = ratio; this.harmful = harmful; this.message = message;
        }
    }

    private static final class Encounter {
        private final MobInstance boss;
        private final Location center;
        private int completedPhase = 1, requestedPhase = 1, ritualWave, actionIndex;
        private long nextAt, poseEnds;
        private double lastShield;
        private boolean introduced, exposurePending;
        private Vector ritualDirection = new Vector(1, 0, 0);
        private @Nullable Cast cast;
        private @Nullable Pose pose;
        Encounter(MobInstance boss) { this.boss = boss; center = boss.spawnLocation(); lastShield = boss.currentShield(); }
    }

    private static final class Cast {
        private final Stage stage;
        private final Location center, mark;
        private final Vector direction;
        private final long started, ends;
        private final boolean ritual;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private @Nullable Location safeSpot;
        private @Nullable BossBar bar;
        Cast(Stage stage, Location center, Location mark, Vector direction, long started, long ends, boolean ritual) {
            this.stage = stage; this.center = center; this.mark = mark; this.direction = direction;
            this.started = started; this.ends = ends; this.ritual = ritual;
        }
    }

    private record Pose(Entity entity, Location origin, boolean gravity, boolean immune) {}
}
