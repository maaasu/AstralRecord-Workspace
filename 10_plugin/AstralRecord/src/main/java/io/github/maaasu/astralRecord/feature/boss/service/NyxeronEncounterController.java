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
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.time.Duration;
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
 * <p>通常行動は環影・光条・心拍・星印・蝕光の視線・星墜・天球逆行に、弾幕（星環弾・星渦）、跳躍としゃがみで避ける
 * 衝撃環と光刃旋回、星片を集めて止める星雨と超新星を加えた12種で、一度に一つだけ予告します。
 * HP70%と40%では無敵の封印調律を行い、成功すると露出、失敗すると天球崩落を受けます。</p>
 */
final class NyxeronEncounterController {
    static final String BOSS_ID = "orrery_heart_nyxeron";
    private static final double[] FLOORS = {0.70D, 0.40D};
    private static final double EXPOSED_MULTIPLIER = 1.35D;
    private static final long EXPOSURE_TICKS = 200L;
    private static final double STAGGER_MULTIPLIER = 1.6D;
    private static final long STAGGER_TICKS = 160L;
    private static final double SPOKE_HALF = 1.4D;
    private static final double BRAND_RADIUS = 5.0D;
    private static final double SCAR_RADIUS = 3.5D;
    private static final long SCAR_TICKS = 300L;
    private static final int MAX_SCARS = 8;
    private static final double METEOR_RADIUS = 3.5D;
    private static final double HEART_INNER = 9.0D;
    private static final double HEART_OUTER = 15.0D;
    private static final long SEAL_REQUIRED_TICKS = 60L;
    private static final double RAIN_RADIUS = 2.4D;
    private static final int[] EVEN_BANDS = {0, 2, 4};
    private static final int[] ODD_BANDS = {1, 3};
    private static final double[] EVEN_STATUES = {0, 135, 225, 315};
    private static final double[] ODD_STATUES = {45, 180, 270};
    private static final double[] ALL_STATUES = {0, 45, 135, 180, 225, 270, 315};
    private static final double[] BISECTORS = {22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5};
    private static final Title.Times TITLE_TIMES = Title.Times.times(
        Duration.ofMillis(150), Duration.ofMillis(1700), Duration.ofMillis(400));
    private static final List<List<Action>> ROTATIONS = List.of(
        List.of(Action.BAND, Action.NOVA, Action.SPOKES, Action.SHOCK, Action.HEART, Action.BRAND,
            Action.SPIRAL, Action.BAND, Action.NOVA, Action.SPOKES, Action.SHOCK, Action.HEART),
        List.of(Action.GAZE, Action.SWEEP, Action.BAND, Action.NOVA, Action.METEOR, Action.SHOCK, Action.STARFALL,
            Action.SPOKES, Action.SPIRAL, Action.HEART, Action.BRAND, Action.GAZE, Action.SWEEP, Action.METEOR, Action.SHOCK),
        List.of(Action.COMBO, Action.SWEEP, Action.STARFALL, Action.SPIRAL, Action.GAZE, Action.SHOCK, Action.NOVA,
            Action.METEOR, Action.HEART, Action.COMBO, Action.BRAND, Action.SWEEP, Action.SPIRAL, Action.SHOCK));

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
     * @return 露出中は1.35、星核崩壊中は1.6、調律中は0、それ以外は1
     */
    double incomingDamageMultiplier(MobInstance boss, long tick) {
        if (!BOSS_ID.equals(boss.template().id())) return 1.0D;
        if (protectedNow(boss)) return 0.0D;
        Encounter state = encounters.get(boss.instanceId());
        return state != null && !state.disabled && tick < state.exposureUntil ? state.exposureMultiplier : 1.0D;
    }

