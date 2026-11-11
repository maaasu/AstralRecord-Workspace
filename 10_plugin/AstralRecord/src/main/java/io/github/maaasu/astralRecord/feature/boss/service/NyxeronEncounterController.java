package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 天球心臓ニクセロンの専用行動を個体ごとに管理します。
 *
 * <p>通常行動は環影・光条・心拍・星印・蝕光の視線・星墜・天球逆行の7種で、一度に一つだけ予告します。
 * HP70%と40%では無敵の封印調律を行い、成功すると露出、失敗すると天球崩落を受けます。</p>
 */
final class NyxeronEncounterController {
    static final String BOSS_ID = "orrery_heart_nyxeron";
    private static final double[] FLOORS = {0.70D, 0.40D};
    private static final double EXPOSED_MULTIPLIER = 1.35D;
    private static final long EXPOSURE_TICKS = 200L;
    private static final double SPOKE_HALF = 1.4D;
    private static final double BRAND_RADIUS = 5.0D;
    private static final double SCAR_RADIUS = 3.5D;
    private static final long SCAR_TICKS = 300L;
    private static final int MAX_SCARS = 8;
    private static final double METEOR_RADIUS = 3.5D;
    private static final double HEART_INNER = 9.0D;
    private static final double HEART_OUTER = 15.0D;
    private static final long SEAL_REQUIRED_TICKS = 60L;
    private static final int[] EVEN_BANDS = {0, 2, 4};
    private static final int[] ODD_BANDS = {1, 3};
    private static final double[] EVEN_STATUES = {0, 135, 225, 315};
    private static final double[] ODD_STATUES = {45, 180, 270};
    private static final double[] ALL_STATUES = {0, 45, 135, 180, 225, 270, 315};
    private static final double[] BISECTORS = {22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5};
    private static final List<List<Action>> ROTATIONS = List.of(
        List.of(Action.BAND, Action.BRAND, Action.SPOKES, Action.HEART,
            Action.BAND, Action.SPOKES, Action.HEART, Action.BRAND),
        List.of(Action.GAZE, Action.BAND, Action.METEOR, Action.SPOKES, Action.HEART,
            Action.BRAND, Action.GAZE, Action.BAND, Action.METEOR, Action.SPOKES, Action.HEART),
        List.of(Action.COMBO, Action.GAZE, Action.METEOR, Action.HEART, Action.BAND,
            Action.BRAND, Action.COMBO, Action.SPOKES, Action.GAZE, Action.METEOR));

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hit;
    private final Consumer<MobInstance> abort;
    private final NyxeronPackDisplay display;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /**
     * @param mobs 移動停止に使うMobサービス
     * @param particles 共通粒子描画
     * @param participants 確定参加者の解決
     * @param hit 共通戦闘処理への魔法攻撃
     * @param abort 進行異常時に報酬なしで終了する処理
     * @param display 対応パックの本体・技表示
     */
    NyxeronEncounterController(MobService mobs, ParticleDisplayService particles,
                               Function<MobInstance, List<Player>> participants, HitHandler hit,
                               Consumer<MobInstance> abort, NyxeronPackDisplay display) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hit = hit;
        this.abort = abort;
        this.display = display;
    }

    /**
     * 未完了の封印調律を一撃で飛ばさないHP下限を返します。
     * @param boss 被弾個体 @return 対象外・全調律完了・停止中は0
     */
    double healthFloor(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id())) return 0.0D;
        Encounter state = encounters.get(boss.instanceId());
        int completed = state == null ? 0 : state.completed;
        if (state != null && state.disabled || completed >= FLOORS.length) return 0.0D;
        return boss.maxHealth() * FLOORS[completed];
    }

    /** @param boss 被弾個体 @return 調律中、またはHP境界に達して調律を待つ間はtrue */
    boolean protectedNow(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id()) || boss.currentHealth() <= 0.0D) return false;
        Encounter state = encounters.get(boss.instanceId());
        if (state != null && state.disabled) return false;
        if (state != null && state.ritual != null) return true;
        double floor = healthFloor(boss);
        return floor > 0.0D && boss.currentHealth() <= floor + 0.001D;
    }

    /**
     * @param boss 被弾個体 @param tick 単調時計
     * @return 露出中は1.35、調律中は0、それ以外は1
     */
    double incomingDamageMultiplier(MobInstance boss, long tick) {
        if (!BOSS_ID.equals(boss.template().id())) return 1.0D;
        if (protectedNow(boss)) return 0.0D;
        Encounter state = encounters.get(boss.instanceId());
        return state != null && !state.disabled && tick < state.exposureUntil ? EXPOSED_MULTIPLIER : 1.0D;
    }

    /**
     * 同期5tick周期で一個体の予告・発動・調律を進めます。
     * @param boss 生存個体 @param entity 戦闘実体 @param tick 単調時計
     * @param canAct 新しい行動を始められるか @param persistentEncounter 不在中も専用行動を保持するか
     */
    void tick(MobInstance boss, Entity entity, long tick, boolean canAct, boolean persistentEncounter) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (state.disabled) return;
        List<Player> active = List.of();
        try {
            active = participants.apply(boss);
            if (state.particlesUnavailable || state.soundUnavailable) warnPresentation(state, active);
            if (active.isEmpty()) {
                closeCast(state);
                closeExposureBar(state);
                if (state.ritual != null) syncBar(state.ritual.bar, active);
                boss.scriptedAction(persistentEncounter || state.originalScripted);
                state.nextAt = Math.max(state.nextAt, tick + 40L);
                return;
            }
            boss.scriptedAction(true);
            mobs.stopPathfinding(boss);
            if (!state.introduced) {
                announce(active, PlayerMsgId.P_8090);
                state.introduced = true;
                state.nextAt = tick + 80L;
            }
            updateScars(state, active, tick);
            if (encounters.get(boss.instanceId()) != state) return;
            if (state.ritual != null) {
                advanceRitual(state, active, tick);
                return;
            }
            if (protectedNow(boss)) {
                closeCast(state);
                startRitual(state, active, tick);
                return;
            }
            updateExposureBar(state, active, tick);
            if (state.cast != null) {
                if (!canAct && state.cast.next == 0) {
                    // 予告中に行動不能になった場合だけ取り消し、発動途中の連撃は最後まで進める。
                    closeCast(state);
                    state.nextAt = tick + 40L;
                } else {
                    advance(state, active, tick);
                }
                return;
            }
            if (tick < state.nextAt || !canAct) return;
            begin(state, active, tick);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_6544, failure, boss.instanceId());
            state.disabled = true;
            closeCast(state);
            closeRitual(state);
            closeExposureBar(state);
            state.boss.scriptedAction(state.originalScripted);
            try {
                abort.accept(boss);
            } catch (RuntimeException abortFailure) {
                Logger.log(LogId.E_6549, abortFailure, boss.instanceId());
                mobs.destroy(boss.instanceId());
            }
        } finally {
            // 調律の開始・終了と同じtickの結果を姿勢と無敵表示へ反映する。
            if (!state.disabled && encounters.get(boss.instanceId()) == state) {
                boolean immune = state.ritual != null || protectedNow(boss);
                display.body(boss.instanceId(), entity, tick, pose(state, tick, immune), immune);
            }
        }
    }

    /** @return 調律・露出・予告・通常の順で決まる本体の姿勢 */
    private static NyxeronPackDisplay.Pose pose(Encounter state, long tick, boolean immune) {
        if (immune) return NyxeronPackDisplay.Pose.GUARD;
        if (tick < state.exposureUntil) return NyxeronPackDisplay.Pose.EXPOSED;
        if (state.cast != null && state.cast.next == 0) return NyxeronPackDisplay.Pose.CAST;
        return NyxeronPackDisplay.Pose.IDLE;
    }

    // ------------------------------------------------------------------ actions

    /** 段階のローテーションから次の行動を一つ予告します。 */
    private void begin(Encounter state, List<Player> active, long tick) {
        List<Player> inside = insidePlayers(state, active);
        if (inside.isEmpty()) {
            state.nextAt = tick + 40L;
            return;
        }
        int phase = Math.min(state.completed, ROTATIONS.size() - 1);
        List<Action> rotation = ROTATIONS.get(phase);
        Action action = rotation.get(state.actions++ % rotation.size());
        Cast cast = switch (action) {
            case BAND -> band(state, phase, tick);
            case SPOKES -> spokes(state, phase, tick);
            case HEART -> heart(state, phase, tick);
            case BRAND -> brand(state, inside, tick);
            case GAZE -> gaze(state, phase, tick);
            case METEOR -> meteor(state, inside, phase, tick);
            case COMBO -> combo(state, tick);
        };
        for (Player player : inside) cast.eligible.add(player.getUniqueId());
        state.cast = cast;
        cast.bar = Bukkit.createBossBar(cast.title.apply(tick), cast.color, BarStyle.SEGMENTED_10);
        cast.bar.setVisible(true);
        cast.intro.accept(active);
        showStep(state, cast, tick);
        updateBar(cast, active, tick);
        drawSafely(state, cast, active, tick);
        playSoundSafely(state, active, cast.sound, 1.4F, cast.pitch);
    }

    /** 環影: 偶数帯（中心円・第二環・外周環）と奇数帯（第一環・第三環）を交互に塗ります。 */
    private Cast band(Encounter state, int phase, long tick) {
        boolean even = state.bandParity++ % 2 == 0;
        int strikes = phase + 1;
        long gap = phase == 1 ? 30L : 25L;
        long first = tick + 60L;
        Cast cast = new Cast(Action.BAND, tick, BarColor.RED, Sound.BLOCK_BEACON_ACTIVATE, 0.6F);
        List<int[]> patterns = new ArrayList<>();
        for (int i = 0; i < strikes; i++) patterns.add((even ^ (i % 2 == 1)) ? EVEN_BANDS : ODD_BANDS);
        for (int i = 0; i < strikes; i++) {
            int[] bands = patterns.get(i);
            cast.steps.add(new Step(first + gap * i, (s, c, players, now) -> strikeBands(s, c, players, bands, 0.55D),
                c -> bandShapes(state.center, bands)));
        }
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            int index = Math.min(c.next, patterns.size() - 1);
            drawBands(s.center, patterns.get(index), warn, safe);
            if (index + 1 < patterns.size()) drawBandAccent(s.center, patterns.get(index + 1), accent);
        };
        cast.title = now -> {
            int index = Math.min(cast.next, patterns.size() - 1);
            return PlayerMsgResource.format(PlayerMsgId.P_8096.getId(), bandNames(patterns.get(index)),
                seconds(cast.nextAt(), now));
        };
        if (strikes == 1) {
            cast.intro = players -> announce(players, PlayerMsgId.P_8094, bandNames(patterns.get(0)), seconds(first, tick));
        } else {
            cast.intro = players -> announce(players, PlayerMsgId.P_8095, bandNames(patterns.get(0)),
                bandNames(patterns.get(1)), seconds(first, tick), gap / 20.0D);
        }
        return cast;
    }

    /** 光条: 第1段階は守護像の半数、第2段階以降は全守護像の後に中間方位へ反射します。 */
    private Cast spokes(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.SPOKES, tick, BarColor.YELLOW, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2F);
        long first = tick + 50L;
        if (phase == 0) {
            double[] set = state.spokeParity++ % 2 == 0 ? EVEN_STATUES : ODD_STATUES;
            cast.steps.add(new Step(first, (s, c, players, now) -> strikeSpokes(s, c, players, set, 0.60D),
                c -> spokeShapes(state.center, set)));
            cast.drawer = (s, c, now, warn, safe, accent) -> drawSpokes(s.center, set, warn);
            cast.intro = players -> announce(players, PlayerMsgId.P_8102, directionNames(set), seconds(first, tick));
            cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8104.getId(), directionNames(set),
                seconds(cast.nextAt(), now));
        } else {
            long second = first + 30L;
            cast.steps.add(new Step(first, (s, c, players, now) -> strikeSpokes(s, c, players, ALL_STATUES, 0.55D),
                c -> spokeShapes(state.center, ALL_STATUES)));
            cast.steps.add(new Step(second, (s, c, players, now) -> strikeSpokes(s, c, players, BISECTORS, 0.55D),
                c -> spokeShapes(state.center, BISECTORS)));
            cast.drawer = (s, c, now, warn, safe, accent) -> {
                if (c.next == 0) {
                    drawSpokes(s.center, ALL_STATUES, warn);
                    drawSpokes(s.center, BISECTORS, accent);
                } else {
                    drawSpokes(s.center, BISECTORS, warn);
                }
            };
            cast.intro = players -> {
                announce(players, PlayerMsgId.P_8102, directionNames(ALL_STATUES), seconds(first, tick));
                announce(players, PlayerMsgId.P_8103, (second - first) / 20.0D);
            };
            cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8104.getId(),
                cast.next == 0 ? directionNames(ALL_STATUES) : PlayerMsgResource.getMessage(PlayerMsgId.P_8134.getId()),
                seconds(cast.nextAt(), now));
        }
        return cast;
    }

    /** 心拍: 一拍目は中心円、二拍目は外側の輪。第3段階は三拍目に中心円へ戻ります。 */
    private Cast heart(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.HEART, tick, BarColor.PINK, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8F);
        long first = tick + 40L;
        List<NyxeronPackDisplay.Shape> innerShapes = List.of(
            NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.DISC, state.center, HEART_INNER, 0.6D));
        List<NyxeronPackDisplay.Shape> outerShapes = List.of(
            NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.HEART, state.center, HEART_OUTER, 0.6D));
        cast.steps.add(new Step(first, (s, c, players, now) -> strikeRing(s, c, players, 0.0D, HEART_INNER, 0.55D, now),
            c -> innerShapes));
        cast.steps.add(new Step(first + 20L, (s, c, players, now) ->
            strikeRing(s, c, players, HEART_INNER, HEART_OUTER, 0.55D, now), c -> outerShapes));
        if (phase >= 2) {
            cast.steps.add(new Step(first + 40L, (s, c, players, now) ->
                strikeRing(s, c, players, 0.0D, HEART_INNER, 0.50D, now), c -> innerShapes));
        }
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            boolean inner = c.next != 1;
            circle(s.center, inner ? HEART_INNER : HEART_OUTER, 18, warn);
            if (!inner) circle(s.center, HEART_INNER, 12, warn);
            circle(s.center, inner ? HEART_OUTER : HEART_INNER, 12, accent);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8105, seconds(first, tick));
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8106.getId(), cast.next + 1,
            seconds(cast.nextAt(), now));
        return cast;
    }

    /** 星印: 参加者ごとの印が5秒後に炸裂し、炸裂地点に星痕を残します。 */
    private Cast brand(Encounter state, List<Player> inside, long tick) {
        Cast cast = new Cast(Action.BRAND, tick, BarColor.PURPLE, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7F);
        long detonate = tick + 100L;
        for (Player player : inside) cast.brands.put(player.getUniqueId(), player.getLocation().clone());
        cast.steps.add(new Step(detonate, this::detonateBrands));
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            for (Location mark : c.brands.values()) circle(mark, BRAND_RADIUS, 12, warn);
        };
        cast.intro = players -> {
            StringJoiner names = new StringJoiner("、");
            for (Player player : inside) names.add(player.getName());
            announce(players, PlayerMsgId.P_8107, names.toString(), seconds(detonate, tick));
            for (Player player : inside) PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8109);
        };
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8108.getId(), seconds(cast.nextAt(), now));
        return cast;
    }

    /** 蝕光の視線: 封印板の一つから視線が走り、遮蔽柱の影以外を射抜きます。第3段階は反対側から二度目。 */
    private Cast gaze(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.GAZE, tick, BarColor.BLUE, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7F);
        int firstSeal = ThreadLocalRandom.current().nextInt(NyxeronArena.SEALS.size());
        long first = tick + 100L;
        List<Integer> origins = new ArrayList<>(List.of(firstSeal));
        if (phase >= 2) origins.add((firstSeal + 4) % NyxeronArena.SEALS.size());
        for (int i = 0; i < origins.size(); i++) {
            int seal = origins.get(i);
            List<NyxeronPackDisplay.Shape> eye = List.of(NyxeronPackDisplay.Shape.facing(NyxeronPackDisplay.Style.GAZE,
                NyxeronArena.seal(state.center, seal), state.center, 4.0F, 4.0F));
            cast.steps.add(new Step(first + 50L * i, (s, c, players, now) -> strikeGaze(s, c, players, seal, 0.70D, now),
                c -> eye));
        }
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            int index = Math.min(c.next, origins.size() - 1);
            Location origin = NyxeronArena.seal(s.center, origins.get(index));
            circle(origin, 2.6D, 10, warn);
            drawShadows(s.center, origin, safe);
            if (index + 1 < origins.size()) circle(NyxeronArena.seal(s.center, origins.get(index + 1)), 2.6D, 10, accent);
        };
        cast.intro = players -> {
            announce(players, PlayerMsgId.P_8111, sealName(firstSeal), NyxeronArena.sealBearing(firstSeal),
                seconds(first, tick));
            if (origins.size() > 1) announce(players, PlayerMsgId.P_8113, sealName(origins.get(1)),
                NyxeronArena.sealBearing(origins.get(1)), seconds(first + 50L, tick));
        };
        cast.title = now -> {
            int index = Math.min(cast.next, origins.size() - 1);
            return PlayerMsgResource.format(PlayerMsgId.P_8112.getId(), NyxeronArena.sealBearing(origins.get(index)),
                seconds(cast.nextAt(), now));
        };
        return cast;
    }

    /** 星墜: 参加者の位置と空き地へ星が落ちます。第3段階は現在位置へ二波目。 */
    private Cast meteor(Encounter state, List<Player> inside, int phase, long tick) {
        Cast cast = new Cast(Action.METEOR, tick, BarColor.YELLOW, Sound.ENTITY_BLAZE_SHOOT, 0.6F);
        long first = tick + 60L;
        List<Location> targets = meteorTargets(state, inside);
        cast.meteors.addAll(targets);
        boolean twoWaves = phase >= 2;
        cast.steps.add(new Step(first, (s, c, players, now) -> {
            strikeMeteors(s, c, players, List.copyOf(c.meteors), 0.70D, now);
            if (ended(s, c)) return;
            c.meteors.clear();
            if (!twoWaves) return;
            // 二波目は一波目の着弾時点の位置を狙う。表示は次の段の開始時に切り替える。
            for (Player player : players) {
                if (c.eligible.contains(player.getUniqueId()) && NyxeronArena.inside(player.getLocation(), s.center))
                    c.meteors.add(jitter(player.getLocation(), 1.0D));
            }
        }, c -> meteorShapes(c.meteors)));
        if (twoWaves) {
            cast.steps.add(new Step(first + 60L, (s, c, players, now) ->
                strikeMeteors(s, c, players, List.copyOf(c.meteors), 0.60D, now), c -> meteorShapes(c.meteors)));
        }
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            for (Location target : c.meteors) circle(target, METEOR_RADIUS, 10, warn);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8114, targets.size(), seconds(first, tick));
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8115.getId(), seconds(cast.nextAt(), now));
        return cast;
    }

    /** 天球逆行: 環影と光条が同時に発動します。 */
    private Cast combo(Encounter state, long tick) {
        Cast cast = new Cast(Action.COMBO, tick, BarColor.RED, Sound.BLOCK_END_PORTAL_SPAWN, 1.4F);
        boolean even = state.bandParity++ % 2 == 0;
        int[] bands = even ? EVEN_BANDS : ODD_BANDS;
        double[] spokes = state.spokeParity++ % 2 == 0 ? EVEN_STATUES : ODD_STATUES;
        long strike = tick + 70L;
        cast.steps.add(new Step(strike, (s, c, players, now) -> {
            Set<UUID> struck = new HashSet<>();
            for (Player player : players) {
                if (!c.eligible.contains(player.getUniqueId())) continue;
                Location at = player.getLocation();
                if (!NyxeronArena.inside(at, s.center)) continue;
                double distance = NyxeronArena.horizontalDistance(at, s.center);
                boolean inBand = false;
                for (int band : bands) inBand |= NyxeronArena.inBand(distance, band);
                boolean onSpoke = false;
                for (double angle : spokes) onSpoke |= NyxeronArena.onSpoke(at, s.center, angle, SPOKE_HALF);
                if ((inBand || onSpoke) && struck.add(player.getUniqueId())) {
                    hit.hit(s.boss, player, 0.65D);
                    if (ended(s, c)) return;
                }
            }
            impact(s, c, now, bandImpactShapes(s.center, bands));
            impact(s, c, now, spokeShapes(s.center, spokes));
            playSoundSafely(s, players, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.6F);
        }, c -> {
            List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>(bandShapes(state.center, bands));
            shapes.addAll(spokeShapes(state.center, spokes));
            return shapes;
        }));
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            drawBands(s.center, bands, warn, safe);
            drawSpokes(s.center, spokes, warn);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8116, bandNames(bands), directionNames(spokes),
            seconds(strike, tick));
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8117.getId(), seconds(cast.nextAt(), now));
        return cast;
    }

    // ------------------------------------------------------------------ cast progress

    /** 予告中の参加資格を保ち、期限の来た段を順に発動します。 */
    private void advance(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        if (cast == null) return;
        Set<UUID> present = new HashSet<>();
        for (Player player : active) if (NyxeronArena.inside(player.getLocation(), state.center)) present.add(player.getUniqueId());
        cast.eligible.retainAll(present);
        // 場外へ出た・倒れた参加者の星印は、仲間を巻き込まないよう取り消す。
        cast.brands.keySet().retainAll(cast.eligible);
        for (Player player : active) {
            UUID id = player.getUniqueId();
            if (present.contains(id) && !cast.eligible.contains(id) && cast.protectionNotified.add(id))
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8124);
            if (cast.brands.containsKey(id)) cast.brands.put(id, player.getLocation().clone());
        }
        boolean fired = false;
        while (cast.next < cast.steps.size() && tick >= cast.steps.get(cast.next).at()) {
            Step step = cast.steps.get(cast.next++);
            fired = true;
            step.action().fire(state, cast, active, tick);
            if (ended(state, cast)) return;
        }
        if (cast.next >= cast.steps.size()) {
            closeCast(state);
            state.nextAt = tick + (state.completed == 0 ? 40L : state.completed == 1 ? 30L : 25L);
            return;
        }
        if (fired) showStep(state, cast, tick);
        updateBar(cast, active, tick);
        if (tick % 10L == 0L) drawSafely(state, cast, active, tick);
    }

    /**
     * 前の段の形状を外し、次に発動する段の危険範囲だけを対応パックへ表示します。
     * @param state 所有個体 @param cast 進行中の行動 @param tick 現在tick
     */
    private void showStep(Encounter state, Cast cast, long tick) {
        cast.cleanup.run();
        cast.cleanup = () -> { };
        if (cast.next >= cast.steps.size()) return;
        Step step = cast.steps.get(cast.next);
        List<NyxeronPackDisplay.Shape> shapes = step.shapes().apply(cast);
        if (shapes.isEmpty()) return;
        cast.cleanup = display.effects(state.boss.instanceId(), shapes, tick, Math.max(10L, step.at() - tick));
    }

    /** @return 命中で挑戦が終わったなど、この詠唱を続けられない場合true */
    private boolean ended(Encounter state, Cast cast) {
        return encounters.get(state.boss.instanceId()) != state || state.cast != cast || state.disabled;
    }

    private void strikeBands(Encounter state, Cast cast, List<Player> active, int[] bands, double ratio) {
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId())) continue;
            Location at = player.getLocation();
            if (!NyxeronArena.inside(at, state.center)) continue;
            double distance = NyxeronArena.horizontalDistance(at, state.center);
            for (int band : bands) {
                if (NyxeronArena.inBand(distance, band)) {
                    hit.hit(state.boss, player, ratio);
                    if (ended(state, cast)) return;
                    break;
                }
            }
        }
        impact(state, cast, cast.steps.get(cast.next - 1).at(), bandImpactShapes(state.center, bands));
        display.strike(state.boss.instanceId(), cast.steps.get(cast.next - 1).at());
        playSoundSafely(state, active, Sound.ENTITY_GENERIC_EXPLODE, 0.9F, 0.7F);
    }

    private void strikeSpokes(Encounter state, Cast cast, List<Player> active, double[] angles, double ratio) {
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId())) continue;
            Location at = player.getLocation();
            if (!NyxeronArena.inside(at, state.center)) continue;
            for (double angle : angles) {
                if (NyxeronArena.onSpoke(at, state.center, angle, SPOKE_HALF)) {
                    hit.hit(state.boss, player, ratio);
                    if (ended(state, cast)) return;
                    break;
                }
            }
        }
        long now = cast.steps.get(cast.next - 1).at();
        impact(state, cast, now, spokeShapes(state.center, angles));
        display.strike(state.boss.instanceId(), now);
        playSoundSafely(state, active, Sound.ENTITY_GUARDIAN_ATTACK, 1.0F, 1.4F);
    }

    private void strikeRing(Encounter state, Cast cast, List<Player> active, double inner, double outer,
                            double ratio, long tick) {
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId())) continue;
            Location at = player.getLocation();
            if (!NyxeronArena.inside(at, state.center)) continue;
            double distance = NyxeronArena.horizontalDistance(at, state.center);
            if (distance >= inner - 0.3D && distance <= outer + 0.3D) {
                hit.hit(state.boss, player, ratio);
                if (ended(state, cast)) return;
            }
        }
        NyxeronPackDisplay.Style style = inner <= 0.0D ? NyxeronPackDisplay.Style.DISC : NyxeronPackDisplay.Style.HEART;
        impact(state, cast, tick, List.of(NyxeronPackDisplay.Shape.circle(style, state.center, outer, 1.2D)));
        display.strike(state.boss.instanceId(), tick);
        playSoundSafely(state, active, Sound.ENTITY_WARDEN_HEARTBEAT, 1.6F, 0.6F);
    }

    /** 各星印を現在位置で炸裂させ、重なった炸裂は回数分命中させます。 */
    private void detonateBrands(Encounter state, Cast cast, List<Player> active, long tick) {
        List<Location> blasts = new ArrayList<>(cast.brands.values());
        for (Location blast : blasts) {
            for (Player player : active) {
                if (!cast.eligible.contains(player.getUniqueId())) continue;
                Location at = player.getLocation();
                if (at.getWorld() != blast.getWorld() || Math.abs(at.getY() - blast.getY()) > 3.0D) continue;
                if (at.distanceSquared(blast) <= BRAND_RADIUS * BRAND_RADIUS) {
                    hit.hit(state.boss, player, 0.45D);
                    if (ended(state, cast)) return;
                }
            }
        }
        List<NyxeronPackDisplay.Shape> bursts = new ArrayList<>();
        for (Location blast : blasts) {
            bursts.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.IMPACT, blast, BRAND_RADIUS, 2.0D));
            if (NyxeronArena.inside(blast, state.center)) addScar(state, blast, tick);
        }
        impact(state, cast, tick, bursts);
        display.strike(state.boss.instanceId(), tick);
        if (!blasts.isEmpty()) announce(active, PlayerMsgId.P_8110, SCAR_TICKS / 20L);
        playSoundSafely(state, active, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.6F, 0.6F);
    }

    private void strikeGaze(Encounter state, Cast cast, List<Player> active, int seal, double ratio, long tick) {
        Location origin = NyxeronArena.seal(state.center, seal);
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId())) continue;
            Location at = player.getLocation();
            if (!NyxeronArena.inside(at, state.center) || NyxeronArena.shadowed(origin, at, state.center)) continue;
            hit.hit(state.boss, player, ratio);
            if (ended(state, cast)) return;
        }
        impact(state, cast, tick, List.of(NyxeronPackDisplay.Shape.facing(NyxeronPackDisplay.Style.GAZE, origin,
            state.center, 6.0F, 6.0F)));
        display.strike(state.boss.instanceId(), tick);
        playSoundSafely(state, active, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.4F, 1.2F);
    }

    private void strikeMeteors(Encounter state, Cast cast, List<Player> active, List<Location> targets,
                               double ratio, long tick) {
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId())) continue;
            Location at = player.getLocation();
            for (Location target : targets) {
                if (at.getWorld() == target.getWorld() && Math.abs(at.getY() - target.getY()) <= 3.0D
                    && horizontalDistanceSquared(at, target) <= METEOR_RADIUS * METEOR_RADIUS) {
                    // 星の重なりでは一回だけ命中させる。
                    hit.hit(state.boss, player, ratio);
                    if (ended(state, cast)) return;
                    break;
                }
            }
        }
        List<NyxeronPackDisplay.Shape> bursts = new ArrayList<>();
        for (Location target : targets)
            bursts.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.IMPACT, target, METEOR_RADIUS, 2.4D));
        impact(state, cast, tick, bursts);
        playSoundSafely(state, active, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 0.9F);
    }

    /** @return 参加者ごとの星と、環の中の空き地2点 */
    private static List<Location> meteorTargets(Encounter state, List<Player> inside) {
        List<Location> targets = new ArrayList<>();
        for (Player player : inside) targets.add(jitter(player.getLocation(), 1.5D));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 2 && targets.size() < 8; i++) {
            double angle = random.nextDouble(Math.PI * 2.0D);
            double radius = 10.0D + random.nextDouble(20.0D);
            targets.add(state.center.clone().add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius));
        }
        return targets;
    }

    /** @param base 基準位置 @param spread 最大ずれ @return 床面へ揃えた着弾点 */
    private static Location jitter(Location base, double spread) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location result = base.clone().add(random.nextDouble(-spread, spread), 0.0D, random.nextDouble(-spread, spread));
        result.setY(Math.floor(base.getY()) + 0.0D);
        return result;
    }

    // ------------------------------------------------------------------ scars

    private void addScar(Encounter state, Location at, long tick) {
        Location floor = at.clone();
        floor.setY(state.center.getY());
        if (state.scars.size() >= MAX_SCARS) {
            Scar oldest = state.scars.removeFirst();
            oldest.cleanup.run();
        }
        Runnable cleanup = display.effects(state.boss.instanceId(), List.of(
            NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.SCAR, floor, SCAR_RADIUS, 0.4D)), tick, SCAR_TICKS);
        state.scars.add(new Scar(floor, tick + SCAR_TICKS, cleanup));
    }

    /** 期限切れの星痕を消し、1秒ごとに上に立つ参加者へ継続ダメージを与えます。 */
    private void updateScars(Encounter state, List<Player> active, long tick) {
        Iterator<Scar> iterator = state.scars.iterator();
        while (iterator.hasNext()) {
            Scar scar = iterator.next();
            if (tick >= scar.until()) {
                iterator.remove();
                scar.cleanup().run();
            }
        }
        if (state.scars.isEmpty()) return;
        if (tick % 10L == 0L && !state.particlesUnavailable) {
            List<Location> points = new ArrayList<>();
            for (Scar scar : state.scars) circle(scar.at(), SCAR_RADIUS, 10, points);
            spawn(state, active, points, SharedParticleDefinitions.NYXERON_SCAR);
        }
        if (tick % 20L != 0L) return;
        for (Player player : active) {
            Location at = player.getLocation();
            for (Scar scar : List.copyOf(state.scars)) {
                if (at.getWorld() == scar.at().getWorld() && Math.abs(at.getY() - scar.at().getY()) <= 3.0D
                    && horizontalDistanceSquared(at, scar.at()) <= SCAR_RADIUS * SCAR_RADIUS) {
                    hit.hit(state.boss, player, 0.12D);
                    if (encounters.get(state.boss.instanceId()) != state) return;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ ritual

    /** HP境界で無敵の封印調律を始め、光る封印板を参加人数に応じて選びます。 */
    private void startRitual(Encounter state, List<Player> active, long tick) {
        int number = state.completed + 1;
        int people = Math.max(1, insidePlayers(state, active).size());
        int count = Math.min(number == 1 ? 5 : 6, Math.max(2, people + number));
        int start = ThreadLocalRandom.current().nextInt(NyxeronArena.SEALS.size());
        // 2枚は90度離し、3枚以上は隣り合う封印板を選ぶ。ソロでも30秒で回れる距離に収める。
        int step = count <= 2 ? 2 : 1;
        List<Integer> seals = new ArrayList<>();
        for (int i = 0; i < count; i++) seals.add(Math.floorMod(start + i * step, NyxeronArena.SEALS.size()));
        long duration = 600L;
        Ritual ritual = new Ritual(number, tick, tick + duration, seals);
        ritual.nextHazardAt = tick + 100L;
        state.ritual = ritual;
        closeExposureBar(state);
        state.exposureUntil = 0L;
        ritual.bar = Bukkit.createBossBar(ritualTitle(ritual, tick), BarColor.PURPLE, BarStyle.SOLID);
        ritual.bar.setVisible(true);
        syncBar(ritual.bar, active);
        for (int i = 0; i < seals.size(); i++) {
            Location seal = NyxeronArena.seal(state.center, seals.get(i));
            ritual.sealEffects.add(display.effects(state.boss.instanceId(), List.of(
                NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.SEAL, seal, NyxeronArena.SEAL_RADIUS, 6.0D)),
                tick, duration));
        }
        announce(active, PlayerMsgId.P_8118, number, seals.size(), sealNames(seals), duration / 20L);
        playSoundSafely(state, active, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.6F, 0.5F);
        display.strike(state.boss.instanceId(), tick);
    }

    /** 調律の進捗・圧力・成否を処理します。 */
    private void advanceRitual(Encounter state, List<Player> active, long tick) {
        Ritual ritual = state.ritual;
        List<Player> inside = insidePlayers(state, active);
        for (int i = 0; i < ritual.seals.size(); i++) {
            if (ritual.progress[i] >= SEAL_REQUIRED_TICKS) continue;
            Location seal = NyxeronArena.seal(state.center, ritual.seals.get(i));
            boolean occupied = false;
            for (Player player : inside) occupied |= NyxeronArena.onSeal(player.getLocation(), seal);
            if (!occupied) continue;
            ritual.progress[i] += 5L;
            if (ritual.progress[i] >= SEAL_REQUIRED_TICKS) {
                ritual.attuned++;
                ritual.sealEffects.get(i).run();
                ritual.sealEffects.add(display.effects(state.boss.instanceId(), List.of(NyxeronPackDisplay.Shape.circle(
                    NyxeronPackDisplay.Style.SEAL_DONE, seal, NyxeronArena.SEAL_RADIUS, 3.0D)), tick,
                    Math.max(10L, ritual.endsAt - tick)));
                announce(active, PlayerMsgId.P_8120, sealName(ritual.seals.get(i)), ritual.attuned, ritual.seals.size());
                playSoundSafely(state, active, Sound.BLOCK_BEACON_POWER_SELECT, 1.2F, 1.4F);
            }
        }
        advanceRitualHazards(state, ritual, inside, tick);
        if (encounters.get(state.boss.instanceId()) != state || state.ritual != ritual) return;
        if (ritual.attuned >= ritual.seals.size()) {
            finishRitual(state, active, tick, true);
            return;
        }
        if (tick >= ritual.endsAt) {
            finishRitual(state, active, tick, false);
            return;
        }
        ritual.bar.setTitle(ritualTitle(ritual, tick));
        ritual.bar.setProgress(Math.clamp((ritual.endsAt - tick) / (double) (ritual.endsAt - ritual.startedAt), 0.0D, 1.0D));
        syncBar(ritual.bar, active);
        if (tick % 10L == 0L && !state.particlesUnavailable) {
            List<Location> gold = new ArrayList<>();
            List<Location> done = new ArrayList<>();
            for (int i = 0; i < ritual.seals.size(); i++) {
                Location seal = NyxeronArena.seal(state.center, ritual.seals.get(i));
                if (ritual.progress[i] >= SEAL_REQUIRED_TICKS) circle(seal, NyxeronArena.SEAL_RADIUS, 8, done);
                else {
                    circle(seal, NyxeronArena.SEAL_RADIUS, 10, gold);
                    for (int h = 1; h <= 4; h++) gold.add(seal.clone().add(0.0D, h * 1.2D, 0.0D));
                }
            }
            List<Location> warn = new ArrayList<>();
            for (Hazard hazard : ritual.hazards) circle(hazard.at(), METEOR_RADIUS, 10, warn);
            spawn(state, active, gold, SharedParticleDefinitions.NYXERON_SEAL);
            spawn(state, active, done, SharedParticleDefinitions.BOSS_SAFE_AREA);
            spawn(state, active, warn, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        }
    }

    /** 調律中は一定間隔で星が落ち、第二儀では参加者全員を狙います。 */
    private void advanceRitualHazards(Encounter state, Ritual ritual, List<Player> inside, long tick) {
        Iterator<Hazard> iterator = ritual.hazards.iterator();
        List<Location> fired = new ArrayList<>();
        while (iterator.hasNext()) {
            Hazard hazard = iterator.next();
            if (tick < hazard.strikeAt()) continue;
            iterator.remove();
            fired.add(hazard.at());
        }
        if (!fired.isEmpty()) {
            for (Player player : inside) {
                for (Location target : fired) {
                    if (Math.abs(player.getLocation().getY() - target.getY()) <= 3.0D
                        && horizontalDistanceSquared(player.getLocation(), target) <= METEOR_RADIUS * METEOR_RADIUS) {
                        hit.hit(state.boss, player, 0.50D);
                        if (encounters.get(state.boss.instanceId()) != state || state.ritual != ritual) return;
                        break;
                    }
                }
            }
            List<NyxeronPackDisplay.Shape> bursts = new ArrayList<>();
            for (Location target : fired)
                bursts.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.IMPACT, target, METEOR_RADIUS, 2.4D));
            display.effects(state.boss.instanceId(), bursts, tick, 12L);
            playSoundSafely(state, inside, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 1.0F);
        }
        if (tick < ritual.nextHazardAt || inside.isEmpty() || tick + 60L >= ritual.endsAt) return;
        ritual.nextHazardAt = tick + (ritual.number == 1 ? 140L : 110L);
        List<Location> targets = new ArrayList<>();
        if (ritual.number == 1) {
            Player target = inside.get(ThreadLocalRandom.current().nextInt(inside.size()));
            targets.add(jitter(target.getLocation(), 1.0D));
        } else {
            for (Player player : inside) targets.add(jitter(player.getLocation(), 1.0D));
        }
        for (Location target : targets) ritual.hazards.add(new Hazard(target, tick + 60L));
        display.effects(state.boss.instanceId(), meteorShapes(targets), tick, 60L);
        announce(inside, PlayerMsgId.P_8114, targets.size(), 3);
    }

    /** 成功では露出、失敗では天球崩落を発動し、次の段階へ進めます。 */
    private void finishRitual(Encounter state, List<Player> active, long tick, boolean success) {
        Ritual ritual = state.ritual;
        closeRitual(state);
        state.completed++;
        if (success) {
            state.exposureUntil = tick + EXPOSURE_TICKS;
            state.nextAt = state.exposureUntil;
            announce(active, PlayerMsgId.P_8121, EXPOSURE_TICKS / 20L);
            playSoundSafely(state, active, Sound.BLOCK_BEACON_DEACTIVATE, 1.6F, 0.8F);
            updateExposureBar(state, active, tick);
        } else {
            announce(active, PlayerMsgId.P_8122);
            for (Player player : insidePlayers(state, active)) {
                hit.hit(state.boss, player, 1.20D);
                if (encounters.get(state.boss.instanceId()) != state) return;
            }
            display.effects(state.boss.instanceId(), List.of(NyxeronPackDisplay.Shape.circle(
                NyxeronPackDisplay.Style.IMPACT, state.center, NyxeronArena.FLOOR_RADIUS, 3.0D)), tick, 14L);
            playSoundSafely(state, active, Sound.ENTITY_GENERIC_EXPLODE, 2.0F, 0.5F);
            state.nextAt = tick + 60L;
        }
        if (ritual != null) announce(active, state.completed == 1 ? PlayerMsgId.P_8092 : PlayerMsgId.P_8093);
    }

    private static String ritualTitle(Ritual ritual, long tick) {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < ritual.seals.size(); i++)
            if (ritual.progress[i] < SEAL_REQUIRED_TICKS) remaining.add(ritual.seals.get(i));
        return PlayerMsgResource.format(PlayerMsgId.P_8119.getId(), ritual.attuned, ritual.seals.size(),
            seconds(ritual.endsAt, tick), sealNames(remaining));
    }

    // ------------------------------------------------------------------ shapes & drawing

    private static List<NyxeronPackDisplay.Shape> bandShapes(Location center, int[] bands) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (int band : bands) {
            double outer = NyxeronArena.band(band)[1];
            shapes.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.band(band), center, outer, 0.5D));
        }
        return shapes;
    }

    private static List<NyxeronPackDisplay.Shape> bandImpactShapes(Location center, int[] bands) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (int band : bands) {
            double outer = NyxeronArena.band(band)[1];
            shapes.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.band(band), center, outer, 1.6D));
        }
        return shapes;
    }

    private static List<NyxeronPackDisplay.Shape> spokeShapes(Location center, double[] angles) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (double angle : angles) {
            double radians = Math.toRadians(angle);
            Location end = center.clone().add(Math.cos(radians) * NyxeronArena.FLOOR_RADIUS, 0.0D,
                Math.sin(radians) * NyxeronArena.FLOOR_RADIUS);
            shapes.add(NyxeronPackDisplay.Shape.segment(NyxeronPackDisplay.Style.SPOKE, center, end,
                (float) (SPOKE_HALF * 2.0D), 1.0F));
        }
        return shapes;
    }

    private static List<NyxeronPackDisplay.Shape> meteorShapes(List<Location> targets) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (Location target : targets)
            shapes.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.METEOR, target, METEOR_RADIUS, 6.0D));
        return shapes;
    }

    /** 発動の瞬間だけ短く表示します。 */
    private void impact(Encounter state, Cast cast, long tick, List<NyxeronPackDisplay.Shape> shapes) {
        if (shapes.isEmpty()) return;
        display.effects(state.boss.instanceId(), shapes, tick, 12L);
    }

    private static void drawBands(Location center, int[] bands, List<Location> warn, List<Location> safe) {
        Set<Double> edges = new HashSet<>();
        Set<Integer> danger = new HashSet<>();
        for (int band : bands) {
            danger.add(band);
            double[] range = NyxeronArena.band(band);
            if (range[0] > 0.0D) edges.add(range[0]);
            edges.add(range[1]);
            double middle = (range[0] + range[1]) / 2.0D;
            int marks = band == 0 ? 4 : 12;
            for (int i = 0; i < marks; i++) {
                double angle = (i + 0.5D) * Math.PI * 2.0D / marks;
                warn.add(center.clone().add(Math.cos(angle) * middle, 0.25D, Math.sin(angle) * middle));
            }
        }
        for (double edge : edges) circle(center, edge, edge <= 8.0D ? 16 : 36, warn);
        for (int band = 0; band <= 4; band++) {
            if (danger.contains(band)) continue;
            double[] range = NyxeronArena.band(band);
            double middle = band == 0 ? 3.5D : (range[0] + range[1]) / 2.0D;
            circle(center, middle, band == 0 ? 6 : 16, safe);
        }
    }

    private static void drawBandAccent(Location center, int[] bands, List<Location> accent) {
        for (int band : bands) {
            double[] range = NyxeronArena.band(band);
            circle(center, (range[0] + range[1]) / 2.0D, 12, accent);
        }
    }

    private static void drawSpokes(Location center, double[] angles, List<Location> out) {
        for (double angle : angles) {
            double radians = Math.toRadians(angle);
            double ux = Math.cos(radians);
            double uz = Math.sin(radians);
            for (double distance = 3.0D; distance <= NyxeronArena.FLOOR_RADIUS - 0.5D; distance += 2.5D) {
                for (double side : new double[] {-SPOKE_HALF, SPOKE_HALF}) {
                    out.add(center.clone().add(ux * distance - uz * side, 0.2D, uz * distance + ux * side));
                }
            }
        }
    }

    /** 視線の起点から見た各柱の影の縁を、柱の裏から壁まで描きます。 */
    private static void drawShadows(Location center, Location origin, List<Location> safe) {
        double ox = origin.getX() - center.getX();
        double oz = origin.getZ() - center.getZ();
        for (double[] pylon : NyxeronArena.PYLONS) {
            double baseAngle = Math.atan2(pylon[1] - oz, pylon[0] - ox);
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double cx = pylon[0] + (corner % 2 == 0 ? -1 : 1) * NyxeronArena.PYLON_HALF;
                double cz = pylon[1] + (corner < 2 ? -1 : 1) * NyxeronArena.PYLON_HALF;
                double angle = Math.atan2(cz - oz, cx - ox) - baseAngle;
                angle = Math.atan2(Math.sin(angle), Math.cos(angle));
                min = Math.min(min, angle);
                max = Math.max(max, angle);
            }
            double start = Math.hypot(pylon[0] - ox, pylon[1] - oz) + NyxeronArena.PYLON_HALF + 0.5D;
            for (double edge : new double[] {baseAngle + min * 0.8D, baseAngle, baseAngle + max * 0.8D}) {
                for (double distance = start; distance < 80.0D; distance += 2.0D) {
                    double px = ox + Math.cos(edge) * distance;
                    double pz = oz + Math.sin(edge) * distance;
                    if (Math.hypot(px, pz) > NyxeronArena.FLOOR_RADIUS - 0.5D) break;
                    safe.add(center.clone().add(px, 0.3D, pz));
                }
            }
        }
    }

    private static void circle(Location center, double radius, int points, List<Location> out) {
        for (int i = 0; i < points; i++) {
            double angle = i * Math.PI * 2.0D / points;
            out.add(center.clone().add(Math.cos(angle) * radius, 0.2D, Math.sin(angle) * radius));
        }
    }

    /** 粒子表示の失敗は戦闘判定から切り離します。 */
    private void drawSafely(Encounter state, Cast cast, List<Player> active, long tick) {
        if (state.particlesUnavailable || cast.drawer == null) return;
        List<Location> warn = new ArrayList<>();
        List<Location> safe = new ArrayList<>();
        List<Location> accent = new ArrayList<>();
        try {
            cast.drawer.draw(state, cast, tick, warn, safe, accent);
        } catch (RuntimeException failure) {
            state.particlesUnavailable = true;
            Logger.log(LogId.E_6547, failure, state.boss.instanceId());
            warnPresentation(state, active);
            return;
        }
        spawn(state, active, warn, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        spawn(state, active, safe, SharedParticleDefinitions.BOSS_SAFE_AREA);
        spawn(state, active, accent, SharedParticleDefinitions.NYXERON_ACCENT);
    }

    private void spawn(Encounter state, List<Player> active, List<Location> points, SharedParticleDefinition definition) {
        if (points.isEmpty() || state.particlesUnavailable) return;
        try {
            particles.spawnForNearbyViewers(points, definition, true);
        } catch (RuntimeException failure) {
            state.particlesUnavailable = true;
            Logger.log(LogId.E_6547, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    private static void playSoundSafely(Encounter state, List<Player> active, Sound sound, float volume, float pitch) {
        if (state.soundUnavailable) return;
        try {
            state.center.getWorld().playSound(state.center, sound, volume, pitch);
        } catch (RuntimeException failure) {
            state.soundUnavailable = true;
            Logger.log(LogId.E_6547, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    private static void warnPresentation(Encounter state, List<Player> active) {
        for (Player player : active) {
            if (!state.presentationWarned.add(player.getUniqueId())) continue;
            try {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_8091);
            } catch (RuntimeException ignored) {
                // 任意演出の代替通知で戦闘を止めない。
            }
        }
    }

    // ------------------------------------------------------------------ bars

    private static void updateBar(Cast cast, List<Player> active, long tick) {
        if (cast.bar == null) return;
        long end = cast.nextAt();
        long start = cast.next == 0 ? cast.startedAt : cast.steps.get(cast.next - 1).at();
        cast.bar.setTitle(cast.title.apply(tick));
        cast.bar.setProgress(Math.clamp((end - tick) / (double) Math.max(1L, end - start), 0.0D, 1.0D));
        syncBar(cast.bar, active);
    }

    private static void updateExposureBar(Encounter state, List<Player> active, long tick) {
        if (state.exposureUntil <= 0L) return;
        if (tick >= state.exposureUntil) {
            closeExposureBar(state);
            return;
        }
        if (state.exposureBar == null) {
            state.exposureBar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
            state.exposureBar.setVisible(true);
        }
        state.exposureBar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_8123.getId(), seconds(state.exposureUntil, tick)));
        state.exposureBar.setProgress(Math.clamp((state.exposureUntil - tick) / (double) EXPOSURE_TICKS, 0.0D, 1.0D));
        syncBar(state.exposureBar, active);
    }

    private static void syncBar(BossBar bar, List<Player> active) {
        if (bar == null) return;
        Set<UUID> ids = new HashSet<>();
        for (Player player : active) {
            ids.add(player.getUniqueId());
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
        for (Player player : List.copyOf(bar.getPlayers()))
            if (!ids.contains(player.getUniqueId())) bar.removePlayer(player);
    }

    private static void removeBar(Encounter state, BossBar bar) {
        if (bar == null) return;
        try {
            bar.removeAll();
            bar.setVisible(false);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_6547, failure, state.boss.instanceId());
        }
    }

    private static void closeCast(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        if (cast == null) return;
        removeBar(state, cast.bar);
        cast.cleanup.run();
    }

    private static void closeRitual(Encounter state) {
        Ritual ritual = state.ritual;
        state.ritual = null;
        if (ritual == null) return;
        removeBar(state, ritual.bar);
        for (Runnable cleanup : ritual.sealEffects) cleanup.run();
    }

    private static void closeExposureBar(Encounter state) {
        BossBar bar = state.exposureBar;
        state.exposureBar = null;
        removeBar(state, bar);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * 戦闘実体が一時的に無効な間、予告を取り消して調律の期限を止めます。
     * 再開時に期限切れの連撃がまとめて発動したり、不在中に調律が失敗したりしないようにします。
     * @param bossId 生存中の個体 @param tick 単調時計
     */
    void suspend(UUID bossId, long tick) {
        Encounter state = encounters.get(bossId);
        if (state == null || state.disabled) return;
        closeCast(state);
        closeExposureBar(state);
        state.nextAt = Math.max(state.nextAt, tick + 40L);
        Ritual ritual = state.ritual;
        if (ritual == null) return;
        ritual.endsAt += 5L;
        ritual.nextHazardAt += 5L;
        List<Hazard> shifted = new ArrayList<>();
        for (Hazard hazard : ritual.hazards) shifted.add(new Hazard(hazard.at(), hazard.strikeAt() + 5L));
        ritual.hazards.clear();
        ritual.hazards.addAll(shifted);
        syncBar(ritual.bar, List.of());
    }

    /** @param bossId 終了した個体。表示とBossBarを回収します */
    void clear(UUID bossId) {
        Encounter state = encounters.remove(bossId);
        if (state == null) return;
        closeCast(state);
        closeRitual(state);
        closeExposureBar(state);
        for (Scar scar : state.scars) scar.cleanup().run();
        state.scars.clear();
        state.boss.scriptedAction(state.originalScripted);
        display.clear(bossId);
    }

    /** @param active 現在残る個体 */
    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!active.contains(id)) clear(id);
        display.retain(active);
    }

    /** Plugin停止時に全個体を回収します。 */
    void clearAll() {
        for (UUID id : List.copyOf(encounters.keySet())) clear(id);
    }

    /** @param bossId 被弾個体 @param tick 共通時計 */
    void hurt(UUID bossId, long tick) {
        display.hurt(bossId, tick);
    }

    // ------------------------------------------------------------------ helpers

    private static List<Player> insidePlayers(Encounter state, List<Player> active) {
        List<Player> inside = new ArrayList<>();
        for (Player player : active) if (NyxeronArena.inside(player.getLocation(), state.center)) inside.add(player);
        return inside;
    }

    private static double horizontalDistanceSquared(Location first, Location second) {
        if (first.getWorld() != second.getWorld()) return Double.POSITIVE_INFINITY;
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static long seconds(long end, long tick) {
        return Math.max(0L, (end - tick + 19L) / 20L);
    }

    private static String bandNames(int[] bands) {
        StringJoiner joiner = new StringJoiner("・");
        for (int band : bands) joiner.add(PlayerMsgResource.getMessage(PlayerMsgId.valueOf("P_" + (8097 + band)).getId()));
        return joiner.toString();
    }

    private static String sealName(int index) {
        return PlayerMsgResource.getMessage(PlayerMsgId.valueOf("P_" + (8126 + Math.floorMod(index, 8))).getId());
    }

    private static String sealNames(List<Integer> seals) {
        StringJoiner joiner = new StringJoiner("・");
        for (int seal : seals) joiner.add(sealName(seal));
        return joiner.toString();
    }

    private static String directionNames(double[] angles) {
        StringJoiner joiner = new StringJoiner("・");
        for (double angle : angles) joiner.add(sealName((int) Math.round(angle / 45.0D)));
        return joiner.toString();
    }

    private static void announce(List<Player> active, PlayerMsgId id, Object... args) {
        for (Player player : active) PlayerMessageService.getInstance().send(player, id, args);
    }

    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃元 @param player 確定参加者 @param ratio 魔法攻撃倍率 */
        void hit(MobInstance boss, Player player, double ratio);
    }

    private enum Action { BAND, SPOKES, HEART, BRAND, GAZE, METEOR, COMBO }

    @FunctionalInterface private interface StepAction {
        void fire(Encounter state, Cast cast, List<Player> active, long tick);
    }

    @FunctionalInterface private interface Drawer {
        void draw(Encounter state, Cast cast, long tick, List<Location> warn, List<Location> safe, List<Location> accent);
    }

    /** @param at 発動tick @param action 発動処理 @param shapes この段の予告中に対応パックへ出す形状 */
    private record Step(long at, StepAction action, Function<Cast, List<NyxeronPackDisplay.Shape>> shapes) {
        Step(long at, StepAction action) {
            this(at, action, ignored -> List.of());
        }
    }

    private record Scar(Location at, long until, Runnable cleanup) {}

    private record Hazard(Location at, long strikeAt) {}

    private static final class Encounter {
        final MobInstance boss;
        final Location center;
        final boolean originalScripted;
        final Set<UUID> presentationWarned = new HashSet<>();
        final List<Scar> scars = new ArrayList<>();
        boolean introduced;
        boolean disabled;
        boolean particlesUnavailable;
        boolean soundUnavailable;
        int completed;
        int actions;
        int bandParity;
        int spokeParity;
        long nextAt;
        long exposureUntil;
        BossBar exposureBar;
        Cast cast;
        Ritual ritual;

        Encounter(MobInstance boss) {
            this.boss = boss;
            this.center = boss.spawnLocation().clone();
            this.originalScripted = boss.scriptedAction();
        }
    }

    private static final class Cast {
        final Action action;
        final long startedAt;
        final BarColor color;
        final Sound sound;
        final float pitch;
        final List<Step> steps = new ArrayList<>();
        final Set<UUID> eligible = new HashSet<>();
        final Set<UUID> protectionNotified = new HashSet<>();
        final Map<UUID, Location> brands = new HashMap<>();
        final List<Location> meteors = new ArrayList<>();
        int next;
        BossBar bar;
        Drawer drawer;
        Function<Long, String> title = ignored -> "";
        Consumer<List<Player>> intro = ignored -> { };
        Runnable cleanup = () -> { };

        Cast(Action action, long startedAt, BarColor color, Sound sound, float pitch) {
            this.action = action;
            this.startedAt = startedAt;
            this.color = color;
            this.sound = sound;
            this.pitch = pitch;
        }

        /** @return 次に発動する段の時刻 */
        long nextAt() {
            return steps.get(Math.min(next, steps.size() - 1)).at();
        }
    }

    private static final class Ritual {
        final int number;
        final long startedAt;
        long endsAt;
        final List<Integer> seals;
        final long[] progress;
        final List<Runnable> sealEffects = new ArrayList<>();
        final List<Hazard> hazards = new ArrayList<>();
        int attuned;
        long nextHazardAt;
        BossBar bar;

        Ritual(int number, long startedAt, long endsAt, List<Integer> seals) {
            this.number = number;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.seals = List.copyOf(seals);
            this.progress = new long[seals.size()];
        }
    }
}
