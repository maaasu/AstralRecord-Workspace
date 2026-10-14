package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** 隕石火口のイグニヴォルを、地形を変えず同期5tickで進行します。 */
final class IgnivorEncounterController {
    static final String BOSS_ID = "eriva_caldera_ignivor";
    private static final double SAFE_RADIUS = 2.2D;
    private static final int SHELL_COUNT = 24;
    private static final int MAX_DISPLAYS = 112;
    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /**
     * 既存の同期戦闘・表示サービスを接続します。
     * @param mobs 行動停止と実体同期先
     * @param particles 閲覧設定を適用する表示先
     * @param participants 同じ攻略に参加する生存プレイヤーの解決先
     * @param hitHandler 共通魔法・火属性ダメージへの委譲先
     */
    IgnivorEncounterController(@NotNull MobService mobs, @NotNull ParticleDisplayService particles,
            @NotNull Function<MobInstance, List<Player>> participants, @NotNull HitHandler hitHandler) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hitHandler = hitHandler;
    }

    /**
     * 生存し行動可能な個体を進めます。HP境界は順番に消費し、反撃時間中は次の技を開始しません。
     * @param boss 発動する個体
     * @param entity 有効な同world実体
     * @param tick 共通同期時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        try {
            List<Player> players = active(state);
            if (players.isEmpty()) { cancelCast(boss.instanceId()); return; }
            if (state.arena == null) {
                state.arena = Arena.scan(boss.spawnLocation());
                if (state.arena == null) return;
            }
            int observed = boss.currentHealth() <= boss.maxHealth() * 0.35D ? 3
                    : boss.currentHealth() <= boss.maxHealth() * 0.70D ? 2 : 1;
            state.requestedPhase = Math.max(state.requestedPhase, observed);
            if (!state.arena.contains(entity.getLocation())) { cancelCast(boss.instanceId()); return; }
            updateBurst(state, tick);
            if (state.cast != null) {
                advance(state, entity, players, tick);
                return;
            }
            if (tick < state.recoveryUntil) {
                hold(state, entity);
                boss.updateSkillCastingRemaining(state.recoveryUntil - tick);
                updateShell(state, entity, tick);
                return;
            }
            release(state);
            if (tick < state.nextAt || boss.scriptedAction() || boss.isSkillCasting()) return;
            Player target = players.stream().filter(player -> state.arena.contains(player.getLocation()))
                    .min((a, b) -> Double.compare(horizontalSquared(entity.getLocation(), a.getLocation()),
                            horizontalSquared(entity.getLocation(), b.getLocation()))).orElse(null);
            if (target == null) return;
            if (state.completedPhase < state.requestedPhase) {
                begin(state, entity, target, state.ritualWave < 3 ? Move.ERUPTION : Move.CATACLYSM, tick, true);
            } else {
                Move[] rotation = {Move.METEOR, Move.FISSURE, Move.NOVA, Move.RING};
                if (begin(state, entity, target, rotation[state.actionIndex % rotation.length], tick, false)) {
                    state.actionIndex++;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6525, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 20L;
        }
    }

    /**
     * CC・拘束・参加者喪失時に表示と所有中のロックを解除します。発動済み儀式波は保持します。
     * @param id 中断する個体
     */
    void cancelCast(@NotNull UUID id) {
        Encounter state = encounters.get(id);
        if (state == null) return;
        removeCast(state);
        removeDisplays(state.shell, id);
        removeDisplays(state.burst, id);
        release(state);
        state.safeRoute = List.of();
        state.recoveryUntil = 0L;
        state.nextAt = 0L;
    }

    /** @param id 死亡・帰還・待機した個体。表示と遭遇進行をすべて破棄します */
    void clear(@NotNull UUID id) { cancelCast(id); encounters.remove(id); }

    /** Plugin停止時に全個体の表示・詠唱・所有ロックを回収します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }

    /** @param liveIds 同期走査で確認した生存個体。消失個体を回収します */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!liveIds.contains(id)) clear(id);
    }

    /**
     * 会場外からの攻撃を拒否するため、走査済み会場を共通戦闘経路へ公開します。
     * @param boss 被弾候補の個体
     * @param point 攻撃所有者の現在位置
     * @return 同じ会場の歩行床上にいる場合true。地形を確定できない場合false
     */
    boolean inArena(@NotNull MobInstance boss, @NotNull Location point) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (state.arena == null) state.arena = Arena.scan(boss.spawnLocation());
        return state.arena != null && state.arena.contains(point);
    }

    /** 地表・回避場所・安全円経路が成立する技だけを固定予兆として予約します。 */
    private boolean begin(Encounter state, Entity entity, Player target, Move move, long tick, boolean ritual) {
        Location mark = groundNear(target.getLocation());
        Location origin = groundNear(entity.getLocation());
        if (mark == null || origin == null || !state.arena.contains(mark)) return retry(state, tick);
        Vector direction = mark.toVector().subtract(origin.toVector()).setY(0);
        if (direction.lengthSquared() < 0.01D) direction = new Vector(1, 0, 0);
        direction.normalize();
        Location safe = null;
        if (ritual) {
            if (state.safeRoute.isEmpty()) {
                state.safeRoute = safeRoute(state.arena, mark);
                if (state.safeRoute.isEmpty()) return retry(state, tick);
            }
            safe = state.safeRoute.get(Math.min(state.ritualWave, 2)).clone();
            if (!circleWalkable(state.arena, safe, SAFE_RADIUS)) {
                state.safeRoute = List.of();
                return retry(state, tick);
            }
            origin = state.arena.center.clone();
            mark = origin.clone();
        }
        Zone zone = new Zone(move, origin, mark, direction, safe, state.arena);
        if (!ritual && reachableEscape(zone, target.getLocation()) == null) return retry(state, tick);
        Cast cast = new Cast(zone, tick + move.warning, ritual);
        state.cast = cast;
        hold(state, entity);
        state.boss.startSkillCasting(move.title, move.warning);
        spawnShell(state, entity);
        updateShell(state, entity, tick);
        spawnCastDisplays(state, cast);
        draw(cast);
        entity.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 1.1F, ritual ? 0.6F : 0.85F);
        return true;
    }

    /** 予約不能時の有限再試行時刻を設定します。 */
    private static boolean retry(Encounter state, long tick) { state.nextAt = tick + 20L; return false; }

    /** 予兆・落下・発動を順次実行し、発動前に波を消費して再実行を防ぎます。 */
    private void advance(Encounter state, Entity entity, List<Player> players, long tick) {
        Cast cast = state.cast;
        hold(state, entity);
        state.boss.updateSkillCastingRemaining(Math.max(0L, cast.impactAt - tick));
        updateShell(state, entity, tick);
        updateCastDisplays(cast, tick);
        if (tick < cast.impactAt) {
            if (tick % 10L == 0L) draw(cast);
            return;
        }
        state.cast = null;
        if (cast.ritual) {
            state.ritualWave++;
            if (state.ritualWave == 4) {
                state.ritualWave = 0;
                state.completedPhase++;
                state.safeRoute = List.of();
            }
        }
        try {
            for (Player player : players) {
                if (!cast.zone.hits(player.getLocation()) || !cast.hitPlayers.add(player.getUniqueId())) continue;
                try { hitHandler.hit(state.boss, player, cast.zone.move.ratio); }
                catch (RuntimeException exception) { Logger.log(LogId.E_6526, exception, state.boss.instanceId(), player.getUniqueId()); }
            }
            particles.spawnForNearbyViewers(cast.zone.origin, cast.warningPoints,
                    SharedParticleDefinitions.WIZARD_METEOR_FLAME_BURST);
            spawnBurst(state, cast, tick);
            entity.getWorld().playSound(cast.zone.mark, Sound.ENTITY_GENERIC_EXPLODE, 1.4F,
                    cast.zone.move == Move.CATACLYSM ? 0.5F : 0.85F);
        } finally {
            removeDisplays(cast.displays, state.boss.instanceId());
            removeDisplays(cast.meteor, state.boss.instanceId());
            long recovery = cast.zone.move == Move.CATACLYSM ? 100L : cast.ritual ? 20L : 35L;
            state.recoveryUntil = tick + recovery;
            state.nextAt = state.recoveryUntil + 15L;
            state.boss.startSkillCasting("隕石殻の冷却", recovery);
        }
    }

    /** 所有している行動ロック中だけ移動を停止します。重力と無敵は変更しません。 */
    private void hold(Encounter state, Entity entity) {
        if (!state.ownsAction) { state.ownsAction = true; state.boss.scriptedAction(true); }
        mobs.stopPathfinding(state.boss);
        entity.setVelocity(new Vector());
    }

    /** 自分が開始した詠唱・ロックだけを終了します。 */
    private static void release(Encounter state) {
        if (state.ownsAction) {
            state.ownsAction = false;
            state.boss.scriptedAction(false);
            state.boss.clearSkillCasting();
        }
    }

    /** 参加者の存在する個体にだけ、非永続の外殻16片・周回岩8個を作ります。 */
    private static void spawnShell(Encounter state, Entity entity) {
        if (!state.shell.isEmpty()) return;
        for (int i = 0; i < SHELL_COUNT; i++) {
            state.shell.add(spawnDisplay(entity.getLocation(), i % 3 == 0 ? Material.MAGMA_BLOCK : Material.BLACKSTONE, 0.7F));
        }
    }

    /** 5tick補間で外殻と周回岩を更新します。閲覧者がいない場合は呼び出し元で中断します。 */
    private static void updateShell(Encounter state, Entity entity, long tick) {
        for (int i = 0; i < state.shell.size(); i++) {
            double angle = i * Math.PI / 4 + tick * (i < 16 ? 0.012D : -0.028D);
            double radius = i < 16 ? 1.5D : 3.1D;
            double height = i < 16 ? 0.8D + (i / 8) * 1.25D : 2.5D + Math.sin(angle * 2) * 0.5D;
            moveDisplay(state.shell.get(i), entity.getLocation().add(Math.cos(angle) * radius, height,
                    Math.sin(angle) * radius), i < 16 ? 0.7F : 0.5F, tick * 0.025F + i);
        }
    }

    /** 形状と同じ赤い予告標識、緑の安全輪、落下隕石を有限個だけ生成します。 */
    private static void spawnCastDisplays(Encounter state, Cast cast) {
        List<Location> danger = cast.warningPoints;
        int count = Math.min(40, danger.size());
        for (int i = 0; i < count; i++) {
            cast.displays.add(spawnDisplay(danger.get(i * danger.size() / count), Material.RED_STAINED_GLASS, 0.26F));
        }
        if (cast.zone.safe != null) {
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI / 8;
                Location point = cast.zone.safe.clone().add(Math.cos(angle) * SAFE_RADIUS, 0.1D,
                        Math.sin(angle) * SAFE_RADIUS);
                cast.displays.add(spawnDisplay(point, Material.LIME_STAINED_GLASS, 0.22F));
            }
        }
        if (cast.zone.move == Move.METEOR || cast.zone.move == Move.CATACLYSM) {
            cast.fallHeight = ceilingHeight(cast.zone.mark);
            for (int i = 0; i < 15; i++) {
                cast.meteor.add(spawnDisplay(cast.zone.mark.clone().add(0, cast.fallHeight, 0),
                        i % 3 == 0 ? Material.MAGMA_BLOCK : i % 3 == 1 ? Material.BLACKSTONE : Material.GILDED_BLACKSTONE,
                        cast.zone.move == Move.CATACLYSM ? 1.4F : 0.75F));
            }
        }
        if (state.shell.size() + cast.displays.size() + cast.meteor.size() > MAX_DISPLAYS) {
            throw new IllegalStateException("Ignivor display limit");
        }
    }

    /** 天井を有限走査し、隕石の出発高さを読込済み空間の内側へ収めます。 */
    private static double ceilingHeight(Location mark) {
        double height = 2.0D;
        for (int step = 2; step <= 16; step++) {
            Location point = mark.clone().add(0, step, 0);
            if (!loaded(point) || point.getY() >= point.getWorld().getMaxHeight() - 2
                    || !point.getBlock().isPassable()) break;
            height = Math.max(2.0D, step - 1.5D);
        }
        return height;
    }

    /** 着弾5tick前に最終位置へ移動し、補間を含む落下の完了とダメージ時刻を合わせます。 */
    private static void updateCastDisplays(Cast cast, long tick) {
        if (cast.meteor.isEmpty()) return;
        long fallTicks = Math.min(40L, cast.zone.move.warning - 10L);
        long fallStart = cast.impactAt - fallTicks;
        double progress = Math.clamp((tick - fallStart) / (double) (fallTicks - 5L), 0.0D, 1.0D);
        double height = cast.fallHeight * (1 - progress);
        float scale = cast.zone.move == Move.CATACLYSM ? 1.4F : 0.75F;
        for (int i = 0; i < cast.meteor.size(); i++) {
            double angle = i * Math.PI * 2 / cast.meteor.size() + tick * 0.08D;
            double radius = i == 0 ? 0 : scale * (0.45D + (i % 3) * 0.28D);
            Location point = cast.zone.mark.clone().add(Math.cos(angle) * radius,
                    height + 0.6D + Math.sin(i * 2.4D) * scale * 0.4D, Math.sin(angle) * radius);
            moveDisplay(cast.meteor.get(i), point, scale, tick * 0.045F + i);
        }
    }

    /** 発動面から最大16個の溶岩柱または岩片を噴出させ、実ブロックを変更しません。 */
    private static void spawnBurst(Encounter state, Cast cast, long tick) {
        removeDisplays(state.burst, state.boss.instanceId());
        state.burstAnchors = List.of();
        List<Location> anchors = new ArrayList<>();
        int count = Math.min(16, cast.warningPoints.size());
        for (int i = 0; i < count; i++) {
            Location point = cast.warningPoints.get(i * cast.warningPoints.size() / count).clone();
            anchors.add(point);
            state.burst.add(spawnDisplay(point, i % 3 == 0 ? Material.BLACKSTONE : Material.MAGMA_BLOCK, 0.65F));
        }
        state.burstAnchors = List.copyOf(anchors);
        state.burstStarted = tick;
        state.burstUntil = tick + 20L;
    }

    /** 発動済みの噴火表示だけを5tickで更新し、20tickで回収します。追加命中は行いません。 */
    private static void updateBurst(Encounter state, long tick) {
        if (state.burst.isEmpty()) return;
        if (tick >= state.burstUntil) {
            removeDisplays(state.burst, state.boss.instanceId());
            state.burstAnchors = List.of();
            return;
        }
        float progress = (tick - state.burstStarted) / 20.0F;
        for (int i = 0; i < state.burst.size(); i++) {
            Location point = state.burstAnchors.get(i).clone().add(0,
                    Math.sin(progress * Math.PI) * (1.0D + (i % 4) * 0.35D), 0);
            moveDisplay(state.burst.get(i), point, 0.65F * (1 - progress * 0.5F), tick * 0.1F + i);
        }
    }

    /** 非永続・無重力・無敵・5tick補間の装飾Displayを生成します。 */
    private static BlockDisplay spawnDisplay(Location point, Material material, float scale) {
        if (!loaded(point)) throw new IllegalStateException("Ignivor display requires a loaded chunk");
        return point.getWorld().spawn(point, BlockDisplay.class, display -> {
            display.setBlock(material.createBlockData());
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setTeleportDuration(5);
            display.setInterpolationDuration(5);
            display.setTransformation(new Transformation(new Vector3f(-scale / 2), new Quaternionf(),
                    new Vector3f(scale), new Quaternionf()));
        });
    }

    /** 既存個体の位置・回転・縮尺を更新し、未ロードchunkへ移動しません。 */
    private static void moveDisplay(BlockDisplay display, Location point, float scale, float angle) {
        if (!display.isValid() || !loaded(point)) return;
        display.teleport(point);
        display.setTransformation(new Transformation(new Vector3f(-scale / 2),
                new Quaternionf().rotateY(angle).rotateZ(angle * 0.3F), new Vector3f(scale), new Quaternionf()));
    }

    /** 命中判定と同じ点群を10tick間隔で描画します。安全円は緑と白い縦標識を併用します。 */
    private void draw(Cast cast) {
        particles.spawnForNearbyViewers(cast.zone.origin, cast.warningPoints, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        if (cast.zone.safe != null) {
            List<Location> safe = new ArrayList<>(32);
            for (int i = 0; i < 32; i++) {
                double angle = i * Math.PI / 16;
                safe.add(cast.zone.safe.clone().add(Math.cos(angle) * SAFE_RADIUS, 0.12D,
                        Math.sin(angle) * SAFE_RADIUS));
            }
            BossParticleEffect.safeArea(particles, cast.zone.safe, safe);
        }
    }

    /** 会場の地表へ投影した、命中条件を満たす最大96地点を生成します。 */
    private static List<Location> warningPoints(Zone zone) {
        return BossDamageAreaEffect.sampleArea(zone.arena.center, zone.arena.radius, 0.9D, zone::hits);
    }

    /** 現在の予兆の所有Displayだけを回収します。 */
    private static void removeCast(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        if (cast == null) return;
        removeDisplays(cast.displays, state.boss.instanceId());
        removeDisplays(cast.meteor, state.boss.instanceId());
    }

    /** 回収失敗を個別記録し、残りのDisplayと行動ロックの清掃を続けます。 */
    private static void removeDisplays(List<BlockDisplay> displays, UUID bossId) {
        for (BlockDisplay display : displays) {
            try { if (display.isValid()) display.remove(); }
            catch (RuntimeException exception) { Logger.log(LogId.E_6527, exception, bossId, display.getUniqueId()); }
        }
        displays.clear();
    }

    /** 生存・同world・近傍参加者だけを使い、閲覧者不在時は表示を生成しません。 */
    private List<Player> active(Encounter state) {
        Location center = state.boss.spawnLocation();
        return participants.apply(state.boss).stream().filter(Player::isOnline).filter(Player::isValid)
                .filter(player -> !player.isDead() && player.getWorld() == center.getWorld())
                .filter(player -> player.getGameMode() != org.bukkit.GameMode.CREATIVE
                        && player.getGameMode() != org.bukkit.GameMode.SPECTATOR)
                .filter(player -> horizontalSquared(player.getLocation(), center) <= 32.0D * 32.0D)
                .toList();
    }

    /** 会場内の歩ける三つの円を選び、隣接地点を5.5m以内の経路で結びます。 */
    private static List<Location> safeRoute(Arena arena, Location target) {
        List<Location> starts = new ArrayList<>();
        starts.add(target);
        for (double distance : new double[] {2.5D, 4.5D}) {
            for (int i = 0; i < 8; i++) starts.add(target.clone().add(Math.cos(i * Math.PI / 4) * distance,
                    0, Math.sin(i * Math.PI / 4) * distance));
        }
        starts.add(arena.center);
        for (int i = 0; i < 8; i++) starts.add(arena.center.clone().add(Math.cos(i * Math.PI / 4) * 5,
                0, Math.sin(i * Math.PI / 4) * 5));
        for (Location start : starts) {
            Location first = groundNear(start);
            if (first == null || !circleWalkable(arena, first, SAFE_RADIUS)
                    || horizontalSquared(target, first) > 36 || !walkablePath(target, first)) continue;
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6;
                Location second = groundNear(first.clone().add(Math.cos(angle) * 5.5, 0, Math.sin(angle) * 5.5));
                Location third = groundNear(first.clone().add(Math.cos(angle + Math.PI / 3) * 5.5,
                        0, Math.sin(angle + Math.PI / 3) * 5.5));
                if (second != null && third != null && circleWalkable(arena, second, SAFE_RADIUS)
                        && circleWalkable(arena, third, SAFE_RADIUS) && walkablePath(first, second)
                        && walkablePath(second, third)) return List.of(first, second, third);
            }
        }
        return List.of();
    }

    /** 通常攻撃から6m以内に、歩いて移れる危険域外の床があることを確認します。 */
    private static @Nullable Location reachableEscape(Zone zone, Location target) {
        Location from = groundNear(target);
        if (from == null) return null;
        for (double distance : new double[] {0, 2.5, 4.5, 6.0}) {
            for (int i = 0; i < 16; i++) {
                Location point = groundNear(from.clone().add(Math.cos(i * Math.PI / 8) * distance,
                        0, Math.sin(i * Math.PI / 8) * distance));
                if (point != null && circleWalkable(zone.arena, point, 0.7D)
                        && !zone.hits(point) && walkablePath(from, point)) return point;
            }
        }
        return null;
    }

    /** 半径全体を0.75m格子と輪郭で検査し、穴・危険床・壁・部屋外へ安全円を置きません。 */
    private static boolean circleWalkable(Arena arena, Location center, double radius) {
        if (!arena.contains(center) || horizontalSquared(center, arena.center) > Math.pow(arena.radius - radius, 2)) return false;
        for (double x = -radius; x <= radius; x += 0.75D) {
            for (double z = -radius; z <= radius; z += 0.75D) {
                if (x * x + z * z > radius * radius) continue;
                Location point = groundNear(center.clone().add(x, 0, z));
                if (point == null || Math.abs(point.getY() - center.getY()) > 0.6D
                        || !arena.contains(point) || !clearSight(center, point)) return false;
            }
        }
        for (int i = 0; i < 24; i++) {
            Location point = groundNear(center.clone().add(Math.cos(i * Math.PI / 12) * radius,
                    0, Math.sin(i * Math.PI / 12) * radius));
            if (point == null || Math.abs(point.getY() - center.getY()) > 0.6D || !arena.contains(point)) return false;
        }
        return true;
    }

    /** 段差・頭上・両肩幅を0.5m間隔で検査します。対象は6m以内の有限経路です。 */
    private static boolean walkablePath(Location from, Location to) {
        Location start = groundNear(from);
        if (start == null || !clearSight(start, to)) return false;
        Vector path = to.toVector().subtract(start.toVector());
        int steps = Math.clamp((int) Math.ceil(path.length() / 0.5D), 1, 16);
        Vector side = new Vector(-path.getZ(), 0, path.getX());
        if (side.lengthSquared() > 0.01D) side.normalize().multiply(0.32D);
        double previousY = start.getY();
        for (int i = 0; i <= steps; i++) {
            Location probe = start.clone().add(path.clone().multiply((double) i / steps));
            Location floor = groundNear(probe);
            if (floor == null || Math.abs(floor.getY() - previousY) > 0.6D) return false;
            for (int sign : new int[] {-1, 1}) {
                Location shoulder = groundNear(floor.clone().add(side.clone().multiply(sign)));
                if (shoulder == null || Math.abs(shoulder.getY() - floor.getY()) > 0.6D) return false;
            }
            previousY = floor.getY();
        }
        return true;
    }

    /** 足元近傍の支持床と頭上2ブロックを調べ、ハーフブロックも地表へ投影します。 */
    private static @Nullable Location groundNear(Location point) {
        if (!loaded(point)) return null;
        World world = point.getWorld();
        for (int y = Math.min(world.getMaxHeight() - 3, point.getBlockY() + 1);
                y >= Math.max(world.getMinHeight(), point.getBlockY() - 3); y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid() || dangerous(floor.getType())) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (Math.abs(top - point.getY()) > 3) continue;
            boolean clear = true;
            for (int height = 1; height <= 2; height++) {
                Block air = floor.getRelative(0, height, 0);
                if (!air.isPassable() || air.isLiquid() || dangerous(air.getType())) clear = false;
            }
            if (clear) { Location result = point.clone(); result.setY(top); return result; }
        }
        return null;
    }

    /** 火傷や接触ダメージになる床材を安全候補から除外します。 */
    private static boolean dangerous(Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.LAVA || material == Material.FIRE
                || material == Material.SOUL_FIRE || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.CACTUS || material == Material.SWEET_BERRY_BUSH
                || material == Material.WITHER_ROSE || material == Material.POWDER_SNOW;
    }

    /** worldの有効高さ・読込済みchunkを確認し、探索からchunkを読み込みません。 */
    private static boolean loaded(Location point) {
        World world = point.getWorld();
        return world != null && point.getY() > world.getMinHeight() && point.getY() < world.getMaxHeight() - 2
                && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }

    /** 地表の同じ視線条件を予兆と命中へ適用し、壁越しへ攻撃しません。 */
    private static boolean clearSight(Location from, Location to) {
        if (!loaded(from) || !loaded(to) || from.getWorld() != to.getWorld()) return false;
        Location eye = from.clone().add(0, 1.0D, 0);
        Vector direction = to.clone().add(0, 0.9D, 0).toVector().subtract(eye.toVector());
        double distance = direction.length();
        if (distance < 0.2D) return true;
        RayTraceResult hit = from.getWorld().rayTraceBlocks(eye, direction.normalize(),
                distance - 0.1D, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    /** 水平距離二乗を返します。 */
    private static double horizontalSquared(Location first, Location second) {
        double x = first.getX() - second.getX(), z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    /** 現行のMob詠唱名として表示する技名と予兆時間・倍率です。 */
    private enum Move {
        METEOR("降星の刻印", 65L, 0.78D), FISSURE("溶岩の放射裂け目", 60L, 0.68D),
        NOVA("火口の脈動", 60L, 0.72D), RING("灼熱の外環", 80L, 0.72D),
        ERUPTION("緑の避難円・連鎖噴火", 70L, 0.55D), CATACLYSM("終焉隕星・緑の避難円", 100L, 1.60D);
        private final String title;
        private final long warning;
        private final double ratio;
        Move(String title, long warning, double ratio) { this.title = title; this.warning = warning; this.ratio = ratio; }
    }

    /** 発動開始時に固定した形状を、地表予兆と実ダメージ判定で共有します。 */
    private record Zone(Move move, Location origin, Location mark, Vector direction, @Nullable Location safe, Arena arena) {
        /** world・高さ・壁・形状を含む一意な命中条件です。 */
        boolean hits(Location point) {
            if (!arena.contains(point) || Math.abs(point.getY() - origin.getY()) > 3.0D || !clearSight(origin, point)) return false;
            double distance = horizontalSquared(point, move == Move.METEOR ? mark : origin);
            return switch (move) {
                case METEOR -> distance <= 4.2D * 4.2D;
                case NOVA -> distance <= 5.5D * 5.5D;
                case RING -> distance >= 10.5D * 10.5D && distance <= Math.pow(Math.min(22.0D, arena.radius - 1), 2);
                case ERUPTION, CATACLYSM -> safe != null && horizontalSquared(point, safe) > SAFE_RADIUS * SAFE_RADIUS;
                case FISSURE -> fissureContains(point);
            };
        }
        /** 前方三方向の裂け目を会場半径に合わせた最大22m・半幅0.9mで判定します。 */
        private boolean fissureContains(Location point) {
            Vector offset = point.toVector().subtract(origin.toVector()).setY(0);
            for (double angle : new double[] {-Math.PI / 6, 0, Math.PI / 6}) {
                Vector forward = direction.clone().rotateAroundY(angle);
                double along = offset.dot(forward);
                double across = Math.abs(offset.getX() * forward.getZ() - offset.getZ() * forward.getX());
                if (along >= 0 && along <= Math.min(22.0D, arena.radius * 0.9D) && across <= 0.9D) return true;
            }
            return false;
        }
    }

    /** spawnの四方向の歩行床を走査して中心と保守的な内接円を求めます。実ブロックは変更しません。 */
    private record Arena(Location center, double radius) {
        /** 四方向最大64mまでのロード済み床/壁から、半径最大27mの会場を求めます。 */
        static @Nullable Arena scan(Location spawn) {
            Location base = groundNear(spawn);
            if (base == null) return null;
            int east = edge(base, 1, 0), west = edge(base, -1, 0);
            int south = edge(base, 0, 1), north = edge(base, 0, -1);
            if (east == 64 || west == 64 || south == 64 || north == 64
                    || east + west > 64 || south + north > 64) return null;
            Location center = groundNear(base.clone().add((east - west) / 2.0D, 0, (south - north) / 2.0D));
            if (center == null) return null;
            double radius = Math.min(27.0D, Math.min((east + west) / 2.0D, (south + north) / 2.0D) - 3.0D);
            return radius >= 7.5D ? new Arena(center, radius) : null;
        }
        /** 幅9mの断面で床と頭上壁を確認し、幅7m回廊への誤延長を避けます。低い瓦礫は境界にしません。 */
        private static int edge(Location from, int dx, int dz) {
            int found = 0;
            for (int step = 1; step <= 64; step++) {
                boolean roomSection = true;
                for (int offset : new int[] {-4, -2, 0, 2, 4}) {
                    Location point = from.clone().add(dx * step - dz * offset, 0, dz * step + dx * offset);
                    if (!loaded(point) || groundNear(point) == null
                            || !point.clone().add(0, 2, 0).getBlock().isPassable()
                            || !point.clone().add(0, 3, 0).getBlock().isPassable()) {
                        roomSection = false;
                        break;
                    }
                }
                if (!roomSection) break;
                found = step;
            }
            return found;
        }
        /** 内接円・高さ・実際の歩行床と中心からの壁遮蔽を確認します。 */
        boolean contains(Location point) {
            return point.getWorld() == center.getWorld() && horizontalSquared(point, center) <= radius * radius
                    && Math.abs(point.getY() - center.getY()) <= 3.0D && groundNear(point) != null && clearSight(center, point);
        }
    }

    /** 波単位で所有する一時表示と命中集合です。 */
    private static final class Cast {
        private final Zone zone;
        private final long impactAt;
        private final boolean ritual;
        private final List<Location> warningPoints;
        private final List<BlockDisplay> displays = new ArrayList<>();
        private final List<BlockDisplay> meteor = new ArrayList<>();
        private final Set<UUID> hitPlayers = new HashSet<>();
        private double fallHeight;
        /** 予兆開始・発動時刻と固定形状を保持します。 */
        private Cast(Zone zone, long impactAt, boolean ritual) {
            this.zone = zone; this.impactAt = impactAt; this.ritual = ritual;
            this.warningPoints = warningPoints(zone);
        }
    }

    /** 個体ごとの進行・所有ロック・外殻表示を保持します。 */
    private static final class Encounter {
        private final MobInstance boss;
        private final List<BlockDisplay> shell = new ArrayList<>();
        private final List<BlockDisplay> burst = new ArrayList<>();
        private List<Location> burstAnchors = List.of();
        private @Nullable Arena arena;
        private @Nullable Cast cast;
        private List<Location> safeRoute = List.of();
        private int requestedPhase = 1, completedPhase = 1, ritualWave, actionIndex;
        private long nextAt, recoveryUntil, burstStarted, burstUntil;
        private boolean ownsAction;
        /** 一個体につき一つの遭遇状態を作ります。 */
        private Encounter(MobInstance boss) { this.boss = boss; }
    }

    /** 共通DamageServiceへ火属性の通常倍率を委譲します。 */
    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃者 @param player 被弾参加者 @param ratio 魔法攻撃倍率 */
        void hit(@NotNull MobInstance boss, @NotNull Player player, double ratio);
    }
}
