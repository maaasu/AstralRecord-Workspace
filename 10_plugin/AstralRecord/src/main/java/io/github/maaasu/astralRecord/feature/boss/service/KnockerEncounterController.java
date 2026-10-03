package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
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
import org.bukkit.entity.Mob;
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

/**
 * クノッカー専用の同期戦闘進行です。参加者確定・フィールド・報酬・Mob寿命は既存の
 * BossChallengeService / MobService に任せ、このクラスは演出と技の状態だけを保持します。
 * BossMechanicService の5tick時計からメインスレッドで呼び出してください。
 */
final class KnockerEncounterController {
    static final String BOSS_ID = "mine_bell_knocker";
    private static final double ARENA_RADIUS = 18.0D;
    private static final double ENGAGEMENT_RADIUS = 32.0D;
    private static final double HEIGHT_TOLERANCE = 3.0D;
    private static final double LINE_HALF_WIDTH = 1.65D;
    private static final double FALL_RADIUS = 2.6D;
    private static final double RING_RADIUS = 6.0D;
    private static final double SAFE_HALF_WIDTH = 2.4D;
    private static final long DRAW_PERIOD = 10L;
    private static final String BELL_EFFECT = "knocker_bell_guard";
    private static final String STAGGER_EFFECT = "knocker_stagger";

    private final MobService mobService;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private @Nullable TemporarySkillEffectService temporarySkillEffectService;

    /**
     * @param mobService 場外へ押し出されたボスを出現地点へ戻す共通サービス
     * @param particles 共通の粒子表示サービス
     * @param participants 固定挑戦参加者のうち有効な戦闘プレイヤーだけを返す関数
     * @param hitHandler DamageService へ倍率を渡す攻撃関数
     */
    KnockerEncounterController(
        @NotNull MobService mobService,
        @NotNull ParticleDisplayService particles,
        @NotNull Function<MobInstance, List<Player>> participants,
        @NotNull HitHandler hitHandler
    ) {
        this.mobService = mobService;
        this.particles = particles;
        this.participants = participants;
        this.hitHandler = hitHandler;
    }

