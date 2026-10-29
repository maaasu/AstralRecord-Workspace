package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** 針の誘導、固定位置への反響、反撃機会をボス個体ごとに管理します。 */
final class HorologiumEncounterController {
    static final String BOSS_ID = "horologium_clockwork_sovereign";
    /** 半径22セルの床・壁上の歩行と跳躍を含み、盤縁の小移動では資格を失わせません。 */
    static final double ARENA_RADIUS = 24.0D;
    private static final double ARENA_HEIGHT = 6.0D;
    private static final double HAND_WIDTH = 2.4D;
    private static final double ECHO_RADIUS = 3.0D;
    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hit;
    private final Consumer<MobInstance> abort;
    private final Predicate<MobInstance> gearField;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final Map<UUID, Encounter> recoveryBarCleanupPending = new HashMap<>();
    private final Set<UUID> retiredAfterRecoveryBarFailure = new HashSet<>();

    /**
     * @param mobs Mobの移動停止に使うサービス
     * @param particles 共通粒子描画
     * @param participants 確定した戦闘参加者の解決関数
     * @param hit 共通戦闘処理への攻撃関数
     * @param abort 異常時に報酬なしで戦闘を終了する関数
     * @param gearField 生成時計盤の挑戦であるかを判定する関数
     */
    HorologiumEncounterController(MobService mobs, ParticleDisplayService particles,
                                  Function<MobInstance, List<Player>> participants, HitHandler hit,
                                  Consumer<MobInstance> abort, Predicate<MobInstance> gearField) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hit = hit;
        this.abort = abort;
        this.gearField = gearField;
    }

    /**
     * 反響後の隙で与えるダメージ補正を返します。
     * @param boss 被弾した個体
     * @param tick 単調時計
     * @return 歯車誘導成功時は1.35、通常の反撃中は1.25、それ以外は1
     */
    double incomingDamageMultiplier(MobInstance boss, long tick) {
        if (!BOSS_ID.equals(boss.template().id())) return 1.0D;
        Encounter state = encounters.get(boss.instanceId());
        return state != null && !state.disabled && tick < state.recoveryUntil ? state.recoveryMultiplier : 1.0D;
    }

    /**
     * 同期5tick周期で一個体の予兆・判定・終了を進めます。
     * @param boss 生存するMob
     * @param entity Bukkit実体
     * @param tick 単調時計
     * @param canAct 通常行動を始められるか
     * @param persistentEncounter 復帰待ち中もHPと専用行動を保持するか
     */
    void tick(MobInstance boss, Entity entity, long tick, boolean canAct, boolean persistentEncounter) {
        if (retiredAfterRecoveryBarFailure.contains(boss.instanceId())) return;
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (state.disabled) return;
        List<Player> active = List.of();
        try {
            active = participants.apply(boss);
            if (state.particlesUnavailable || state.gearDisplayUnavailable || state.soundUnavailable)
                warnPresentation(state, active);
            if (active.isEmpty()) {
                closeCast(state);
                closeRecoveryBar(state);
                boss.scriptedAction(persistentEncounter || state.originalScripted);
                state.nextAt = Math.max(state.nextAt, tick + 40L);
                return;
            }
            boss.scriptedAction(true);
            mobs.stopPathfinding(boss);
            if (state.cast == null) updateRecoveryBar(state, active, tick);
            if (!state.introduced) {
                announce(active, PlayerMsgId.P_8030);
                state.gearEnabled = gearField.test(boss);
                if (!state.gearEnabled) announce(active, PlayerMsgId.P_8040);
                state.introduced = true;
                state.nextAt = tick + 80L;
            }
            if (state.cast != null) {
                advance(state, active, tick);
                return;
            }
            if (tick < state.nextAt || !canAct) return;
            begin(state, active, tick);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_6542, failure, boss.instanceId());
            state.disabled = true;
            closeCast(state);
            closeRecoveryBar(state);
            state.boss.scriptedAction(state.originalScripted);
            try {
                abort.accept(boss);
            } catch (RuntimeException abortFailure) {
                Logger.log(LogId.E_6543, abortFailure, boss.instanceId());
                mobs.destroy(boss.instanceId());
            }
        }
    }

    /** 生存参加者の足元へ向く針を固定し、予兆中の位置変更を反響の記録に使います。 */
    private void begin(Encounter state, List<Player> active, long tick) {
        if (!closeRecoveryBar(state)) throw new IllegalStateException("Recovery BossBar could not be released");
        List<Player> inside = active.stream().filter(player -> inside(player.getLocation(), state.center)).toList();
        if (inside.isEmpty()) {
            state.nextAt = tick + 40L;
            return;
        }
        int eligiblePhase = state.boss.currentHealth() <= state.boss.maxHealth() * 0.35D ? 3
            : state.boss.currentHealth() <= state.boss.maxHealth() * 0.70D ? 2 : 1;
        if (eligiblePhase > state.phase) {
            state.phase++;
            if (state.phase == 2) announce(active, PlayerMsgId.P_8048);
            else announce(active, PlayerMsgId.P_8031, state.phase);
        }
        Player target = inside.get(state.actions++ % inside.size());
        Location aim = target.getLocation();
        double dx = aim.getX() - state.center.getX();
        double dz = aim.getZ() - state.center.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 0.01D) { dx = 0; dz = 1; length = 1; }
        HorologiumGearMarks.Mark gear = state.gearEnabled ? HorologiumGearMarks.nearest(aim, state.center) : null;
        int phase = state.phase;
        long lockDuration = phase == 3 ? 30L : 40L;
        if (gear != null) {
            double approach = Math.max(0D, gear.distance(aim, state.center) - HorologiumGearMarks.RADIUS);
            lockDuration = Math.max(lockDuration, (long) Math.ceil(approach / 3D * 20D) + 20L);
        }
        lockDuration = ((lockDuration + 4L) / 5L) * 5L;
        long lockAt = tick + lockDuration;
        long strikeAt = lockAt + (phase == 1 ? 40L : 30L);
        int bearing = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dx, -dz))), 360);
        double initialDx = dx / length;
        double initialDz = dz / length;
        boolean rotates = phase >= 2;
        int impactBearing = (bearing + (rotates ? 90 : 0)) % 360;
        long replayAt = phase == 3 ? strikeAt + 35L : -1L;
        long echoAt = phase == 3 ? replayAt + 35L : strikeAt + 35L;
        Cast cast = new Cast(phase, initialDx, initialDz, rotates ? -initialDz : initialDx,
            rotates ? initialDx : initialDz, tick, lockAt, strikeAt, replayAt, echoAt,
            phase == 3 ? 0.65D : 0.55D, target.getUniqueId(), target.getName(), bearing, impactBearing, gear);
        for (Player player : inside) cast.eligible.add(player.getUniqueId());
        cast.bar = Bukkit.createBossBar(lockTitle(cast, (lockAt - tick + 19L) / 20L),
            BarColor.YELLOW, BarStyle.SEGMENTED_10);
        state.cast = cast;
        updateBar(cast, active, tick);
        if (rotates) announce(active, PlayerMsgId.P_8049, cast.targetName, cast.bearing,
            cast.impactBearing, (lockAt - tick + 19L) / 20L, (strikeAt - tick + 19L) / 20L);
        else announce(active, PlayerMsgId.P_8033, cast.targetName, cast.bearing,
            (lockAt - tick + 19L) / 20L, (strikeAt - tick + 19L) / 20L);
        if (cast.replays()) announce(active, PlayerMsgId.P_8059, cast.bearing,
            (replayAt - tick + 19L) / 20L, (echoAt - tick + 19L) / 20L);
        if (gear != null) {
            announce(active, PlayerMsgId.P_8041, gearName(gear), gear.inner() ? 8 : 16,
                gear.bearing(), cast.targetName);
            showGear(state, cast, active);
        }
        drawSafely(state, cast, active, tick);
        playSoundSafely(state, active, Sound.BLOCK_NOTE_BLOCK_BELL, 1.6F, 0.8F);
    }

    /** 固定された足跡と針の判定後、同じ足跡に遅延反響を発生させます。 */
    private void advance(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        if (cast == null) return;
        Set<UUID> present = new HashSet<>();
        for (Player player : active) {
            if (inside(player.getLocation(), state.center)) present.add(player.getUniqueId());
        }
        cast.eligible.retainAll(present);
        cast.footprints.keySet().retainAll(cast.eligible);
        for (Player player : active) {
            UUID playerId = player.getUniqueId();
            if (present.contains(playerId) && !cast.eligible.contains(playerId)
                && cast.protectionNotified.add(playerId))
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8070);
        }
        if (!cast.locked && tick >= cast.lockAt) {
            cast.locked = true;
            for (Player player : active) {
                if (cast.eligible.contains(player.getUniqueId()) && inside(player.getLocation(), state.center))
                    cast.footprints.put(player.getUniqueId(), player.getLocation().clone());
            }
            Player target = active.stream().filter(player -> player.getUniqueId().equals(cast.targetId)).findFirst().orElse(null);
            cast.targetOnGear = cast.gear != null && target != null
                && cast.footprints.containsKey(cast.targetId)
                && cast.gear.containsPaintedFloor(target.getLocation(), state.center);
            releaseGearDisplay(state, cast);
            if (cast.gear != null && target != null && cast.eligible.contains(cast.targetId))
                PlayerMessageService.getInstance().send(target,
                    cast.targetOnGear ? PlayerMsgId.P_8071 : PlayerMsgId.P_8072);
            if (cast.rotates()) announce(active, PlayerMsgId.P_8054, cast.impactBearing,
                (cast.strikeAt - tick + 19L) / 20L);
            else announce(active, PlayerMsgId.P_8034, (cast.strikeAt - tick + 19L) / 20L);
            playSoundSafely(state, active, Sound.BLOCK_PISTON_EXTEND, 1.2F, 0.65F);
        }
        updateBar(cast, active, tick);
        if (tick < cast.strikeAt) {
            if (tick % 10L == 0) drawSafely(state, cast, active, tick);
            return;
        }
        if (!cast.struck) {
            cast.struck = true;
            updateBar(cast, active, tick);
            for (Player player : active) {
                if (cast.eligible.contains(player.getUniqueId())
                    && handHits(player.getLocation(), state.center, cast.impactDx, cast.impactDz)) {
                    cast.handHit.add(player.getUniqueId());
                    hit.hit(state.boss, player, 0.50D);
                }
                if (encounters.get(state.boss.instanceId()) != state || state.cast != cast) return;
            }
            if (cast.replays()) announce(active, PlayerMsgId.P_8057, cast.bearing,
                (cast.replayAt - tick + 19L) / 20L);
            else announce(active, PlayerMsgId.P_8035, (cast.echoAt - tick + 19L) / 20L);
            playSoundSafely(state, active, Sound.BLOCK_ANVIL_LAND, 1.0F, 1.3F);
            drawSafely(state, cast, active, tick);
            return;
        }
        if (cast.replays() && !cast.replayed) {
            if (tick < cast.replayAt) {
                if (tick % 10L == 0) drawSafely(state, cast, active, tick);
                return;
            }
            cast.replayed = true;
            updateBar(cast, active, tick);
            for (Player player : active) {
                if (cast.eligible.contains(player.getUniqueId()) && !cast.handHit.contains(player.getUniqueId())
                    && handHits(player.getLocation(), state.center, cast.initialDx, cast.initialDz)) {
                    cast.handHit.add(player.getUniqueId());
                    hit.hit(state.boss, player, 0.30D);
                }
                if (encounters.get(state.boss.instanceId()) != state || state.cast != cast) return;
            }
            announce(active, PlayerMsgId.P_8058, (cast.echoAt - tick + 19L) / 20L);
            playSoundSafely(state, active, Sound.BLOCK_PISTON_CONTRACT, 1.2F, 0.55F);
            drawSafely(state, cast, active, tick);
            return;
        }
        if (tick < cast.echoAt) {
            if (tick % 10L == 0) drawSafely(state, cast, active, tick);
            return;
        }
        for (Player player : active) {
            Location mark = cast.footprints.get(player.getUniqueId());
            if (cast.eligible.contains(player.getUniqueId()) && mark != null && sameFloor(player.getLocation(), mark)
                && player.getLocation().distanceSquared(mark) <= ECHO_RADIUS * ECHO_RADIUS)
                hit.hit(state.boss, player, cast.echoRatio);
            if (encounters.get(state.boss.instanceId()) != state || state.cast != cast) return;
        }
        playSoundSafely(state, active, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2F, 1.2F);
        boolean gearSuccess = false;
        if (cast.targetOnGear && cast.eligible.contains(cast.targetId)) {
            Location footprint = cast.footprints.get(cast.targetId);
            Player target = participants.apply(state.boss).stream()
                .filter(player -> player.getUniqueId().equals(cast.targetId)).findFirst().orElse(null);
            gearSuccess = footprint != null && target != null && inside(target.getLocation(), state.center)
                && horizontalDistanceSquared(target.getLocation(), footprint) > 3.5D * 3.5D;
        }
        closeCast(state);
        state.recoveryMultiplier = gearSuccess ? 1.35D : 1.25D;
        state.recoveryDuration = gearSuccess ? 180L : 120L;
        state.recoveryUntil = tick + state.recoveryDuration;
        state.nextAt = state.recoveryUntil;
        updateRecoveryBar(state, active, tick);
        announce(active, gearSuccess ? PlayerMsgId.P_8045 : PlayerMsgId.P_8036);
    }

    /** 粒子表示の不具合を戦闘判定から分離します。 */
    private void drawSafely(Encounter state, Cast cast, List<Player> active, long tick) {
        if (state.particlesUnavailable) return;
        try { draw(state, cast, tick); }
        catch (RuntimeException failure) {
            state.particlesUnavailable = true;
            Logger.log(LogId.E_6545, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    /** 音は操作時刻の補助に留め、失敗した個体では繰り返し試さない。 */
    private static void playSoundSafely(Encounter state, List<Player> active, Sound sound, float volume, float pitch) {
        if (state.soundUnavailable) return;
        try { state.center.getWorld().playSound(state.center, sound, volume, pitch); }
        catch (RuntimeException failure) {
            state.soundUnavailable = true;
            Logger.log(LogId.E_6546, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    /** 演出を失っても方位と秒数を読む位置を参加者ごとに一度だけ案内する。 */
    private static void warnPresentation(Encounter state, List<Player> active) {
        for (Player player : active) {
            if (!state.presentationWarned.add(player.getUniqueId())) continue;
            try { PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8063); }
            catch (RuntimeException ignored) { /* 任意演出の代替通知で戦闘を止めない。 */ }
        }
    }

    /** 現在危険な針と反響円を判定座標で描き、回転中の針だけ装飾として重ねます。 */
    private void draw(Encounter state, Cast cast, long tick) {
        if (cast.gear != null && !cast.locked) {
            List<Location> highlight = cast.gear.outline(state.center);
            particles.spawnForNearbyViewers(highlight, SharedParticleDefinitions.CHARON_SAFE_LIGHT, true);
        }
        List<Location> warning = new ArrayList<>(70);
        boolean dangerousHand = !cast.struck || cast.replays() && !cast.replayed;
        if (dangerousHand) {
            double handDx = cast.struck ? cast.initialDx : cast.impactDx;
            double handDz = cast.struck ? cast.initialDz : cast.impactDz;
            for (int step = 0; step < 15; step++) {
                double distance = 0.8D + step * 1.6D;
                for (double side : new double[] {-HAND_WIDTH, HAND_WIDTH}) {
                    warning.add(state.center.clone().add(handDx * distance - handDz * side, 0.18D,
                        handDz * distance + handDx * side));
                }
                if (step % 4 == 0)
                    warning.add(state.center.clone().add(handDx * distance, 0.18D, handDz * distance));
            }
        }
        if (cast.locked) {
            for (Location mark : cast.footprints.values()) {
                for (int i = 0; i < 6; i++) {
                    double angle = i * Math.PI / 3.0D;
                    warning.add(mark.clone().add(Math.cos(angle) * ECHO_RADIUS, 0.12D,
                        Math.sin(angle) * ECHO_RADIUS));
                }
            }
        }
        particles.spawnForNearbyViewers(warning, SharedParticleDefinitions.BOSS_ATTACK_WARNING, true);
        if (cast.rotates() && dangerousHand) {
            double progress = cast.struck
                ? Math.clamp((tick - cast.strikeAt) / (double) (cast.replayAt - cast.strikeAt), 0D, 1D)
                : Math.clamp((tick - cast.lockAt) / (double) (cast.strikeAt - cast.lockAt), 0D, 1D);
            double angle = (cast.struck ? 1D - progress : progress) * Math.PI / 2D;
            double sweepDx = cast.initialDx * Math.cos(angle) - cast.initialDz * Math.sin(angle);
            double sweepDz = cast.initialDx * Math.sin(angle) + cast.initialDz * Math.cos(angle);
            List<Location> sweep = new ArrayList<>(14);
            for (int step = 0; step < 14; step++) {
                double distance = 1D + step * 1.7D;
                sweep.add(state.center.clone().add(sweepDx * distance, 0.28D, sweepDz * distance));
            }
            particles.spawnForNearbyViewers(sweep, SharedParticleDefinitions.HOROLOGIUM_SWEEP, true);
        }
    }

    /** 固定・針・逆再演・反響を別の締切と進捗で表示し、粒子OFFでも操作時刻を伝えます。 */
    private static void updateBar(Cast cast, List<Player> active, long tick) {
        if (cast.bar == null) return;
        boolean awaitingReplay = cast.replays() && cast.struck && !cast.replayed;
        long end = awaitingReplay ? cast.replayAt : cast.struck ? cast.echoAt
            : cast.locked ? cast.strikeAt : cast.lockAt;
        long start = awaitingReplay ? cast.strikeAt : cast.struck ? cast.replays() ? cast.replayAt : cast.strikeAt
            : cast.locked ? cast.lockAt : cast.startedAt;
        long remainingSeconds = Math.max(0L, (end - tick + 19L) / 20L);
        cast.bar.setTitle(awaitingReplay ? replayTitle(cast, remainingSeconds) : cast.struck
            ? PlayerMsgResource.format(PlayerMsgId.P_8037.getId(), remainingSeconds)
            : cast.locked ? barTitle(cast, remainingSeconds) : lockTitle(cast, remainingSeconds));
        cast.bar.setProgress(Math.clamp((end - tick) / (double) Math.max(1L, end - start), 0D, 1D));
        syncBar(cast.bar, active);
    }

    /** 緑の反撃窓を残秒と倍率で示し、次の詠唱時には解放します。 */
    private static void updateRecoveryBar(Encounter state, List<Player> active, long tick) {
        if (state.recoveryUntil <= 0L) return;
        if (tick >= state.recoveryUntil) {
            if (!closeRecoveryBar(state)) throw new IllegalStateException("Recovery BossBar could not be released");
            return;
        }
        if (state.recoveryBar == null) {
            state.recoveryBar = Bukkit.createBossBar(recoveryTitle(state, (state.recoveryUntil - tick + 19L) / 20L),
                BarColor.GREEN, BarStyle.SOLID);
            state.recoveryBar.setVisible(true);
        }
        BossBar bar = state.recoveryBar;
        bar.setVisible(true);
        bar.setTitle(recoveryTitle(state, (state.recoveryUntil - tick + 19L) / 20L));
        bar.setProgress(Math.clamp((state.recoveryUntil - tick) / (double) state.recoveryDuration, 0D, 1D));
        syncBar(bar, active);
    }

    /** @return 反撃倍率と締切を共通メッセージから組み立てたタイトル */
    private static String recoveryTitle(Encounter state, long remainingSeconds) {
        return PlayerMsgResource.format((state.recoveryMultiplier > 1.25D
            ? PlayerMsgId.P_8074 : PlayerMsgId.P_8073).getId(), remainingSeconds);
    }

    /** @param bar 対象バー @param active 現在の参加者 */
    private static void syncBar(BossBar bar, List<Player> active) {
        Set<UUID> ids = new HashSet<>();
        for (Player player : active) {
            ids.add(player.getUniqueId());
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
        for (Player player : List.copyOf(bar.getPlayers()))
            if (!ids.contains(player.getUniqueId())) bar.removePlayer(player);
    }

    /** @param cast 表示中の針 @param remainingSeconds 衝突までの残秒 @return 現在の赤い危険線 */
    private static String barTitle(Cast cast, long remainingSeconds) {
        if (cast.rotates()) return cast.gear == null
            ? PlayerMsgResource.format(PlayerMsgId.P_8052.getId(), cast.impactBearing, remainingSeconds)
            : PlayerMsgResource.format(PlayerMsgId.P_8053.getId(), cast.impactBearing, remainingSeconds);
        return cast.gear == null
            ? PlayerMsgResource.format(PlayerMsgId.P_8032.getId(), cast.impactBearing, remainingSeconds)
            : PlayerMsgResource.format(PlayerMsgId.P_8044.getId(), cast.impactBearing, remainingSeconds);
    }

    /** @param cast 固定前の針 @param remainingSeconds 足跡固定までの残秒 @return 固定締切を明示したタイトル */
    private static String lockTitle(Cast cast, long remainingSeconds) {
        if (cast.rotates()) return cast.gear == null
            ? PlayerMsgResource.format(PlayerMsgId.P_8050.getId(), cast.targetName, remainingSeconds)
            : PlayerMsgResource.format(PlayerMsgId.P_8051.getId(), cast.targetName,
                gearName(cast.gear), cast.gear.bearing(), remainingSeconds);
        return cast.gear == null
            ? PlayerMsgResource.format(PlayerMsgId.P_8046.getId(), cast.targetName, remainingSeconds)
            : PlayerMsgResource.format(PlayerMsgId.P_8047.getId(), cast.targetName,
                gearName(cast.gear), cast.gear.bearing(), remainingSeconds);
    }

    /** @param cast 第三段階の波 @param remainingSeconds 逆再演までの残秒 @return 初期角へ戻る危険線 */
    private static String replayTitle(Cast cast, long remainingSeconds) {
        return cast.gear == null
            ? PlayerMsgResource.format(PlayerMsgId.P_8055.getId(), cast.bearing, remainingSeconds)
            : PlayerMsgResource.format(PlayerMsgId.P_8056.getId(), cast.bearing, remainingSeconds);
    }

    /** @param mark 選定した歯車印 @return リソース定義された内環または外環の表示名 */
    private static String gearName(HorologiumGearMarks.Mark mark) {
        return PlayerMsgResource.getMessage((mark.inner() ? PlayerMsgId.P_8042 : PlayerMsgId.P_8043).getId());
    }

    /** 床の12印のうち現在の対象だけを一時表示で強調します。表示失敗は判定へ影響させません。 */
    private static void showGear(Encounter state, Cast cast, List<Player> active) {
        if (cast.gear == null || state.gearDisplayUnavailable) return;
        try {
            float scale = 0.7F;
            BlockDisplay display = state.center.getWorld().spawn(
                cast.gear.at(state.center).add(0D, 0.28D, 0D), BlockDisplay.class, block -> {
                    block.setPersistent(false);
                    block.setInvulnerable(true);
                    block.setGravity(false);
                    block.setBrightness(new Display.Brightness(15, 15));
                    block.setBlock(Material.SEA_LANTERN.createBlockData());
                    block.setTransformation(new Transformation(new Vector3f(-scale / 2F, 0F, -scale / 2F),
                        new Quaternionf(), new Vector3f(scale), new Quaternionf()));
                });
            cast.gearDisplay = display.getUniqueId();
        } catch (RuntimeException failure) {
            state.gearDisplayUnavailable = true;
            Logger.log(LogId.E_6545, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    /** @return 同一会場の歩行面にいるか */
    static boolean inside(Location point, Location center) {
        if (point.getWorld() != center.getWorld() || Math.abs(point.getY() - center.getY()) > ARENA_HEIGHT) return false;
        double dx = point.getX() - center.getX();
        double dz = point.getZ() - center.getZ();
        return dx * dx + dz * dz <= ARENA_RADIUS * ARENA_RADIUS;
    }

    /** @return XZ上で針の長さと幅へ入った場合true */
    private static boolean handHits(Location point, Location center, double handDx, double handDz) {
        if (!inside(point, center)) return false;
        double dx = point.getX() - center.getX();
        double dz = point.getZ() - center.getZ();
        double along = dx * handDx + dz * handDz;
        double across = Math.abs(dx * handDz - dz * handDx);
        return along >= 0D && along <= ARENA_RADIUS && across <= HAND_WIDTH;
    }

    /** @return 固定位置と同じワールドかつ同じ床面か */
    private static boolean sameFloor(Location point, Location mark) {
        return point.getWorld() == mark.getWorld() && Math.abs(point.getY() - mark.getY()) <= 2D;
    }

    /** @param first 一方の位置 @param second 他方の位置 @return 平面二乗距離 */
    private static double horizontalDistanceSquared(Location first, Location second) {
        if (first.getWorld() != second.getWorld()) return Double.POSITIVE_INFINITY;
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    /** 表示と参加者参照を解放します。 */
    private static void closeCast(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        if (cast != null && cast.bar != null) {
            try {
                cast.bar.removeAll();
            } catch (RuntimeException failure) {
                Logger.log(LogId.E_6543, failure, state.boss.instanceId());
            }
        }
        if (cast != null) releaseGearDisplay(state, cast);
    }

    /** 反撃窓の参加者参照と表示を解放します。 */
    private static boolean closeRecoveryBar(Encounter state) {
        BossBar bar = state.recoveryBar;
        if (bar == null) return true;
        boolean removed = true;
        try {
            bar.removeAll();
        } catch (RuntimeException failure) {
            removed = false;
            logRecoveryBarCleanupFailure(state, failure);
        }
        boolean hidden = true;
        try {
            bar.setVisible(false);
        } catch (RuntimeException failure) {
            hidden = false;
            logRecoveryBarCleanupFailure(state, failure);
        }
        if (removed && hidden) state.recoveryBar = null;
        return removed && hidden;
    }

    /** 同じ個体の緑バー回収例外を繰り返し記録しません。 */
    private static void logRecoveryBarCleanupFailure(Encounter state, RuntimeException failure) {
        if (state.recoveryBarCleanupWarned) return;
        state.recoveryBarCleanupWarned = true;
        Logger.log(LogId.E_6543, failure, state.boss.instanceId());
    }

    /** 固定した印の一時強調を直ちに外し、後片付け時も同じ経路を使う。 */
    private static void releaseGearDisplay(Encounter state, Cast cast) {
        if (cast.gearDisplay == null) return;
        try {
            Entity display = Bukkit.getEntity(cast.gearDisplay);
            if (display != null) display.remove();
            cast.gearDisplay = null;
        } catch (RuntimeException failure) {
            if (!state.gearDisplayUnavailable) {
                state.gearDisplayUnavailable = true;
                Logger.log(LogId.E_6545, failure, state.boss.instanceId());
            }
        }
    }

    /**
     * @param bossId 終了した個体。別個体へ影響しない
     */
    void clear(UUID bossId) {
        Encounter state = encounters.remove(bossId);
        if (state == null) {
            Encounter pending = recoveryBarCleanupPending.get(bossId);
            if (pending != null && closeRecoveryBar(pending)) recoveryBarCleanupPending.remove(bossId);
            return;
        }
        closeCast(state);
        if (!closeRecoveryBar(state)) {
            recoveryBarCleanupPending.put(bossId, state);
            retiredAfterRecoveryBarFailure.add(bossId);
        }
        state.boss.scriptedAction(state.originalScripted);
    }

    /** @param active 現在残る個体。消失個体の表示を回収する */
    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(recoveryBarCleanupPending.keySet())) clear(id);
        for (UUID id : List.copyOf(encounters.keySet())) if (!active.contains(id)) clear(id);
        retiredAfterRecoveryBarFailure.removeIf(id -> !active.contains(id)
            && !recoveryBarCleanupPending.containsKey(id));
    }

    /** Plugin停止時に全個体を回収します。 */
    void clearAll() {
        for (UUID id : List.copyOf(encounters.keySet())) clear(id);
        for (UUID id : List.copyOf(recoveryBarCleanupPending.keySet())) clear(id);
    }

    /** @param active 対象参加者 @param id リソース文言 @param args 置換値 */
    private static void announce(List<Player> active, PlayerMsgId id, Object... args) {
        for (Player player : active) PlayerMessageService.getInstance().send(player, id, args);
    }

    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃元 @param player 確定参加者 @param ratio 魔法攻撃倍率 */
        void hit(MobInstance boss, Player player, double ratio);
    }

    private static final class Encounter {
        final MobInstance boss;
        final Location center;
        final boolean originalScripted;
        boolean introduced;
        boolean disabled;
        boolean gearEnabled;
        boolean particlesUnavailable;
        boolean gearDisplayUnavailable;
        boolean soundUnavailable;
        final Set<UUID> presentationWarned = new HashSet<>();
        int phase = 1;
        int actions;
        long nextAt;
        long recoveryUntil;
        long recoveryDuration;
        double recoveryMultiplier = 1.25D;
        BossBar recoveryBar;
        boolean recoveryBarCleanupWarned;
        Cast cast;

        /** @param boss 専用行動の所有個体 */
        Encounter(MobInstance boss) {
            this.boss = boss;
            center = boss.spawnLocation().clone();
            originalScripted = boss.scriptedAction();
        }
    }

    private static final class Cast {
        final int phase;
        final double initialDx;
        final double initialDz;
        final double impactDx;
        final double impactDz;
        final long startedAt;
        final long lockAt;
        final long strikeAt;
        final long replayAt;
        final long echoAt;
        final double echoRatio;
        final UUID targetId;
        final String targetName;
        final int bearing;
        final int impactBearing;
        final HorologiumGearMarks.Mark gear;
        final Set<UUID> eligible = new HashSet<>();
        final Set<UUID> handHit = new HashSet<>();
        final Set<UUID> protectionNotified = new HashSet<>();
        final Map<UUID, Location> footprints = new HashMap<>();
        boolean locked;
        boolean struck;
        boolean replayed;
        boolean targetOnGear;
        UUID gearDisplay;
        BossBar bar;

        /** @param phase 波開始時のHP段階 @param initialDx 初期針の単位X @param initialDz 初期針の単位Z
         * @param impactDx 衝突針の単位X @param impactDz 衝突針の単位Z @param startedAt 予兆開始時刻
         * @param lockAt 足跡を固定する時刻 @param strikeAt 針の衝突時刻
         * @param replayAt 逆再演時刻。第三段階以外は-1 @param echoAt 足跡反響時刻
         * @param echoRatio この波の反響攻撃倍率
         * @param targetId 標的ID @param targetName 固定した標的名 @param bearing 北から時計回りの角度
         * @param impactBearing 衝突時の針の角度 @param gear 誘導先の歯車印。生成時計盤以外ではnull */
        Cast(int phase, double initialDx, double initialDz, double impactDx, double impactDz,
             long startedAt, long lockAt, long strikeAt, long replayAt, long echoAt, double echoRatio,
             UUID targetId, String targetName, int bearing, int impactBearing, HorologiumGearMarks.Mark gear) {
            this.phase = phase;
            this.initialDx = initialDx;
            this.initialDz = initialDz;
            this.impactDx = impactDx;
            this.impactDz = impactDz;
            this.startedAt = startedAt;
            this.lockAt = lockAt;
            this.strikeAt = strikeAt;
            this.replayAt = replayAt;
            this.echoAt = echoAt;
            this.echoRatio = echoRatio;
            this.targetId = targetId;
            this.targetName = targetName;
            this.bearing = bearing;
            this.impactBearing = impactBearing;
            this.gear = gear;
        }

        /** @return この波が時計回り90度の回転針を使うか */
        boolean rotates() { return phase >= 2; }

        /** @return 第三段階の逆再演を含むか */
        boolean replays() { return phase == 3; }
    }
}