    /**
     * 同期5tick周期で一個体の予告・発動・調律を進めます。
     * @param boss 生存個体 @param entity 戦闘実体 @param tick 単調時計
     * @param canAct 新しい行動を始められるか @param persistentEncounter 不在中も専用行動を保持するか
     */
    void tick(MobInstance boss, Entity entity, long tick, boolean canAct, boolean persistentEncounter) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, display));
        if (state.disabled) return;
        List<Player> active = List.of();
        try {
            active = participants.apply(boss);
            if (state.particlesUnavailable || state.soundUnavailable) warnPresentation(state, active);
            if (active.isEmpty()) {
                closeCast(state);
                closeExposureBar(state);
                state.barrage.clear();
                if (state.ritual != null) syncBar(state.ritual.bar, active);
                boss.scriptedAction(persistentEncounter || state.originalScripted);
                state.nextAt = Math.max(state.nextAt, tick + 40L);
                return;
            }
            boss.scriptedAction(true);
            mobs.stopPathfinding(boss);
            state.barrage.observe(insidePlayers(state, active), tick);
            if (!state.introduced) {
                announce(active, PlayerMsgId.P_8090);
                sound(state, active, NyxeronSound.INTRO, 1.0F);
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
                state.barrage.clear();
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
            fail(state, boss, failure);
        } finally {
            // 調律の開始・終了と同じtickの結果を姿勢と無敵表示へ反映する。
            if (!state.disabled && encounters.get(boss.instanceId()) == state) {
                boolean immune = state.ritual != null || protectedNow(boss);
                display.body(boss.instanceId(), entity, tick, pose(state, tick, immune), immune);
            }
        }
    }

    /**
     * 毎tick、弾幕・衝撃環・光刃・星雨を進めます。要素がない個体は参加者の解決も行いません。
     * @param tick 単調時計
     */
    void pulse(long tick) {
        for (Encounter state : List.copyOf(encounters.values())) {
            if (state.disabled || !state.barrage.active()) continue;
            try {
                List<Player> active = participants.apply(state.boss);
                if (active.isEmpty()) {
                    state.barrage.clear();
                    continue;
                }
                state.barrage.tick(state.host(this, active), active, tick);
            } catch (RuntimeException failure) {
                fail(state, state.boss, failure);
            }
        }
    }

    private void fail(Encounter state, MobInstance boss, RuntimeException failure) {
        Logger.log(LogId.E_6544, failure, boss.instanceId());
        state.disabled = true;
        closeCast(state);
        closeRitual(state);
        closeExposureBar(state);
        state.barrage.clear();
        state.boss.scriptedAction(state.originalScripted);
        try {
            abort.accept(boss);
        } catch (RuntimeException abortFailure) {
            Logger.log(LogId.E_6549, abortFailure, boss.instanceId());
            mobs.destroy(boss.instanceId());
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
            case NOVA -> nova(state, phase, tick);
            case SPIRAL -> spiral(state, phase, tick);
            case SHOCK -> shock(state, phase, tick);
            case SWEEP -> sweep(state, phase, tick);
            case STARFALL -> starfall(state, inside, phase, tick);
        };
        for (Player player : inside) cast.eligible.add(player.getUniqueId());
        state.cast = cast;
        cast.bar = Bukkit.createBossBar(cast.title.apply(tick), cast.color, BarStyle.SEGMENTED_10);
        cast.bar.setVisible(true);
        cast.intro.accept(active);
        title(active, cast.titleId, cast.subtitleId);
        showStep(state, cast, tick);
        updateBar(cast, active, tick);
        drawSafely(state, cast, active, tick);
        sound(state, active, cast.cue, 1.0F);
    }

    /** 環影: 偶数帯（中心円・第二環・外周環）と奇数帯（第一環・第三環）を交互に塗ります。 */
    private Cast band(Encounter state, int phase, long tick) {
        boolean even = state.bandParity++ % 2 == 0;
        int strikes = phase + 1;
        long gap = phase == 1 ? 30L : 25L;
        long first = tick + 60L;
        Cast cast = new Cast(Action.BAND, tick, BarColor.RED, NyxeronSound.CUE_BAND, PlayerMsgId.P_8162, PlayerMsgId.P_8163);
        List<int[]> patterns = new ArrayList<>();
        for (int i = 0; i < strikes; i++) patterns.add((even ^ (i % 2 == 1)) ? EVEN_BANDS : ODD_BANDS);
        for (int i = 0; i < strikes; i++) {
            int[] bands = patterns.get(i);
            cast.steps.add(new Step(first + gap * i, (s, c, players, now) -> strikeBands(s, c, players, bands, 0.55D),
                c -> bandTelegraph(state.center, bands)));
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
        Cast cast = new Cast(Action.SPOKES, tick, BarColor.YELLOW, NyxeronSound.CUE_SPOKE, PlayerMsgId.P_8164, PlayerMsgId.P_8165);
        long first = tick + 50L;
        if (phase == 0) {
            double[] set = state.spokeParity++ % 2 == 0 ? EVEN_STATUES : ODD_STATUES;
            cast.steps.add(new Step(first, (s, c, players, now) -> strikeSpokes(s, c, players, set, 0.60D),
                c -> spokeTelegraph(state.center, set)));
            cast.drawer = (s, c, now, warn, safe, accent) -> drawSpokes(s.center, set, warn);
            cast.intro = players -> announce(players, PlayerMsgId.P_8102, directionNames(set), seconds(first, tick));
            cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8104.getId(), directionNames(set),
                seconds(cast.nextAt(), now));
        } else {
            long second = first + 30L;
            cast.steps.add(new Step(first, (s, c, players, now) -> strikeSpokes(s, c, players, ALL_STATUES, 0.55D),
                c -> spokeTelegraph(state.center, ALL_STATUES)));
            cast.steps.add(new Step(second, (s, c, players, now) -> strikeSpokes(s, c, players, BISECTORS, 0.55D),
                c -> spokeTelegraph(state.center, BISECTORS)));
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
        Cast cast = new Cast(Action.HEART, tick, BarColor.PINK, NyxeronSound.CUE_HEART, PlayerMsgId.P_8166, PlayerMsgId.P_8167);
        long first = tick + 40L;
        List<NyxeronPackDisplay.Shape> innerShapes = List.of(
            NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.DISC, state.center, HEART_INNER, 0.6D),
            approach(state.center, HEART_INNER, 0.3D));
        List<NyxeronPackDisplay.Shape> outerShapes = List.of(
            NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.HEART, state.center, HEART_OUTER, 0.6D),
            approach(state.center, HEART_OUTER, HEART_INNER));
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
        Cast cast = new Cast(Action.BRAND, tick, BarColor.PURPLE, NyxeronSound.CUE_BRAND, PlayerMsgId.P_8168, PlayerMsgId.P_8169);
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
        Cast cast = new Cast(Action.GAZE, tick, BarColor.BLUE, NyxeronSound.CUE_GAZE, PlayerMsgId.P_8170, PlayerMsgId.P_8171);
        int firstSeal = ThreadLocalRandom.current().nextInt(NyxeronArena.SEALS.size());
        long first = tick + 100L;
        List<Integer> origins = new ArrayList<>(List.of(firstSeal));
        if (phase >= 2) origins.add((firstSeal + 4) % NyxeronArena.SEALS.size());
        for (int i = 0; i < origins.size(); i++) {
            int seal = origins.get(i);
            Location eye = NyxeronArena.seal(state.center, seal);
            List<NyxeronPackDisplay.Shape> shapes = List.of(NyxeronPackDisplay.Shape.facing(NyxeronPackDisplay.Style.GAZE,
                eye, state.center, 4.0F, 4.0F), approach(eye, 2.8D, 0.4D));
            cast.steps.add(new Step(first + 50L * i, (s, c, players, now) -> strikeGaze(s, c, players, seal, 0.70D, now),
                c -> shapes));
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
        Cast cast = new Cast(Action.METEOR, tick, BarColor.YELLOW, NyxeronSound.CUE_METEOR, PlayerMsgId.P_8172, PlayerMsgId.P_8173);
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
        Cast cast = new Cast(Action.COMBO, tick, BarColor.RED, NyxeronSound.CUE_COMBO, PlayerMsgId.P_8174, PlayerMsgId.P_8175);
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
            display.strike(s.boss.instanceId(), now);
            sound(s, players, NyxeronSound.IMPACT_BAND, 0.9F);
            sound(s, players, NyxeronSound.IMPACT_SPOKE, 1.0F);
        }, c -> {
            List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>(bandTelegraph(state.center, bands));
            shapes.addAll(spokeTelegraph(state.center, spokes));
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

    /** 星環弾: 48発の輪を連射し、輪の切れ目が少しずつ回ります。 */
    private Cast nova(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.NOVA, tick, BarColor.YELLOW, NyxeronSound.CUE_NOVA, PlayerMsgId.P_8135, PlayerMsgId.P_8136);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int volleys = phase == 0 ? 6 : phase == 1 ? 8 : 10;
        long gap = phase == 0 ? 16L : phase == 1 ? 14L : 12L;
        int gaps = phase == 2 ? 4 : 2;
        int half = phase == 0 ? 2 : 1;
        double speed = phase == 0 ? 0.30D : phase == 1 ? 0.32D : 0.34D;
        double step = (phase == 2 ? 15.0D : 7.5D) * (random.nextBoolean() ? 1.0D : -1.0D);
        double[] angles = new double[volleys];
        double angle = random.nextDouble(360.0D);
        for (int i = 0; i < volleys; i++) {
            angles[i] = NyxeronBarrage.quantize(angle);
            double direction = phase == 0 ? 1.0D : phase == 1 ? (i + 1 < volleys / 2 ? 1.0D : -1.0D) : ((i / 3) % 2 == 0 ? 1.0D : -1.0D);
            angle += step * direction;
        }
        long first = tick + 40L;
        for (int i = 0; i < volleys; i++) {
            int volley = i;
            cast.steps.add(new Step(first + gap * i, (s, c, players, now) -> {
                s.barrage.nova(now, angles[volley], gaps, half, speed, 0.22D);
                display.strike(s.boss.instanceId(), now);
                sound(s, players, NyxeronSound.NOVA_BURST, 1.0F + volley * 0.03F);
            }));
        }
        cast.countdown = false;
        cast.recovery = 60L;
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            int index = Math.min(c.next, volleys - 1);
            for (int g = 0; g < gaps; g++) drawRay(s.center, angles[index] + g * 360.0D / gaps, 3.0D, 38.0D, 2.0D, safe);
            if (index + 1 < volleys) for (int g = 0; g < gaps; g++)
                drawRay(s.center, angles[index + 1] + g * 360.0D / gaps, 6.0D, 30.0D, 4.0D, accent);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8137, volleys, gaps);
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8138.getId(), Math.min(cast.next + 1, volleys), volleys);
        return cast;
    }

    /** 星渦: 回転する腕から弾が流れ出し、列の切れ目をくぐって避けます。 */
    private Cast spiral(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.SPIRAL, tick, BarColor.BLUE, NyxeronSound.CUE_SPIRAL, PlayerMsgId.P_8139, PlayerMsgId.P_8140);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double sign = random.nextBoolean() ? 1.0D : -1.0D;
        double start = random.nextDouble(360.0D);
        long first = tick + 40L;
        long duration = phase == 0 ? 90L : 100L;
        int arms = phase == 0 ? 3 : phase == 1 ? 4 : 3;
        cast.steps.add(new Step(first, (s, c, players, now) -> {
            switch (phase) {
                case 0 -> s.barrage.spiral(now, 3, start, 4.0D * sign, 3, duration, 0.28D, 0.20D);
                case 1 -> s.barrage.spiral(now, 4, start, 3.5D * sign, 3, duration / 2, 0.28D, 0.20D);
                default -> {
                    s.barrage.spiral(now, 3, start, 4.0D * sign, 4, duration, 0.30D, 0.20D);
                    s.barrage.spiral(now, 3, start + 60.0D, -4.0D * sign, 4, duration, 0.30D, 0.20D);
                }
            }
        }));
        if (phase == 1) {
            // 第2段階は半分の時点で回転を反転する。
            double reversed = start + 3.5D * sign * (duration / 2);
            cast.steps.add(new Step(first + duration / 2, (s, c, players, now) -> {
                s.barrage.spiral(now, 4, reversed, -3.5D * sign, 3, duration - duration / 2, 0.28D, 0.20D);
                sound(s, players, NyxeronSound.SWEEP_REVERSE, 1.2F);
            }));
        }
        cast.steps.add(new Step(first + duration, (s, c, players, now) -> { }));
        cast.countdown = false;
        cast.recovery = 50L;
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            if (c.next > 0) return;
            for (int arm = 0; arm < arms; arm++) drawRay(s.center, start + arm * 360.0D / arms, 3.0D, 20.0D, 2.0D, accent);
            if (phase >= 2) for (int arm = 0; arm < 3; arm++)
                drawRay(s.center, start + 60.0D + arm * 120.0D, 3.0D, 20.0D, 2.0D, accent);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8141, phase >= 2 ? 6 : arms, duration / 20L);
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8142.getId(),
            seconds(cast.steps.getLast().at(), now));
        return cast;
    }

    /** 衝撃環: 低い環は跳び、高い環はしゃがんでやり過ごします。第1段階は低い環だけ。 */
    private Cast shock(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.SHOCK, tick, BarColor.WHITE, NyxeronSound.CUE_SHOCK, PlayerMsgId.P_8143, PlayerMsgId.P_8144);
        boolean flip = state.shockParity++ % 2 == 1;
        boolean[] pattern = switch (phase) {
            case 0 -> new boolean[] {false, false, false, false, false};
            case 1 -> flip ? new boolean[] {false, true, false, true, true, false}
                : new boolean[] {false, false, true, false, true, true};
            default -> flip ? new boolean[] {false, true, true, false, true, false, false, true}
                : new boolean[] {false, true, false, true, true, false, true, false};
        };
        long[] gaps = switch (phase) {
            case 0 -> new long[] {22L, 22L, 30L, 22L};
            case 1 -> new long[] {22L, 20L, 24L, 20L, 20L};
            default -> new long[] {16L, 18L, 16L, 20L, 16L, 16L, 18L};
        };
        double speed = phase == 0 ? 0.42D : phase == 1 ? 0.45D : 0.5D;
        long at = tick + 45L;
        for (int i = 0; i < pattern.length; i++) {
            boolean high = pattern[i];
            cast.steps.add(new Step(at, (s, c, players, now) -> {
                s.barrage.ring(now, high, speed, 0.50D);
                display.strike(s.boss.instanceId(), now);
                sound(s, players, high ? NyxeronSound.SHOCK_HIGH : NyxeronSound.SHOCK_LOW, 1.0F);
            }));
            if (i < gaps.length) at += gaps[i];
        }
        cast.countdown = false;
        cast.recovery = 60L;
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            if (c.next >= pattern.length) return;
            circle(s.center, 3.0D, 12, pattern[c.next] ? accent : warn);
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8145, pattern.length);
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8146.getId(),
            PlayerMsgResource.getMessage((pattern[Math.min(cast.next, pattern.length - 1)] ? PlayerMsgId.P_8148
                : PlayerMsgId.P_8147).getId()), seconds(cast.nextAt(), now));
        return cast;
    }

    /** 光刃旋回: 床を回る光刃を、青は跳び、桃色はしゃがんで避けます。第3段階は途中で逆回転します。 */
    private Cast sweep(Encounter state, int phase, long tick) {
        Cast cast = new Cast(Action.SWEEP, tick, BarColor.YELLOW, NyxeronSound.CUE_SWEEP, PlayerMsgId.P_8149, PlayerMsgId.P_8150);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double start = random.nextDouble(360.0D);
        double omega = (phase >= 2 ? 2.2D : 1.8D) * (random.nextBoolean() ? 1.0D : -1.0D);
        boolean[] highs = phase >= 2 ? new boolean[] {false, true, false} : new boolean[] {false, false, false};
        long duration = phase >= 2 ? 180L : 160L;
        long first = tick + 50L;
        List<NyxeronPackDisplay.Shape> telegraph = new ArrayList<>();
        for (int i = 0; i < highs.length; i++) {
            telegraph.add(NyxeronPackDisplay.Shape.bar(state.center, start + i * 360.0D / highs.length,
                NyxeronBarrage.ARENA_EDGE, 1.2F));
        }
        cast.steps.add(new Step(first, (s, c, players, now) -> s.barrage.beams(now, start, highs, omega, duration, 0.45D),
            c -> telegraph));
        if (phase >= 2) {
            cast.steps.add(new Step(first + 90L, (s, c, players, now) -> {
                s.barrage.reverseBeams(now);
                announce(players, PlayerMsgId.P_8153);
                sound(s, players, NyxeronSound.SWEEP_REVERSE, 1.0F);
            }));
        }
        cast.steps.add(new Step(first + duration, (s, c, players, now) -> { }));
        cast.countdown = false;
        cast.recovery = 40L;
        cast.drawer = (s, c, now, warn, safe, accent) -> {
            if (c.next > 0) return;
            for (int i = 0; i < highs.length; i++) {
                double angle = start + i * 360.0D / highs.length;
                drawRay(s.center, angle, 2.0D, 40.0D, 2.0D, warn);
                // 回転の向きを示す短い弧。
                for (int k = 1; k <= 4; k++) drawRay(s.center, angle + Math.signum(omega) * k * 4.0D, 12.0D, 22.0D, 5.0D, accent);
            }
        };
        cast.intro = players -> announce(players, PlayerMsgId.P_8151, highs.length, duration / 20L);
        cast.title = now -> cast.next == 0
            ? PlayerMsgResource.format(PlayerMsgId.P_8152.getId(), seconds(first + duration, now))
            : PlayerMsgResource.format(PlayerMsgId.P_8152.getId(), seconds(cast.steps.getLast().at(), now));
        return cast;
    }

    /** 星雨と超新星: 落ちる星を避けながら星片を集め、規定数で超新星を止めると星核崩壊で大きくよろめきます。 */
    private Cast starfall(Encounter state, List<Player> inside, int phase, long tick) {
        Cast cast = new Cast(Action.STARFALL, tick, BarColor.YELLOW, NyxeronSound.CUE_STARFALL, PlayerMsgId.P_8154, PlayerMsgId.P_8155);
        int need = Math.min(8, 2 + inside.size());
        long duration = phase >= 2 ? 220L : 200L;
        // 予告中のBossBarに前回の星片の数を出さないよう、集計はこの時点で戻す。
        state.barrage.resetFragments();
        long first = tick + 40L;
        long resolve = first + duration + 10L;
        cast.need = need;
        cast.steps.add(new Step(first, (s, c, players, now) -> {
            s.barrage.resetFragments();
            s.barrage.rain(now, duration, 4, phase >= 2 ? 3 : 2, 30L, RAIN_RADIUS, 0.35D, phase >= 2 ? 7 : 6, 0.32D);
            c.supernova = display.effects(s.boss.instanceId(), List.of(new NyxeronPackDisplay.Shape(
                NyxeronPackDisplay.Style.SUPERNOVA, s.center.clone().add(0, 2.8D, 0), 7.0F, 7.0F, 7.0F)), now, resolve - now);
            sound(s, players, NyxeronSound.SUPERNOVA_CHARGE, 0.8F);
        }));
        cast.steps.add(new Step(resolve - 60L, (s, c, players, now) -> sound(s, players, NyxeronSound.SUPERNOVA_CHARGE, 1.2F)));
        cast.steps.add(new Step(resolve, (s, c, players, now) -> resolveSupernova(s, c, players, now)));
        cast.countdown = false;
        cast.recovery = 40L;
        cast.intro = players -> announce(players, PlayerMsgId.P_8156, (resolve - tick) / 20L, need);
        cast.title = now -> PlayerMsgResource.format(PlayerMsgId.P_8157.getId(), seconds(resolve, now),
            state.barrage.collected(), need);
        return cast;
    }

    private void resolveSupernova(Encounter state, Cast cast, List<Player> active, long tick) {
        int collected = state.barrage.collected();
        cast.supernova.run();
        state.barrage.resetFragments();
        if (collected >= cast.need) {
            state.exposureUntil = tick + STAGGER_TICKS;
            state.exposureLength = STAGGER_TICKS;
            state.exposureMultiplier = STAGGER_MULTIPLIER;
            state.exposureTitle = PlayerMsgId.P_8161;
            state.nextAt = Math.max(state.nextAt, state.exposureUntil);
            announce(active, PlayerMsgId.P_8159, STAGGER_TICKS / 20L);
            title(active, PlayerMsgId.P_8178, PlayerMsgId.P_8179);
            sound(state, active, NyxeronSound.STAGGER, 1.0F);
            display.effects(state.boss.instanceId(), List.of(NyxeronPackDisplay.Shape.circle(
                NyxeronPackDisplay.Style.IMPACT, state.center, 9.0D, 4.0D)), tick, 14L);
            updateExposureBar(state, active, tick);
            return;
        }
        announce(active, PlayerMsgId.P_8160, cast.need - collected);
        double ratio = Math.min(1.5D, 0.6D + 0.15D * (cast.need - collected));
        for (Player player : active) {
            if (!cast.eligible.contains(player.getUniqueId()) || !NyxeronArena.inside(player.getLocation(), state.center)) continue;
            hit.hit(state.boss, player, ratio);
            if (ended(state, cast)) return;
        }
        display.effects(state.boss.instanceId(), List.of(NyxeronPackDisplay.Shape.circle(
            NyxeronPackDisplay.Style.IMPACT, state.center, NyxeronArena.FLOOR_RADIUS, 4.0D)), tick, 16L);
        display.strike(state.boss.instanceId(), tick);
        sound(state, active, NyxeronSound.SUPERNOVA_BLAST, 1.0F);
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
            long pause = state.completed == 0 ? 40L : state.completed == 1 ? 30L : 25L;
            state.nextAt = Math.max(state.nextAt, tick + pause + cast.recovery);
            return;
        }
        if (fired) showStep(state, cast, tick);
        countdown(state, cast, active, tick);
        updateBar(cast, active, tick);
        if (tick % 10L == 0L) drawSafely(state, cast, active, tick);
    }

    /** 発動の3・2・1秒前と直前に刻みの音を鳴らします。 */
    private void countdown(Encounter state, Cast cast, List<Player> active, long tick) {
        if (!cast.countdown && cast.next > 0) return;
        long remaining = cast.nextAt() - tick;
        if (remaining == 60L || remaining == 40L || remaining == 20L) {
            sound(state, active, NyxeronSound.COUNT, 1.0F + (60L - remaining) / 100.0F);
        } else if (remaining == 5L) {
            sound(state, active, NyxeronSound.COUNT_FINAL, 1.0F);
        }
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
        sound(state, active, NyxeronSound.IMPACT_BAND, 1.0F);
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
        sound(state, active, NyxeronSound.IMPACT_SPOKE, 1.0F);
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
        sound(state, active, NyxeronSound.IMPACT_HEART, inner <= 0.0D ? 1.0F : 1.15F);
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
        sound(state, active, NyxeronSound.IMPACT_BRAND, 1.0F);
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
        sound(state, active, NyxeronSound.IMPACT_GAZE, 1.0F);
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
        sound(state, active, NyxeronSound.IMPACT_METEOR, 1.0F);
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
        title(active, PlayerMsgId.P_8176, PlayerMsgId.P_8177);
        sound(state, active, NyxeronSound.RITUAL_START, 1.0F);
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
                sound(state, active, NyxeronSound.SEAL_DONE, 1.0F + ritual.attuned * 0.06F);
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
            sound(state, inside, NyxeronSound.IMPACT_METEOR, 1.0F);
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
        sound(state, inside, NyxeronSound.CUE_METEOR, 1.1F);
    }

    /** 成功では露出、失敗では天球崩落を発動し、次の段階へ進めます。 */
    private void finishRitual(Encounter state, List<Player> active, long tick, boolean success) {
        Ritual ritual = state.ritual;
        closeRitual(state);
        state.completed++;
        if (success) {
            state.exposureUntil = tick + EXPOSURE_TICKS;
            state.exposureLength = EXPOSURE_TICKS;
            state.exposureMultiplier = EXPOSED_MULTIPLIER;
            state.exposureTitle = PlayerMsgId.P_8123;
            state.nextAt = state.exposureUntil;
            announce(active, PlayerMsgId.P_8121, EXPOSURE_TICKS / 20L);
            sound(state, active, NyxeronSound.RITUAL_SUCCESS, 1.0F);
            updateExposureBar(state, active, tick);
        } else {
            announce(active, PlayerMsgId.P_8122);
            for (Player player : insidePlayers(state, active)) {
                hit.hit(state.boss, player, 1.20D);
                if (encounters.get(state.boss.instanceId()) != state) return;
            }
            display.effects(state.boss.instanceId(), List.of(NyxeronPackDisplay.Shape.circle(
                NyxeronPackDisplay.Style.IMPACT, state.center, NyxeronArena.FLOOR_RADIUS, 3.0D)), tick, 14L);
            sound(state, active, NyxeronSound.RITUAL_FAIL, 1.0F);
            state.nextAt = tick + 60L;
        }
        if (ritual != null) {
            announce(active, state.completed == 1 ? PlayerMsgId.P_8092 : PlayerMsgId.P_8093);
            sound(state, active, NyxeronSound.PHASE, 1.0F);
        }
    }

    private static String ritualTitle(Ritual ritual, long tick) {
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < ritual.seals.size(); i++)
            if (ritual.progress[i] < SEAL_REQUIRED_TICKS) remaining.add(ritual.seals.get(i));
        return PlayerMsgResource.format(PlayerMsgId.P_8119.getId(), ritual.attuned, ritual.seals.size(),
            seconds(ritual.endsAt, tick), sealNames(remaining));
    }

    // ------------------------------------------------------------------ shapes & drawing

    private static NyxeronPackDisplay.Shape approach(Location center, double from, double to) {
        return NyxeronPackDisplay.Shape.ring(NyxeronPackDisplay.Style.APPROACH, center, from, to, 0.35D, 0.0D);
    }

    private static List<NyxeronPackDisplay.Shape> bandShapes(Location center, int[] bands) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (int band : bands) {
            double outer = NyxeronArena.band(band)[1];
            shapes.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.band(band), center, outer, 0.5D));
        }
        return shapes;
    }

    /** 帯の床と、外縁から内縁へ縮んで発動の瞬間を示す輪。 */
    private static List<NyxeronPackDisplay.Shape> bandTelegraph(Location center, int[] bands) {
        List<NyxeronPackDisplay.Shape> shapes = bandShapes(center, bands);
        for (int band : bands) {
            double[] range = NyxeronArena.band(band);
            shapes.add(approach(center, range[1], Math.max(0.3D, range[0])));
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

    /** 光条の床と、中心から壁へ伸びて発動の瞬間に届く帯。 */
    private static List<NyxeronPackDisplay.Shape> spokeTelegraph(Location center, double[] angles) {
        List<NyxeronPackDisplay.Shape> shapes = spokeShapes(center, angles);
        for (double angle : angles) {
            shapes.add(NyxeronPackDisplay.Shape.bar(center, angle, NyxeronArena.FLOOR_RADIUS, (float) (SPOKE_HALF * 1.2D)));
        }
        return shapes;
    }

    private static List<NyxeronPackDisplay.Shape> meteorShapes(List<Location> targets) {
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (Location target : targets) {
            shapes.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.METEOR, target, METEOR_RADIUS, 6.0D));
            shapes.add(approach(target, METEOR_RADIUS, 0.3D));
            shapes.add(NyxeronPackDisplay.Shape.star(target, 14.0D, 2.0F));
        }
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

    private static void drawRay(Location center, double angle, double from, double to, double step, List<Location> out) {
        double radians = Math.toRadians(angle);
        for (double distance = from; distance <= to; distance += step)
            out.add(center.clone().add(Math.cos(radians) * distance, 0.3D, Math.sin(radians) * distance));
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

    /** 弾や環の代替粒子は、専用表示を持たない接続にだけ送ります。 */
    private void fallback(Encounter state, List<Player> active, List<Location> points, SharedParticleDefinition definition) {
        if (points.isEmpty() || state.particlesUnavailable) return;
        try {
            for (Player player : active) if (!display.hasPack(player.getUniqueId())) particles.spawnForViewer(player, points, definition);
        } catch (RuntimeException failure) {
            state.particlesUnavailable = true;
            Logger.log(LogId.E_6547, failure, state.boss.instanceId());
            warnPresentation(state, active);
        }
    }

    private void sound(Encounter state, List<Player> active, NyxeronSound sound, float pitch) {
        if (state.soundUnavailable) return;
        try {
            sound.play(active, display::hasPack, 1.0F, pitch);
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

    private static void title(List<Player> active, PlayerMsgId title, PlayerMsgId subtitle) {
        if (title == null) return;
        try {
            Title shown = Title.title(PlayerMsgResource.getComponent(title.getId()),
                PlayerMsgResource.getComponent(subtitle.getId()), TITLE_TIMES);
            for (Player player : active) player.showTitle(shown);
        } catch (RuntimeException ignored) {
            // 画面表示の失敗で戦闘を止めない。チャットとBossBarが同じ内容を示す。
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
        state.exposureBar.setTitle(PlayerMsgResource.format(state.exposureTitle.getId(), seconds(state.exposureUntil, tick)));
        state.exposureBar.setProgress(Math.clamp((state.exposureUntil - tick) / (double) Math.max(1L, state.exposureLength),
            0.0D, 1.0D));
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
        cast.supernova.run();
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
     * 戦闘実体が一時的に無効な間、予告と弾幕を取り消して調律の期限を止めます。
     * 再開時に期限切れの連撃がまとめて発動したり、不在中に調律が失敗したりしないようにします。
     * @param bossId 生存中の個体 @param tick 単調時計
     */
    void suspend(UUID bossId, long tick) {
        Encounter state = encounters.get(bossId);
        if (state == null || state.disabled) return;
        closeCast(state);
        closeExposureBar(state);
        state.barrage.clear();
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
        state.barrage.clear();
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

    private enum Action { BAND, SPOKES, HEART, BRAND, GAZE, METEOR, COMBO, NOVA, SPIRAL, SHOCK, SWEEP, STARFALL }

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
        final NyxeronBarrage barrage;
        boolean introduced;
        boolean disabled;
        boolean particlesUnavailable;
        boolean soundUnavailable;
        int completed;
        int actions;
        int bandParity;
        int spokeParity;
        int shockParity;
        long nextAt;
        long exposureUntil;
        long exposureLength = EXPOSURE_TICKS;
        double exposureMultiplier = EXPOSED_MULTIPLIER;
        PlayerMsgId exposureTitle = PlayerMsgId.P_8123;
        BossBar exposureBar;
        Cast cast;
        Ritual ritual;

        Encounter(MobInstance boss, NyxeronPackDisplay display) {
            this.boss = boss;
            this.center = boss.spawnLocation().clone();
            this.originalScripted = boss.scriptedAction();
            this.barrage = new NyxeronBarrage(boss.instanceId(), center, display);
        }

        /** @return この遭遇へ命中・代替粒子・効果音・星片の通知を渡す処理 */
        NyxeronBarrage.Host host(NyxeronEncounterController controller, List<Player> active) {
            Encounter state = this;
            return new NyxeronBarrage.Host() {
                @Override
                public boolean hit(Player player, double ratio) {
                    controller.hit.hit(boss, player, ratio);
                    return controller.encounters.get(boss.instanceId()) == state && !state.disabled;
                }

                @Override
                public void fallback(List<Player> viewers, List<Location> points, NyxeronBarrage.Mark mark) {
                    controller.fallback(state, viewers, points, switch (mark) {
                        case BULLET -> SharedParticleDefinitions.NYXERON_BULLET;
                        case LOW -> SharedParticleDefinitions.NYXERON_LOW;
                        case HIGH -> SharedParticleDefinitions.NYXERON_HIGH;
                        case FRAGMENT -> SharedParticleDefinitions.NYXERON_FRAGMENT;
                        case WARNING -> SharedParticleDefinitions.BOSS_ATTACK_WARNING;
                    });
                }

                @Override
                public void sound(List<Player> viewers, NyxeronBarrage.Cue cue, float pitch) {
                    controller.sound(state, viewers, switch (cue) {
                        case BULLET -> NyxeronSound.BULLET;
                        case FRAGMENT_DROP -> NyxeronSound.FRAGMENT_DROP;
                        case IMPACT -> NyxeronSound.IMPACT_METEOR;
                    }, pitch);
                }

                @Override
                public void collected(List<Player> viewers, Player player, int collected) {
                    Cast current = state.cast;
                    int need = current == null ? 0 : current.need;
                    announce(viewers, PlayerMsgId.P_8158, player.getName(), collected, need);
                    controller.sound(state, viewers, NyxeronSound.FRAGMENT_PICKUP, 1.0F + Math.min(0.6F, collected * 0.05F));
                }
            };
        }
    }

    private static final class Cast {
        final Action action;
        final long startedAt;
        final BarColor color;
        final NyxeronSound cue;
        final PlayerMsgId titleId;
        final PlayerMsgId subtitleId;
        final List<Step> steps = new ArrayList<>();
        final Set<UUID> eligible = new HashSet<>();
        final Set<UUID> protectionNotified = new HashSet<>();
        final Map<UUID, Location> brands = new HashMap<>();
        final List<Location> meteors = new ArrayList<>();
        int next;
        int need;
        boolean countdown = true;
        long recovery;
        BossBar bar;
        Drawer drawer;
        Function<Long, String> title = ignored -> "";
        Consumer<List<Player>> intro = ignored -> { };
        Runnable cleanup = () -> { };
        Runnable supernova = () -> { };

        Cast(Action action, long startedAt, BarColor color, NyxeronSound cue, PlayerMsgId titleId, PlayerMsgId subtitleId) {
            this.action = action;
            this.startedAt = startedAt;
            this.color = color;
            this.cue = cue;
            this.titleId = titleId;
            this.subtitleId = subtitleId;
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