    /** @param service 戦闘中の被ダメージ倍率を適用する共通サービス。未設定なら倍率を省略する */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) {
        this.temporarySkillEffectService = service;
    }

    /**
     * 1体の戦闘状態を5tickだけ進めます。例外時は攻撃と所有表示を回収して再予兆します。
     * @param boss 生存するクノッカーMob
     * @param entity 対応する有効なBukkit実体
     * @param tick BossMechanicService の単調増加する時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        state.lastEntity = entity;
        try {
            if (entity.getWorld() != boss.spawnLocation().getWorld()) {
                clear(boss.instanceId());
                return;
            }
            if (horizontalSquared(entity.getLocation(), boss.spawnLocation()) > ARENA_RADIUS * ARENA_RADIUS) {
                cancelCast(boss.instanceId());
                mobService.resetPosition(boss, boss.spawnLocation());
                state.nextAt = tick + 20L;
                return;
            }
            List<Player> eligible = active(boss, boss.spawnLocation());
            if (eligible.isEmpty()) {
                cancelCast(boss.instanceId());
                state.nextAt = tick + 20L;
                return;
            }
            if (tick % 20L == 0L) drawBoundary(boss.spawnLocation());
            if (state.cast != null) {
                advance(state, entity, tick);
                return;
            }
            if (tick < state.nextAt || boss.scriptedAction()) return;
            if (!state.introduced) {
                start(state, entity, Stage.INTRO, tick, null);
                return;
            }
            if (tick >= state.nextBoundaryAt
                && eligible.stream().anyMatch(player -> !inArena(player.getLocation(), boss.spawnLocation()))) {
                start(state, entity, Stage.RETURN, tick, null);
                state.nextBoundaryAt = tick + 200L;
                return;
            }
            // 場内の対象がいない間は幕の告知と技順を消費しない。
            if (inner(boss, boss.spawnLocation()).isEmpty()) return;
            int observed = phaseFor(boss);
            if (observed > state.completedPhase) {
                state.pendingPhase = state.completedPhase + 1;
                announce(state, PlayerMsgId.P_6589, state.pendingPhase);
                start(state, entity, Stage.BELLS, tick, null);
                return;
            }
            Stage next = switch (state.completedPhase) {
                case 1 -> switch (state.actionIndex++ % 3) {
                    case 0 -> Stage.LINE;
                    case 1 -> Stage.FALL;
                    default -> Stage.INNER;
                };
                case 2 -> switch (state.actionIndex++ % 4) {
                    case 0 -> Stage.LINE;
                    case 1 -> Stage.FALL;
                    case 2 -> Stage.INNER;
                    default -> Stage.BELLS;
                };
                default -> Stage.LINE;
            };
            state.combo = state.completedPhase == 3;
            start(state, entity, next, tick, null);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6507, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 20L;
        }
    }

    /** 完了済みフェーズを保持し、進行中の技だけを中断します。 */
    void cancelCast(@NotNull UUID bossId) {
        Encounter state = encounters.get(bossId);
        if (state == null) return;
        close(state);
        clearEffects(state);
        state.pendingPhase = 0;
        state.nextAt = 0L;
    }

    /** Mob消滅やIDLE/LEASHED時に技・フェーズを含む全状態を削除します。 */
    void clear(@NotNull UUID bossId) {
        cancelCast(bossId);
        encounters.remove(bossId);
    }

    /** Plugin停止時に全個体の表示・倍率・行動ロックを解除します。 */
    void clearAll() {
        for (UUID id : List.copyOf(encounters.keySet())) clear(id);
    }

    /** @param liveIds 現在の同期走査で生存しているクノッカー個体ID */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID id : List.copyOf(encounters.keySet())) {
            if (!liveIds.contains(id)) clear(id);
        }
    }

    /** @return 指定個体の現在の表示段階。HP65%、30%を順に越える */
    private static int phaseFor(@NotNull MobInstance boss) {
        if (boss.maxHealth() <= 0.0D) return 1;
        double ratio = boss.currentHealth() / boss.maxHealth();
        return ratio <= 0.30D ? 3 : ratio <= 0.65D ? 2 : 1;
    }

    /** 新しい予兆を開始し、対象位置と方向を一度だけ確定します。 */
    private void start(@NotNull Encounter state, @NotNull Entity entity, @NotNull Stage stage,
                       long tick, @Nullable Vector inheritedDirection) {
        Location center = state.boss.spawnLocation();
        if (center.getWorld() != entity.getWorld() || !loaded(center)) return;
        List<Player> targets = stage == Stage.RETURN || stage == Stage.INTRO
            ? active(state.boss, center) : inner(state.boss, center);
        if (targets.isEmpty()) return;
        Player primary = targets.getFirst();
        if (entity instanceof Mob mob && mob.getTarget() instanceof Player aggro && targets.contains(aggro)) {
            primary = aggro;
        }
        Vector direction = inheritedDirection == null ? direction(center, primary.getLocation())
            : inheritedDirection.clone();
        List<Location> marks = stage == Stage.FALL ? snapshotFalls(targets, center) : List.of();
        if (stage == Stage.FALL && marks.isEmpty()) return;
        int bellRequired = targets.size() >= 3 ? 3 : 2;
        String title = stage == Stage.BELLS
            ? PlayerMsgResource.format(PlayerMsgId.P_6586.getId(), 0, bellRequired)
            : PlayerMsgResource.format(stage.title.getId());
        BossBar bar = Bukkit.createBossBar(title,
            stage == Stage.BELLS || stage == Stage.STAGGER ? BarColor.BLUE : BarColor.RED,
            BarStyle.SEGMENTED_10);
        Cast cast = new Cast(stage, tick, tick + stage.duration, center, direction, marks, bar);
        state.cast = cast;
        state.boss.scriptedAction(true);
        entity.setVelocity(entity.getVelocity().setX(0.0D).setZ(0.0D));
        if (stage == Stage.BELLS) {
            if (!spawnBells(cast)) {
                close(state);
                clearEffect(state.boss, BELL_EFFECT);
                // 壊れた床によってフェーズ移行を無限に再試行しない。
                if (state.pendingPhase > 0) completePhase(state, state.pendingPhase);
                state.nextAt = tick + 20L;
                return;
            }
            cast.required = bellRequired;
            applyEffect(state.boss, BELL_EFFECT, stage.duration, 0.35D);
        } else if (stage == Stage.STAGGER) {
            applyEffect(state.boss, STAGGER_EFFECT, stage.duration, 1.5D);
        }
        updateBar(state, cast, tick);
        sound(center, stage == Stage.BELLS ? Sound.BLOCK_BELL_USE : Sound.BLOCK_NOTE_BLOCK_HAT, 0.8F);
        draw(cast);
    }

    /** 単一技の時間・鐘操作・予兆描画・着弾を進めます。 */
    private void advance(@NotNull Encounter state, @NotNull Entity entity, long tick) {
        Cast cast = state.cast;
        if (cast == null) return;
        if (entity.getWorld() != cast.center.getWorld() || !loaded(cast.center)
            || entity.getLocation().distanceSquared(cast.center) > ARENA_RADIUS * ARENA_RADIUS) {
            cancelCast(state.boss.instanceId());
            return;
        }
        updateBar(state, cast, tick);
        // castはtick時計、共有倍率は実時間時計なので進行中に再付与して同期する。
        if (cast.stage == Stage.BELLS) applyEffect(state.boss, BELL_EFFECT, Stage.BELLS.duration, 0.35D);
        if (cast.stage == Stage.STAGGER) applyEffect(state.boss, STAGGER_EFFECT, Stage.STAGGER.duration, 1.5D);
        if ((tick - cast.started) % DRAW_PERIOD == 0L) draw(cast);
        if (cast.stage == Stage.LINE && (tick - cast.started == 20L || tick - cast.started == 40L)) {
            sound(cast.center, Sound.BLOCK_NOTE_BLOCK_HAT, 1.15F);
        }
        if (cast.stage == Stage.BELLS) {
            advanceBell(state, cast, tick);
            if (state.cast != cast) return;
        }
        if (tick < cast.ends) return;
        if (cast.stage.harmful) impact(state, cast);
        if (state.cast != cast) return;
        finish(state, entity, cast, tick);
    }

    /** スニーク継続を個人ごとに数え、光る鐘を順番に鎮めます。 */
    private void advanceBell(@NotNull Encounter state, @NotNull Cast cast, long tick) {
        Location bell = cast.bells.get(cast.lit).getLocation();
        Set<UUID> present = new HashSet<>();
        for (Player player : active(state.boss, cast.center)) {
            if (!player.isSneaking() || !safeHeight(player.getLocation(), bell)
                || horizontalSquared(player.getLocation(), bell) > 1.8D * 1.8D
                || !clearSight(bell, player.getEyeLocation())) continue;
            UUID id = player.getUniqueId();
            present.add(id);
            long held = tick - cast.sneakTicks.computeIfAbsent(id, ignored -> tick);
            if (held < 20L || tick - cast.lastBellAt < 5L) continue;
            cast.sneakTicks.clear();
            cast.lastBellAt = tick;
            cast.done++;
            sound(bell, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.3F);
            if (cast.done >= cast.required) {
                clearEffect(state.boss, BELL_EFFECT);
                int advanced = state.pendingPhase;
                close(state);
                if (advanced > 0) completePhase(state, advanced);
                announce(state, PlayerMsgId.P_6587);
                start(state, state.lastEntity, Stage.STAGGER, tick, null);
                return;
            }
            cast.lit = (cast.lit + 1) % cast.bells.size();
            glowBell(cast);
            break;
        }
        cast.sneakTicks.keySet().retainAll(present);
    }

    /** 一つの技の着弾を固定形状内の各参加者へ1回だけ適用します。 */
    private void impact(@NotNull Encounter state, @NotNull Cast cast) {
        drawImpact(cast);
        sound(cast.center, Sound.BLOCK_ANVIL_LAND, 0.9F);
        for (Player player : active(state.boss, cast.center)) {
            Location point = player.getLocation();
            if (!contains(cast, point) || !clearSight(impactOrigin(cast, point), player.getEyeLocation())) continue;
            if (!cast.hit.add(player.getUniqueId())) continue;
            hitHandler.hit(state.boss, player, cast.stage.damageRatio);
            if (state.cast != cast || state.boss.currentHealth() <= 0.0D) return;
        }
    }

    /** 現技を閉じ、フェーズに応じて次の予兆または回復時間へ進めます。 */
    private void finish(@NotNull Encounter state, @NotNull Entity entity, @NotNull Cast cast, long tick) {
        Stage stage = cast.stage;
        Vector inherited = stage == Stage.BELLS && !cast.bells.isEmpty()
            ? direction(cast.center, cast.bells.get(cast.lit).getLocation())
            : cast.direction.clone();
        close(state);
        switch (stage) {
            case INTRO -> { state.introduced = true; state.nextAt = tick + 20L; }
            case LINE -> {
                if (state.completedPhase >= 2) start(state, entity, Stage.ECHO, tick, inherited);
                else { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            }
            case ECHO -> {
                if (state.combo) start(state, entity, Stage.FALL, tick, null);
                else { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            }
            case FALL -> {
                if (state.combo) start(state, entity, Stage.INNER, tick, null);
                else { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            }
            case INNER -> start(state, entity, Stage.OUTER, tick, null);
            case OUTER -> {
                if (state.combo) start(state, entity, Stage.BELLS, tick, null);
                else { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            }
            case BELLS -> {
                clearEffect(state.boss, BELL_EFFECT);
                if (state.pendingPhase > 0) completePhase(state, state.pendingPhase);
                announce(state, PlayerMsgId.P_6588);
                start(state, entity, Stage.CHORUS, tick, inherited);
            }
            case STAGGER -> { clearEffect(state.boss, STAGGER_EFFECT); state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            case CHORUS -> { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
            case RETURN -> { state.nextAt = tick + 20L; announce(state, PlayerMsgId.P_6591); }
        }
    }

    /** HP境界儀式を1段だけ完了させ、もう一段の境界が残れば次回tickに実行します。 */
    private static void completePhase(@NotNull Encounter state, int phase) {
        state.completedPhase = phase;
        state.pendingPhase = 0;
        state.actionIndex = 0;
        state.combo = false;
    }

    /** 当該技の表示個体とBarだけを解放し、古い判定を無効にします。 */
    private void close(@NotNull Encounter state) {
        Cast cast = state.cast;
        if (cast == null) return;
        cast.bar.removeAll();
        for (BlockDisplay display : cast.bells) if (display.isValid()) display.remove();
        cast.bells.clear();
        cast.sneakTicks.clear();
        state.cast = null;
        state.boss.scriptedAction(false);
    }

    /** 鐘と反撃窓の倍率をこの個体からのみ解除します。 */
    private void clearEffects(@NotNull Encounter state) {
        clearEffect(state.boss, BELL_EFFECT);
        clearEffect(state.boss, STAGGER_EFFECT);
    }

    /** 共有倍率サービスへ専用効果を付けます。 */
    private void applyEffect(@NotNull MobInstance boss, @NotNull String id, long ticks, double incoming) {
        if (temporarySkillEffectService != null) {
            temporarySkillEffectService.apply(boss.instanceId(), id, ticks, incoming, 1.0D, 1.0D);
        }
    }

    /** 共有倍率サービスから専用効果だけを取り除きます。 */
    private void clearEffect(@NotNull MobInstance boss, @NotNull String id) {
        if (temporarySkillEffectService != null) temporarySkillEffectService.clear(boss.instanceId(), id);
    }

    /** 有効な固定参加者を同一フィールド内・生存・32m内へ絞ります。 */
    private @NotNull List<Player> active(@NotNull MobInstance boss, @NotNull Location center) {
        List<Player> result = new ArrayList<>();
        for (Player player : participants.apply(boss)) {
            if (!player.isOnline() || player.isDead() || player.getWorld() != center.getWorld()
                || !safeHeight(player.getLocation(), center)
                || horizontalSquared(player.getLocation(), center) > ENGAGEMENT_RADIUS * ENGAGEMENT_RADIUS) continue;
            result.add(player);
        }
        return result;
    }

    /** 通常技で狙う内側18mの参加者だけを返します。 */
    private @NotNull List<Player> inner(@NotNull MobInstance boss, @NotNull Location center) {
        return active(boss, center).stream().filter(player -> inArena(player.getLocation(), center)).toList();
    }

    /** 参加者が戦闘円内にいるか返します。 */
    static boolean inArena(@NotNull Location point, @NotNull Location center) {
        return safeHeight(point, center)
            && horizontalSquared(point, center) <= ARENA_RADIUS * ARENA_RADIUS;
    }

    /** 参加者の足元を移動不能な予兆中心として確定します。 */
    private static @NotNull List<Location> snapshotFalls(@NotNull List<Player> targets, @NotNull Location center) {
        List<Location> marks = new ArrayList<>(targets.size());
        for (Player player : targets) {
            Location ground = ground(player.getLocation());
            if (ground != null && safeHeight(ground, center)
                && horizontalSquared(ground, center) <= ARENA_RADIUS * ARENA_RADIUS) marks.add(ground);
        }
        return List.copyOf(marks);
    }

    /** 足場と頭上空間を確認し、対象近くの安全な地表を返します。 */
    private static @Nullable Location ground(@NotNull Location point) {
        World world = point.getWorld();
        if (world == null || !loaded(point)) return null;
        int x = point.getBlockX(), z = point.getBlockZ();
        for (int y = point.getBlockY(); y >= Math.max(world.getMinHeight() + 1, point.getBlockY() - 5); y--) {
            Block floor = world.getBlockAt(x, y - 1, z);
            if (floor.getType().isSolid() && world.getBlockAt(x, y, z).isPassable()
                && world.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(world, x + 0.5D, y, z + 0.5D);
            }
        }
        return null;
    }

    /** 鐘の表示を中心から半径6mの三角形に生成します。 */
    private static boolean spawnBells(@NotNull Cast cast) {
        World world = cast.center.getWorld();
        if (world == null) return false;
        for (int i = 0; i < 3; i++) {
            double angle = 2.0D * Math.PI * i / 3.0D;
            Location projected = cast.center.clone().add(Math.cos(angle) * RING_RADIUS, 0, Math.sin(angle) * RING_RADIUS);
            Location place = ground(projected);
            if (place == null || !safeHeight(place, cast.center)) return false;
            BlockDisplay display = world.spawn(place.clone().add(0.0D, 0.25D, 0.0D), BlockDisplay.class);
            display.setBlock(Bukkit.createBlockData(Material.BELL));
            display.setPersistent(false);
            cast.bells.add(display);
        }
        glowBell(cast);
        return true;
    }

    /** 現在の操作対象の鐘だけを発光させます。 */
    private static void glowBell(@NotNull Cast cast) {
        for (int i = 0; i < cast.bells.size(); i++) cast.bells.get(i).setGlowing(i == cast.lit);
    }

    /** 技の実際の危険領域と共通の幾何判定を返します。 */
    private static boolean contains(@NotNull Cast cast, @NotNull Location point) {
        if (!safeHeight(point, cast.center)) return false;
        double radius = Math.sqrt(horizontalSquared(point, cast.center));
        if (cast.stage == Stage.RETURN) return radius > ARENA_RADIUS && radius <= ENGAGEMENT_RADIUS;
        if (radius > ARENA_RADIUS) return false;
        return switch (cast.stage) {
            case LINE, ECHO -> {
                double dx = point.getX() - cast.center.getX(), dz = point.getZ() - cast.center.getZ();
                double along = dx * cast.direction.getX() + dz * cast.direction.getZ();
                double across = Math.abs(dx * cast.direction.getZ() - dz * cast.direction.getX());
                yield along >= -0.5D && along <= ARENA_RADIUS && across <= LINE_HALF_WIDTH;
            }
            case FALL -> cast.marks.stream().anyMatch(mark -> safeHeight(point, mark)
                && horizontalSquared(point, mark) <= FALL_RADIUS * FALL_RADIUS);
            case INNER -> radius <= RING_RADIUS;
            case OUTER -> radius > RING_RADIUS;
            case CHORUS -> {
                double dx = point.getX() - cast.center.getX(), dz = point.getZ() - cast.center.getZ();
                yield Math.abs(dx * cast.direction.getZ() - dz * cast.direction.getX()) > SAFE_HALF_WIDTH;
            }
            default -> false;
        };
    }

    /** 落盤だけは固定円から、他の技は闘技場中心から遮蔽判定します。 */
    private static @NotNull Location impactOrigin(@NotNull Cast cast, @NotNull Location point) {
        if (cast.stage != Stage.FALL) return cast.center.clone().add(0.0D, 1.1D, 0.0D);
        return cast.marks.stream().min((a, b) -> Double.compare(horizontalSquared(a, point), horizontalSquared(b, point)))
            .orElse(cast.center).clone().add(0.0D, 2.0D, 0.0D);
    }

    /** 壁を越えるヒットを防ぎます。 */
    private static boolean clearSight(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        return distance < 0.1D || from.getWorld().rayTraceBlocks(from, delta.normalize(), distance,
            FluidCollisionMode.NEVER, true) == null;
    }

    /** 最大192点の予兆を10tick周期で一括送信します。 */
    private void draw(@NotNull Cast cast) {
        List<Location> danger = new ArrayList<>(192), safe = new ArrayList<>(48);
        switch (cast.stage) {
            case LINE, ECHO -> linePoints(cast, danger, LINE_HALF_WIDTH);
            case FALL -> { for (Location mark : cast.marks) circlePoints(mark, FALL_RADIUS, 24, danger); }
            case INNER, OUTER -> {
                circlePoints(cast.center, RING_RADIUS, 48, danger);
                if (cast.stage == Stage.INNER) {
                    for (int ring = 1; ring <= 3; ring++) circlePoints(cast.center, ring * 1.5D, 16, danger);
                } else {
                    for (int ring = 1; ring <= 7; ring++) circlePoints(cast.center, RING_RADIUS + ring * 1.5D, 16, danger);
                }
                circlePoints(cast.center, cast.stage == Stage.INNER ? 9.0D : 3.0D, 24, safe);
            }
            case BELLS -> { if (!cast.bells.isEmpty()) circlePoints(cast.bells.get(cast.lit).getLocation(), 1.8D, 20, safe); }
            case CHORUS -> corridorPoints(cast, safe, danger);
            case RETURN -> {
                circlePoints(cast.center, ARENA_RADIUS, 48, safe);
                circlePoints(cast.center, ENGAGEMENT_RADIUS, 48, danger);
                for (int ring = 1; ring <= 4; ring++) {
                    circlePoints(cast.center, ARENA_RADIUS + ring * (ENGAGEMENT_RADIUS - ARENA_RADIUS) / 5.0D,
                        24, danger);
                }
            }
            default -> circlePoints(cast.center, 2.0D, 24, safe);
        }
        if (!danger.isEmpty()) particles.spawnForNearbyViewers(cast.center, danger.subList(0, Math.min(192, danger.size())),
            SharedParticleDefinitions.KNOCKER_WARNING);
        if (!safe.isEmpty()) particles.spawnForNearbyViewers(cast.center, safe.subList(0, Math.min(48, safe.size())),
            SharedParticleDefinitions.KNOCKER_SAFE);
    }

    /** 着弾の輪郭を描画します。 */
    private void drawImpact(@NotNull Cast cast) {
        List<Location> points = new ArrayList<>(96);
        if (cast.stage == Stage.LINE || cast.stage == Stage.ECHO) linePoints(cast, points, LINE_HALF_WIDTH);
        else if (cast.stage == Stage.FALL) { for (Location mark : cast.marks) circlePoints(mark, FALL_RADIUS, 20, points); }
        else if (cast.stage == Stage.CHORUS) corridorPoints(cast, new ArrayList<>(), points);
        else if (cast.stage == Stage.RETURN) {
            circlePoints(cast.center, ARENA_RADIUS, 48, points);
            circlePoints(cast.center, ENGAGEMENT_RADIUS, 48, points);
        }
        else circlePoints(cast.center, RING_RADIUS, 48, points);
        if (!points.isEmpty()) particles.spawnForNearbyViewers(cast.center,
            points.subList(0, Math.min(192, points.size())), SharedParticleDefinitions.KNOCKER_IMPACT);
    }

    /** フィールド半径を20tick間隔で表示します。 */
    private void drawBoundary(@NotNull Location center) {
        List<Location> points = new ArrayList<>(48);
        circlePoints(center, ARENA_RADIUS, 48, points);
        particles.spawnForNearbyViewers(center, points, SharedParticleDefinitions.KNOCKER_BOUNDARY);
    }

    /** 線の両縁を当たり判定と同じ長さで作ります。 */
    private static void linePoints(@NotNull Cast cast, @NotNull List<Location> out, double width) {
        double edgeEnd = Math.sqrt(ARENA_RADIUS * ARENA_RADIUS - width * width);
        for (int i = 0; i <= 18; i++) {
            double d = -0.5D + i * (edgeEnd + 0.5D) / 18.0D;
            double x = cast.direction.getX(), z = cast.direction.getZ();
            out.add(cast.center.clone().add(x * d - z * width, 0.15D, z * d + x * width));
            out.add(cast.center.clone().add(x * d + z * width, 0.15D, z * d - x * width));
        }
        // 18m円で切られる前端を円弧で閉じ、描画も実際の危険領域に収める。
        for (int i = -4; i <= 4; i++) {
            double across = width * i / 4.0D;
            double along = Math.sqrt(ARENA_RADIUS * ARENA_RADIUS - across * across);
            out.add(cast.center.clone().add(cast.direction.clone().multiply(along))
                .add(-cast.direction.getZ() * across, 0.15D, cast.direction.getX() * across));
        }
    }

    /** 円環の点を指定数だけ作ります。 */
    private static void circlePoints(@NotNull Location center, double radius, int count,
                                     @NotNull List<Location> out) {
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            out.add(center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius));
        }
    }

    /** 安全通路と外側の危険位置を同時に作ります。 */
    private static void corridorPoints(@NotNull Cast cast, @NotNull List<Location> safe,
                                       @NotNull List<Location> danger) {
        Vector side = new Vector(-cast.direction.getZ(), 0.0D, cast.direction.getX());
        for (int i = -9; i <= 9; i++) {
            double along = i * 2.0D;
            for (double edge : new double[]{-SAFE_HALF_WIDTH, SAFE_HALF_WIDTH}) {
                Location point = cast.center.clone().add(cast.direction.clone().multiply(along))
                    .add(side.clone().multiply(edge)).add(0.0D, 0.15D, 0.0D);
                if (horizontalSquared(point, cast.center) <= ARENA_RADIUS * ARENA_RADIUS) safe.add(point);
            }
        }
        for (int ring = 1; ring <= 4; ring++) {
            double radius = ring * ARENA_RADIUS / 4.0D;
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI * 2.0D / 24.0D;
                double x = Math.cos(angle) * radius, z = Math.sin(angle) * radius;
                if (Math.abs(x * cast.direction.getZ() - z * cast.direction.getX()) > SAFE_HALF_WIDTH) {
                    danger.add(cast.center.clone().add(x, 0.15D, z));
                }
            }
        }
    }

    /** 残り時間と操作進捗を参加者のBossBarへ反映します。 */
    private void updateBar(@NotNull Encounter state, @NotNull Cast cast, long tick) {
        double duration = Math.max(1L, cast.ends - cast.started);
        cast.bar.setProgress(Math.clamp((cast.ends - tick) / duration, 0.0D, 1.0D));
        if (cast.stage == Stage.BELLS) {
            long held = cast.sneakTicks.values().stream().mapToLong(started -> tick - started).max().orElse(0L);
            if (held > 0L) {
                cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_6590.getId(),
                    Math.min(100, (int) (held * 100L / 20L))));
            } else {
                cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_6586.getId(), cast.done, cast.required));
            }
        }
        Set<UUID> wanted = new HashSet<>();
        for (Player player : active(state.boss, cast.center)) {
            wanted.add(player.getUniqueId());
            if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player);
        }
        for (Player player : List.copyOf(cast.bar.getPlayers())) {
            if (!wanted.contains(player.getUniqueId())) cast.bar.removePlayer(player);
        }
    }

    /** 固定参加者へMsgIdの本文を共通メッセージサービスで送信します。 */
    private void announce(@NotNull Encounter state, @NotNull PlayerMsgId id, @NotNull Object... args) {
        for (Player player : active(state.boss, state.boss.spawnLocation())) {
            PlayerMessageService.getInstance().send(player, id, args);
        }
    }

    /** 発動方向を水平に固定し、中心重なりでは東を使います。 */
    private static @NotNull Vector direction(@NotNull Location origin, @NotNull Location target) {
        Vector vector = target.toVector().subtract(origin.toVector()).setY(0.0D);
        return vector.lengthSquared() < 0.01D ? new Vector(1.0D, 0.0D, 0.0D) : vector.normalize();
    }

    /** 中心と対象の高低差が読み取れる範囲内か調べます。 */
    private static boolean safeHeight(@NotNull Location point, @NotNull Location center) {
        return point.getWorld() == center.getWorld()
            && Math.abs(point.getY() - center.getY()) <= HEIGHT_TOLERANCE;
    }

    /** 平面距離の二乗を返します。 */
    private static double horizontalSquared(@NotNull Location a, @NotNull Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** chunk未ロードの表示や床走査を避けます。 */
    private static boolean loaded(@NotNull Location point) {
        World world = point.getWorld();
        return world != null && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }

    /** 予兆音を同一フィールド内だけで再生します。 */
    private static void sound(@NotNull Location point, @NotNull Sound sound, float pitch) {
        if (point.getWorld() != null) point.getWorld().playSound(point, sound, 0.9F, pitch);
    }

    /** ダメージ処理を既存DamageServiceへ委譲します。 */
    @FunctionalInterface
    interface HitHandler {
        /** @param boss 攻撃者 @param target 固定参加者 @param ratio 攻撃倍率 */
        void hit(@NotNull MobInstance boss, @NotNull Player target, double ratio);
    }

    /** 各技の時間・ダメージ・表示IDを保持します。 */
    private enum Stage {
        INTRO(60L, 0, false, PlayerMsgId.P_6580),
        LINE(60L, 0.8D, true, PlayerMsgId.P_6581),
        ECHO(30L, 0.8D, true, PlayerMsgId.P_6582),
        FALL(50L, 0.9D, true, PlayerMsgId.P_6583),
        INNER(45L, 0.9D, true, PlayerMsgId.P_6584),
        OUTER(45L, 0.9D, true, PlayerMsgId.P_6585),
        BELLS(240L, 0, false, PlayerMsgId.P_6586),
        STAGGER(80L, 0, false, PlayerMsgId.P_6587),
        CHORUS(80L, 1.1D, true, PlayerMsgId.P_6588),
        RETURN(60L, 1.1D, true, PlayerMsgId.P_6592);

        private final long duration;
        private final double damageRatio;
        private final boolean harmful;
        private final PlayerMsgId title;

        /** @param duration 予兆時間 @param damageRatio 命中倍率
         * @param harmful 着弾の有無 @param title 共通表示メッセージ */
        Stage(long duration, double damageRatio, boolean harmful, PlayerMsgId title) {
            this.duration = duration;
            this.damageRatio = damageRatio;
            this.harmful = harmful;
            this.title = title;
        }
    }

    /** 個体の進行状況です。 */
    private static final class Encounter {
        private final MobInstance boss;
        private Entity lastEntity;
        private int completedPhase = 1;
        private int pendingPhase;
        private int actionIndex;
        private long nextAt;
        private long nextBoundaryAt;
        private boolean introduced;
        private boolean combo;
        private @Nullable Cast cast;

        /** @param boss 所有ボス */
        private Encounter(@NotNull MobInstance boss) { this.boss = boss; }
    }

    /** 単一予兆の固定形状と所有表示です。 */
    private static final class Cast {
        private final Stage stage;
        private final long started;
        private final long ends;
        private final Location center;
        private final Vector direction;
        private final List<Location> marks;
        private final BossBar bar;
        private final List<BlockDisplay> bells = new ArrayList<>(3);
        private final Map<UUID, Long> sneakTicks = new HashMap<>();
        private final Set<UUID> hit = new HashSet<>();
        private int lit;
        private int required;
        private int done;
        private long lastBellAt;

        /** @param stage 技 @param started 開始時刻 @param ends 着弾時刻
         * @param center 闘技場中心 @param direction 固定方向 @param marks 落盤中心
         * @param bar 所有BossBar */
        private Cast(Stage stage, long started, long ends, Location center, Vector direction,
                     List<Location> marks, BossBar bar) {
            this.stage = stage;
            this.started = started;
            this.ends = ends;
            this.center = center.clone();
            this.direction = direction.clone();
            this.marks = List.copyOf(marks);
            this.bar = bar;
        }
    }
}
