package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 月蝕の綴り魔女の直列詠唱・封頁解除・反撃窓を5tickの共通時計で進めます。
 * HP、ダメージ、挑戦参加者、報酬の正本は既存サービスに置き、遅延taskを作りません。
 */
final class SeleneaEncounterController {
    static final String BOSS_ID = "selenea_eclipse_witch";
    private static final String GUARD = "selenea_sealed_grimoire";
    private static final String EXPOSURE = "selenea_unbound_grimoire";
    private static final int MAX_POINTS = 2048;
    private static final int MAX_DISPLAYS = 16;
    private static final double RUNE_RADIUS = 3.0D;
    private static final long RUNE_HOLD = 30L;
    private static final Stage[] FIRST = {Stage.FORK, Stage.MARKS, Stage.INNER, Stage.SEALS};
    private static final Stage[] SECOND = {Stage.FORK, Stage.PASSAGE, Stage.MARKS, Stage.INNER, Stage.SEALS};
    private static final Stage[] FINAL = {Stage.FORK, Stage.MARKS, Stage.PASSAGE, Stage.INNER, Stage.SEALS};

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final SeleneaPressureController pressure;
    private final ToIntFunction<MobInstance> partySize;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private @Nullable TemporarySkillEffectService effects;
    private Predicate<MobInstance> fieldResolver = ignored -> false;

    /** @param resolver 専用フィールドの実ワールドを判定する関数 */
    void setFieldResolver(@NotNull Predicate<MobInstance> resolver) { fieldResolver = resolver; }

    /**
     * @param mobs 実体位置を同期する共通サービス
     * @param particles 設定と閲覧者を考慮する共通演出サービス
     * @param participants 生存する確定参加者を返す関数
     * @param hitHandler 共通魔法ダメージへの委譲先 @param partySize 確定名簿の参加人数
     */
    SeleneaEncounterController(MobService mobs, ParticleDisplayService particles,
        Function<MobInstance, List<Player>> participants, HitHandler hitHandler, ToIntFunction<MobInstance> partySize) {
        this.mobs = mobs; this.particles = particles; this.participants = participants; this.hitHandler = hitHandler;
        this.pressure = new SeleneaPressureController(particles, hitHandler);
        this.partySize = partySize;
    }

    /** @param service 専用の防護・反撃倍率を適用するサービス */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) { effects = service; }

    /**
     * 生存個体の予兆を同期処理で進めます。HP境界は一段ずつ完了まで保持します。
     * @param boss 操作可能な魔女
     * @param entity 有効な同一worldの実体
     * @param tick 単調増加する5tick時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, fieldResolver.test(boss)));
        try {
            List<Player> active = active(boss);
            if (active.isEmpty() || entity.getWorld() != state.center.getWorld() || !SeleneaArena.loaded(state.center)) {
                cancelCast(boss.instanceId());
                return;
            }
            if (!SeleneaArena.inArena(entity.getLocation(), state.center)) {
                cancelCast(boss.instanceId());
                Location floor = SeleneaArena.ground(state.center);
                if (floor != null) mobs.resetPosition(boss, floor);
                state.nextAt = tick + 40L;
                return;
            }
            state.requestedPhase = Math.max(state.requestedPhase, phase(boss));
            if (tick % 20L == 0L) {
                List<Location> boundary = new ArrayList<>(96);
                ring(state.center, state.radius, 96, boundary);
                particles.spawnForNearbyViewers(boundary, SharedParticleDefinitions.SELENEA_BOUNDARY, true);
            }
            // HP境界の大技は通常詠唱より優先し、飛翔弾や波も同時に中断します。
            boolean pendingUltimate = state.completedPhase < state.requestedPhase;
            if (pendingUltimate && state.introduced && (state.cast == null || state.cast.stage != Stage.ULTIMATE)) {
                close(state);
                state.followUp = null;
                state.followDirection = null;
                state.pendingPhase = state.completedPhase + 1;
                if (tick >= state.nextAt && begin(state, Stage.ULTIMATE, tick, null)) {
                    announce(state, PlayerMsgId.P_7692, state.pendingPhase - 1);
                }
            }
            pressure.tick(boss, entity, active, tick, pendingUltimate || !state.introduced,
                state.radius, state.field, partySize.applyAsInt(boss));
            if (encounters.get(boss.instanceId()) != state || boss.currentHealth() <= 0 || !entity.isValid()) return;
            if (state.cast != null) { advance(state, entity, active, tick); return; }
            if (pendingUltimate && state.introduced) return;
            if (tick < state.nextAt) return;
            if (!state.introduced) { begin(state, Stage.INTRO, tick, null); return; }
            if (!fieldResolver.test(boss) && tick >= state.nextBoundaryAt
                && active.stream().anyMatch(p -> !SeleneaArena.inArena(p.getLocation(), state.center))) {
                if (begin(state, Stage.RETURN, tick, null)) state.nextBoundaryAt = tick + 240L;
                return;
            }
            if (inside(active, state.center, state.radius, state.field).isEmpty()) return;
            if (state.followUp != null) {
                if (begin(state, state.followUp, tick, state.followDirection)) {
                    state.followUp = null;
                    state.followDirection = null;
                }
                return;
            }
            Stage[] rotation = state.completedPhase == 1 ? FIRST : state.completedPhase == 2 ? SECOND : FINAL;
            if (begin(state, rotation[state.actionIndex % rotation.length], tick, null)) state.actionIndex++;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6509, exception, boss.instanceId());
            cancelCast(boss.instanceId());
            state.nextAt = tick + 40L;
        }
    }

    /**
     * 主呪文・分身の構成を変えず、管理中の通常弾・波・発射予告だけを中間tickで進めます。
     * @param tick 5tick時計と同じ軸の1tick時計 @param allowed 状態異常・拘束による実行資格
     */
    void tickPressure(long tick, Predicate<MobInstance> allowed) {
        for (Encounter state : List.copyOf(encounters.values())) {
            MobInstance boss = state.boss;
            try {
                Entity entity = mobs.entityController().getEntity(boss);
                if (mobs.getInstance(boss.instanceId()) != boss || boss.currentHealth() <= 0
                    || !state.field && (boss.state() == MobState.IDLE || boss.state() == MobState.LEASHED)
                    || entity == null || !entity.isValid() || entity.isDead() || !allowed.test(boss)
                    || entity.getWorld() != state.center.getWorld() || !SeleneaArena.loaded(state.center)
                    || !SeleneaArena.inArena(entity.getLocation(), state.center)) {
                    pressure.clear(boss.instanceId());
                    continue;
                }
                List<Player> active = active(boss);
                if (active.isEmpty()) { pressure.clear(boss.instanceId()); continue; }
                pressure.tickMotion(boss, entity, active, tick, ultimateProtected(boss) || !state.introduced,
                    state.radius, state.field);
            } catch (RuntimeException failure) {
                Logger.log(LogId.E_6509, failure, boss.instanceId());
                cancelCast(boss.instanceId());
            }
        }
    }

    /** @return 現在HPが60%・20%を厳密に下回った境界から算出した段階 */
    private static int phase(MobInstance boss) {
        if (boss.maxHealth() <= 0) return 1;
        return boss.currentHealth() < boss.maxHealth() * 0.20D ? 3
            : boss.currentHealth() < boss.maxHealth() * 0.60D ? 2 : 1;
    }

    /**
     * 支援回復・CC分岐の前にHP境界を記録し、詠唱できない間も到達した大技を取り消しません。
     * @param boss 生存する魔女。位置・表示・ダメージは変更しません
     */
    void observeHealthThreshold(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id()) || boss.currentHealth() <= 0) return;
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, fieldResolver.test(boss)));
        state.requestedPhase = Math.max(state.requestedPhase, phase(boss));
    }

    /**
     * 大技の開始待ち・詠唱中の通常、状態異常、固定HP追加ダメージを共通処理で遮断します。
     * @param boss 被弾対象 @return この魔女に未完了のHP境界または大技がある場合true
     */
    boolean ultimateProtected(MobInstance boss) {
        if (!BOSS_ID.equals(boss.template().id()) || boss.currentHealth() <= 0) return false;
        observeHealthThreshold(boss);
        Encounter state = encounters.get(boss.instanceId());
        return state != null && state.requestedPhase > state.completedPhase
            || state != null && (state.pendingPhase > state.completedPhase || state.cast != null && state.cast.stage == Stage.ULTIMATE);
    }

    /** @return 生存人数に応じた必要封頁数。ソロでも1枚で成立 */
    static int sealCount(int activeCount) { return Math.clamp((activeCount + 1) / 2, 1, 3); }

    /**
     * 詠唱を全時間付きで開始します。退避不能な攻撃・到達不能な封頁は予約しません。
     * @return 正常に開始した場合true。地形不備では一呼吸置いて再試行
     */
    private boolean begin(Encounter state, Stage stage, long tick, @Nullable Vector inherited) {
        List<Player> active = active(state.boss);
        List<Player> targets = stage == Stage.RETURN || stage == Stage.INTRO ? active : inside(active, state.center, state.radius, state.field);
        if (targets.isEmpty()) return false;
        List<Player> ordered = new ArrayList<>(targets);
        ordered.sort(Comparator.comparing(Player::getUniqueId));
        Player primary = ordered.get(Math.floorMod(state.targetCursor, ordered.size()));
        Vector aim = inherited == null ? SeleneaArena.direction(state.center, primary.getLocation()) : inherited.clone();
        List<Location> marks = new ArrayList<>();
        if (stage == Stage.MARKS) {
            int count = Math.min(3, sealCount(ordered.size()));
            for (int i = 0; i < count; i++) {
                Player player = ordered.get(Math.floorMod(state.targetCursor + i, ordered.size()));
                Location mark = SeleneaArena.ground(player.getLocation(), state.center, state.field);
                if (mark != null) marks.add(mark);
            }
            if (marks.isEmpty()) return retry(state, tick);
        }
        Cast cast = new Cast(stage, tick, state.center, aim, marks, state.radius, warningDuration(stage, state.field), state.field, state.books);
        if (stage.shape != SeleneaArena.Shape.NONE) {
            for (Player player : targets) {
                Location safe = SeleneaArena.safeSpot(player.getLocation(), state.center, stage.shape, aim, marks, cast.duration, cast.radius, cast.field);
                if (safe == null) return retry(state, tick);
            }
        }
        if (stage == Stage.SEALS && !prepareRunes(cast, ordered)) return retry(state, tick);
        state.cast = cast; // 生成途中の例外もこのcastが所有する表示を回収する。
        if (stage == Stage.ULTIMATE) {
            cast.originalImmune = state.boss.damageImmune();
            mobs.setDamageImmune(state.boss, true);
        }
        state.boss.scriptedAction(true);
        mobs.stopPathfinding(state.boss);
        cast.bar = Bukkit.createBossBar(stage == Stage.SEALS
                ? PlayerMsgResource.format(PlayerMsgId.P_7656.getId(), 0, cast.required, 0)
                : PlayerMsgResource.format(stage.message.getId()),
            stage == Stage.SEALS || stage == Stage.EXPOSURE ? BarColor.BLUE : BarColor.PURPLE, BarStyle.SEGMENTED_10);
        buildVisuals(cast, tick);
        if (stage == Stage.SEALS) announce(state, PlayerMsgId.P_7660, cast.required);
        if (stage == Stage.INTRO || stage == Stage.EXPOSURE || stage == Stage.ULTIMATE) announce(state, stage.message);
        if (stage == Stage.INTRO) {
            if (state.field) announce(state, PlayerMsgId.P_7688);
            announce(state, PlayerMsgId.P_7691);
        }
        if (stage == Stage.MARKS || stage == Stage.FORK) state.targetCursor++;
        updateEffects(state);
        updateBar(state, active, tick);
        draw(cast);
        sound(state.center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F);
        return true;
    }

    /** 地形が整うまで無害に待ち、毎tickの床走査を防ぎます。 */
    private static boolean retry(Encounter state, long tick) { state.nextAt = tick + 40L; return false; }

    /**
     * 中心から9mの床に、半径3mの輪が重ならない6m以上の間隔で封頁を配置します。
     * 少なくとも一人が予兆時間から保持30tickを除いた時間で歩いて届く床だけを採用します。
     * @param cast 解除操作の予兆と出力先 @param players 円内の参加者 @return 必要枚数を配置できた場合true
     */
    private static boolean prepareRunes(Cast cast, List<Player> players) {
        cast.required = sealCount(players.size());
        for (int i = 0; i < 12 && cast.runes.size() < cast.required; i++) {
            double angle = i * Math.PI * 5.0D / 6.0D;
            Location point = SeleneaArena.ground(cast.center.clone().add(Math.cos(angle) * 9.0D, 0, Math.sin(angle) * 9.0D));
            if (point == null || cast.runes.stream().anyMatch(r -> SeleneaArena.squared(r.point, point) < 4.0D * RUNE_RADIUS * RUNE_RADIUS)) continue;
            boolean reachable = players.stream().anyMatch(p -> {
                Location feet = SeleneaArena.ground(p.getLocation(), cast.center, cast.field);
                return feet != null && SeleneaArena.squared(feet, point) <= Math.pow((cast.duration - RUNE_HOLD) / 20.0D * 3.0D, 2)
                    && SeleneaArena.walkablePath(feet, point);
            });
            if (reachable) cast.runes.add(new Rune(point));
        }
        return cast.runes.size() == cast.required;
    }

    /** 予兆・操作を進め、同じcastの着弾を一度だけ確定します。 */
    private void advance(Encounter state, Entity entity, List<Player> active, long tick) {
        Cast cast = state.cast;
        updateEffects(state);
        animate(cast, tick);
        if ((tick - cast.started) % 10L == 0L) draw(cast);
        if (cast.stage == Stage.SEALS) {
            if (advanceRunes(state, cast, active, tick)) { finishRitual(state, tick, true, cast.direction); return; }
        }
        updateBar(state, active, tick);
        if (tick < cast.ends) return;
        if (cast.stage.shape != SeleneaArena.Shape.NONE) {
            impact(cast);
            for (Player player : List.copyOf(active)) {
                if (!contains(cast, player.getLocation())) continue;
                Location origin = cast.stage == Stage.MARKS ? cast.marks.stream()
                    .min(Comparator.comparingDouble(mark -> SeleneaArena.squared(mark, player.getLocation())))
                    .orElse(cast.center) : cast.center;
                if (!SeleneaArena.visible(origin.clone().add(0, 1.0D, 0), player.getEyeLocation())) continue;
                hitHandler.hit(state.boss, player, cast.stage.ratio);
                if (state.cast != cast || state.boss.currentHealth() <= 0 || !entity.isValid()) return;
            }
        }
        if (cast.stage == Stage.SEALS) { finishRitual(state, tick, false, cast.direction); return; }
        Stage previous = cast.stage;
        Vector direction = cast.direction.clone();
        close(state);
        if (previous == Stage.INTRO) state.introduced = true;
        // 成功反撃窓はHP境界を飛び越えても最後まで保証する。
        if (previous == Stage.ULTIMATE) completePhase(state);
        Stage follow = switch (previous) {
            case INNER -> Stage.OUTER;
            case FORK -> state.completedPhase == 3 ? Stage.ECHO : Stage.RECOVERY;
            case PASSAGE -> state.completedPhase == 3 ? Stage.TURN_PAGE : Stage.RECOVERY;
            default -> Stage.RECOVERY;
        };
        if (previous == Stage.ECHO || previous == Stage.TURN_PAGE) follow = Stage.RECOVERY;
        if (previous == Stage.RECOVERY) { state.nextAt = tick; return; }
        if (follow == Stage.ECHO || follow == Stage.TURN_PAGE) direction.rotateAroundY(Math.PI / 2.0D);
        if (!begin(state, follow, tick, direction) && (follow != Stage.RECOVERY || state.followUp == null)) {
            state.followUp = follow;
            state.followDirection = direction;
        }
    }

    /**
     * 保持時間は個人ごとに計測し、解除済み枚数を残します。減員では必要残数を減らします。
     * @return 必要枚数が揃った場合true
     */
    private boolean advanceRunes(Encounter state, Cast cast, List<Player> active, long tick) {
        List<Player> available = inside(active, cast.center, cast.radius, cast.field);
        if (available.isEmpty()) {
            for (Rune rune : cast.runes) if (!rune.done) rune.holders.clear();
            return false;
        }
        int done = (int) cast.runes.stream().filter(r -> r.done).count();
        cast.required = Math.min(cast.required, done + sealCount(available.size()));
        Set<UUID> assigned = new HashSet<>();
        for (Rune rune : cast.runes) {
            if (rune.done) continue;
            Set<UUID> present = new HashSet<>();
            for (Player player : available) {
                UUID id = player.getUniqueId();
                if (assigned.contains(id) || !player.isSneaking()) continue;
                Location feet = player.getLocation();
                if (SeleneaArena.squared(feet, rune.point) > RUNE_RADIUS * RUNE_RADIUS
                    || !SeleneaArena.sameFloor(feet, rune.point) || !SeleneaArena.standingOnGround(feet)
                    || !SeleneaArena.visible(rune.point.clone().add(0, 0.5D, 0), player.getEyeLocation())) continue;
                assigned.add(id);
                present.add(id);
                long start = rune.holders.computeIfAbsent(id, ignored -> tick);
                if (tick - start >= RUNE_HOLD) {
                    rune.done = true;
                    rune.holders.clear();
                    if (rune.display != null) rune.display.setBlock(Material.SEA_LANTERN.createBlockData());
                    sound(rune.point, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.6F);
                    break;
                }
            }
            rune.holders.keySet().retainAll(present);
        }
        return cast.runes.stream().filter(r -> r.done).count() >= cast.required;
    }

    /** 儀式を解いた場合は5秒の反撃窓、失敗時は完全な予兆を持つ回避可能な月蝕へ進みます。 */
    private void finishRitual(Encounter state, long tick, boolean success, Vector direction) {
        close(state);
        Stage result = success ? Stage.EXPOSURE : Stage.ECLIPSE;
        if (!begin(state, result, tick, direction)) {
            // 解除成否を失わず、結果の予兆または反撃窓を再試行する。
            state.followUp = result;
            state.followDirection = direction.clone();
            state.nextAt = tick + 40L;
        }
    }

    /** 実際に完了した境界だけを記録し、次の未完了段階を温存します。 */
    private static void completePhase(Encounter state) {
        if (state.pendingPhase > 0) {
            state.completedPhase = state.pendingPhase;
            state.pendingPhase = 0;
            state.actionIndex = 0;
        }
    }

    /** 実時間期限の共有倍率をcastのtick時計に同期し、別ボスや別効果へ影響させません。 */
    private void updateEffects(Encounter state) {
        if (effects == null || state.cast == null) return;
        if (state.cast.stage == Stage.SEALS) effects.apply(state.boss.instanceId(), GUARD, 40L, 0.35D, 1, 1);
        if (state.cast.stage == Stage.EXPOSURE) effects.apply(state.boss.instanceId(), EXPOSURE, 40L, 1.45D, 1, 1);
    }

    /** CC・対象喪失では現在の予兆と待機中の次波を解除し、HP境界の進行記録を維持します。 */
    void cancelCast(@NotNull UUID id) {
        Encounter state = encounters.get(id);
        if (state != null) {
            close(state);
            clearBooks(state);
            pressure.clear(id);
            state.followUp = null;
            state.followDirection = null;
            state.nextAt = 0L;
        }
    }

    /** @param id 死亡、帰還、破棄したボスのUUID */
    void clear(@NotNull UUID id) { cancelCast(id); encounters.remove(id); }

    /** 停止時に全個体の演出・倍率・行動ロックを回収します。 */
    void clearAll() { for (UUID id : List.copyOf(encounters.keySet())) clear(id); }

    /** @param liveIds 同期走査に残っている生存個体 */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!liveIds.contains(id)) clear(id);
    }

    /** @param playerId 転送した参加者。波が存在しない歩行経路を補間しないよう位置履歴を解除します */
    void forgetParticipantPosition(@NotNull UUID playerId) { pressure.forgetPlayerPosition(playerId); }

    /** castを先に無効化して再入を防ぎ、所有表示と専用効果だけを解除します。 */
    private void close(Encounter state) {
        Cast cast = state.cast;
        state.cast = null;
        state.boss.scriptedAction(false);
        if (effects != null) { effects.clear(state.boss.instanceId(), GUARD); effects.clear(state.boss.instanceId(), EXPOSURE); }
        if (cast == null) return;
        if (cast.stage == Stage.ULTIMATE) mobs.setDamageImmune(state.boss, cast.originalImmune || state.boss.template().damageImmune());
        if (cast.bar != null) cast.bar.removeAll();
        for (Entity display : cast.displays) display.remove();
        cast.displays.clear();
        for (Rune rune : cast.runes) rune.holders.clear();
    }

    /** 中断・個体終了では、通常の波切替で再利用した本も所有個体ごとに回収します。 */
    private static void clearBooks(Encounter state) {
        for (ItemDisplay book : state.books) book.remove();
        state.books.clear();
    }

    /** @return 専用フィールドでは四方向の足場と跳躍を含み、通常会場では同じ階の32m以内の参加者 */
    private List<Player> active(MobInstance boss) {
        return participants.apply(boss).stream().filter(p -> p.isOnline() && !p.isDead())
            .filter(p -> fieldResolver.test(boss) ? SeleneaArena.inFieldArena(p.getLocation(), boss.spawnLocation())
                : SeleneaArena.sameFloor(p.getLocation(), boss.spawnLocation())
                    && SeleneaArena.squared(p.getLocation(), boss.spawnLocation()) <= 1024.0D)
            .sorted(Comparator.comparing(Player::getUniqueId)).limit(6).toList();
    }

    /** @return 通常技と解除操作を行える円内の参加者 */
    private static List<Player> inside(List<Player> players, Location center, double radius, boolean field) {
        return players.stream().filter(p -> SeleneaArena.inAttackArena(p.getLocation(), center, radius, field)).toList();
    }

    /** @return 凍結済み形状の危険面に入っている場合true */
    private static boolean contains(Cast cast, Location point) {
        return SeleneaArena.contains(cast.stage.shape, cast.center, cast.direction, cast.marks, cast.radius, point, cast.field);
    }

    /**
     * 危険面と封頁の表示をcastへ、本3冊を個体へ登録します。実体ブロックは変更しません。
     * @param cast 所有する詠唱 @param tick 初期姿勢に使う共通時計
     */
    private static void buildVisuals(Cast cast, long tick) {
        buildDanger(cast);
        int passageMarkers = cast.stage.shape == SeleneaArena.Shape.PASSAGE ? 4 : 0;
        List<Location> anchors = shardAnchors(cast, MAX_DISPLAYS - 4 - passageMarkers);
        for (Location point : anchors) {
            BlockDisplay display = block(cast, point.clone().add(0, 1.2D, 0), Material.AMETHYST_BLOCK, 0.24F);
            cast.shards.add(new Shard(display, point));
        }
        if (passageMarkers > 0) {
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            for (double along : new double[]{-cast.radius * 0.65D, cast.radius * 0.65D}) {
                for (double across : new double[]{-SeleneaArena.PASSAGE_HALF_WIDTH + 0.3D, SeleneaArena.PASSAGE_HALF_WIDTH - 0.3D}) {
                    Location point = SeleneaArena.ground(cast.center.clone().add(cast.direction.clone().multiply(along))
                        .add(side.clone().multiply(across)));
                    if (point != null) block(cast, point.clone().add(0, 0.15D, 0), Material.SEA_LANTERN, 0.5F);
                }
            }
        }
        for (Rune rune : cast.runes) rune.display = block(cast, rune.point.clone().add(0, 0.2D, 0), Material.ENCHANTING_TABLE, 0.6F);
        cast.books.removeIf(book -> !book.isValid());
        for (int i = 0; i < SeleneaCastAnimation.BOOK_COUNT; i++) {
            SeleneaCastAnimation.Pose pose = bookPose(cast, i, tick);
            if (i >= cast.books.size()) {
                ItemDisplay book = cast.center.getWorld().spawn(bookLocation(cast, pose), ItemDisplay.class);
                cast.books.add(book); // 個体が先に所有し、生成途中の例外でも回収します。
                book.setPersistent(false);
                book.setGravity(false);
                book.setInvulnerable(true);
                book.setItemStack(new ItemStack(Material.ENCHANTED_BOOK));
                book.setBrightness(new Display.Brightness(15, 15));
                book.setTeleportDuration(5);
                book.setTransformation(bookTransformation(pose));
                book.setInterpolationDuration(5);
            } else {
                moveBook(cast, cast.books.get(i), pose);
            }
        }
    }

    /**
     * 粒子OFFでも形状を読めるよう、呪片を有限点群の配列順でなく各輪郭へ配置します。
     * @param cast 固定形状 @param maximum 呪片の上限 @return 同じ危険判定を満たす床だけの固定配置
     */
    private static List<Location> shardAnchors(Cast cast, int maximum) {
        List<Location> points = new ArrayList<>(maximum);
        switch (cast.stage.shape) {
            case FORK -> {
                for (int ray = -1; ray <= 1; ray++) {
                    Vector aim = cast.direction.clone().rotateAroundY(ray * Math.PI / 6.0D);
                    Vector side = new Vector(-aim.getZ(), 0, aim.getX());
                    for (double fraction : new double[]{0.25D, 0.75D}) {
                        for (double across : new double[]{-1.24D, 1.24D}) {
                            shardAnchor(cast, cast.center.clone().add(aim.clone().multiply(cast.radius * fraction))
                                .add(side.clone().multiply(across)), points, maximum);
                        }
                    }
                }
            }
            case MARKS -> {
                int count = maximum / cast.marks.size();
                for (Location mark : cast.marks) shardRing(cast, mark, SeleneaArena.MARK_RADIUS - 0.01D, count, points, maximum);
            }
            case INNER, OUTER -> shardRing(cast, cast.center, SeleneaArena.MOON_RADIUS
                + (cast.stage.shape == SeleneaArena.Shape.INNER ? -0.01D : 0.01D), maximum, points, maximum);
            case PASSAGE -> {
                Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
                for (double along : new double[]{-0.85D, -0.3D, 0.3D, 0.85D}) {
                    for (double across : new double[]{-SeleneaArena.PASSAGE_HALF_WIDTH - 0.05D, SeleneaArena.PASSAGE_HALF_WIDTH + 0.05D}) {
                        shardAnchor(cast, cast.center.clone().add(cast.direction.clone().multiply(cast.radius * along))
                            .add(side.clone().multiply(across)), points, maximum);
                    }
                }
            }
            case RETURN -> shardRing(cast, cast.center, cast.radius + 0.01D, maximum, points, maximum);
            default -> { }
        }
        return points;
    }

    /** 同じ危険面にある床だけを、所有表示の上限以内で採用します。 */
    private static void shardAnchor(Cast cast, Location point, List<Location> points, int maximum) {
        if (points.size() >= maximum || !contains(cast, point)) return;
        Location floor = SeleneaArena.ground(point);
        if (floor != null && contains(cast, floor)) points.add(floor.add(0, 0.12D, 0));
    }

    /** 最大12点の輪郭へ呪片を均等配置します。 */
    private static void shardRing(Cast cast, Location center, double radius, int count, List<Location> points, int maximum) {
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            shardAnchor(cast, center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), points, maximum);
        }
    }

    /**
     * 輪郭と面積に応じた有限サンプルを最大2048地点まで床へ投影します。
     * @param cast 凍結した形状。描画と命中で同じcontains判定を使います
     */
    private static void buildDanger(Cast cast) {
        SeleneaArena.Shape shape = cast.stage.shape;
        if (shape == SeleneaArena.Shape.NONE) return;
        if (shape == SeleneaArena.Shape.FORK) {
            for (int ray = -1; ray <= 1; ray++) {
                Vector aim = cast.direction.clone().rotateAroundY(ray * Math.PI / 6.0D);
                Vector side = new Vector(-aim.getZ(), 0, aim.getX());
                int steps = Math.max(24, (int) Math.ceil(cast.radius / 1.25D));
                for (int i = 0; i <= steps; i++) {
                    for (double across : new double[]{-1.24D, 0, 1.24D}) {
                        dangerPoint(cast, cast.center.clone().add(aim.clone().multiply((cast.radius - 0.1D) * i / steps))
                            .add(side.clone().multiply(across)));
                    }
                }
            }
        } else if (shape == SeleneaArena.Shape.MARKS) {
            for (Location mark : cast.marks) {
                dangerRing(cast, mark, SeleneaArena.MARK_RADIUS - 0.01D, 40);
                dangerDisc(cast, mark, 0, SeleneaArena.MARK_RADIUS - 0.1D, 40);
            }
        } else if (shape == SeleneaArena.Shape.PASSAGE) {
            Vector side = new Vector(-cast.direction.getZ(), 0, cast.direction.getX());
            double halfWidth = SeleneaArena.PASSAGE_HALF_WIDTH + 0.05D;
            double length = Math.sqrt(cast.radius * cast.radius - halfWidth * halfWidth) - 0.05D;
            int steps = Math.max(48, (int) Math.ceil(length * 2.0D / 1.25D));
            for (int i = 0; i <= steps; i++) {
                for (double across : new double[]{-halfWidth, halfWidth}) {
                    dangerPoint(cast, cast.center.clone().add(cast.direction.clone().multiply(-length + length * 2 * i / steps))
                        .add(side.clone().multiply(across)));
                }
            }
            dangerRing(cast, cast.center, cast.radius - 0.01D, 64);
            dangerDisc(cast, cast.center, 0, cast.radius - 0.1D, 96);
        } else {
            double inner = shape == SeleneaArena.Shape.INNER ? 0
                : shape == SeleneaArena.Shape.RETURN ? cast.radius : SeleneaArena.MOON_RADIUS;
            double outer = shape == SeleneaArena.Shape.INNER ? SeleneaArena.MOON_RADIUS
                : shape == SeleneaArena.Shape.RETURN ? SeleneaArena.ENGAGEMENT_RADIUS : cast.radius;
            if (inner > 0) dangerRing(cast, cast.center, inner + 0.01D, 64);
            dangerRing(cast, cast.center, outer - 0.01D, 96);
            dangerDisc(cast, cast.center, inner + 0.01D, outer - 0.1D, 96);
        }
    }

    /** @param cast 危険点の出力先 @param point 同じ危険判定を満たす床だけを最大2048点まで追加 */
    private static void dangerPoint(Cast cast, Location point) {
        if (cast.danger.size() >= MAX_POINTS || !contains(cast, point)) return;
        Location floor = SeleneaArena.ground(point);
        if (floor != null && contains(cast, floor)) cast.danger.add(floor.add(0, 0.12D, 0));
    }

    /** @param cast 詠唱 @param center 円中心 @param radius 輪郭半径 @param count 上限付きのサンプル数 */
    private static void dangerRing(Cast cast, Location center, double radius, int count) {
        count = Math.clamp(Math.max(count, (int) Math.ceil(Math.PI * 2.0D * radius / 1.25D)), 1, 512);
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count;
            dangerPoint(cast, center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
    }

    /**
     * 面積が均等な黄金角サンプルで円・環の内部を示し、面積5平方mにつき一点、最大1600点を配置します。
     * @param cast 詠唱 @param center 円中心 @param inner 内側半径 @param outer 外側半径 @param count 有限点数
     */
    private static void dangerDisc(Cast cast, Location center, double inner, double outer, int count) {
        count = Math.clamp(Math.max(count, (int) Math.ceil(Math.PI * (outer * outer - inner * inner) / 5.0D)), 1, 1600);
        for (int i = 0; i < count; i++) {
            double radius = Math.sqrt(inner * inner + (outer * outer - inner * inner) * (i + 0.5D) / count);
            double angle = i * Math.PI * (3.0D - Math.sqrt(5.0D));
            dangerPoint(cast, center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        }
    }

    /** 所有権登録を最初に行い、生成途中でも回収できる表示を作ります。 */
    private static BlockDisplay block(Cast cast, Location point, Material material, float scale) {
        BlockDisplay display = point.getWorld().spawn(point, BlockDisplay.class);
        cast.displays.add(display);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setBlock(material.createBlockData());
        display.setBrightness(new Display.Brightness(15, 15));
        display.setTeleportDuration(5);
        display.setTransformation(new Transformation(new Vector3f(-scale / 2, 0, -scale / 2),
            new Quaternionf(), new Vector3f(scale), new Quaternionf()));
        return display;
    }

    /**
     * 本を個体の共通時計で周回させ、呪片は着弾直前の座標変更時だけ更新します。
     * @param cast 所有する詠唱 @param tick 共通5tick時計
     */
    private static void animate(Cast cast, long tick) {
        for (int i = 0; i < cast.books.size(); i++) {
            moveBook(cast, cast.books.get(i), bookPose(cast, i, tick));
        }
        double progress = SeleneaCastAnimation.release(tick, cast.ends);
        if (progress == cast.shardRelease) return;
        cast.shardRelease = progress;
        for (Shard shard : cast.shards) {
            if (shard.display.isValid() && SeleneaArena.loaded(shard.point)) {
                shard.display.teleport(shard.point.clone().add(0, 1.2D * (1 - progress), 0));
            }
        }
    }

    /** 無害状態は解放率を使わず、個体の共通時計で詠唱・封頁・反撃の姿勢を計算します。 */
    private static SeleneaCastAnimation.Pose bookPose(Cast cast, int index, long tick) {
        SeleneaCastAnimation.Mood mood = switch (cast.stage) {
            case INTRO -> SeleneaCastAnimation.Mood.INTRO;
            case SEALS -> SeleneaCastAnimation.Mood.SEALS;
            case EXPOSURE -> SeleneaCastAnimation.Mood.EXPOSURE;
            case RECOVERY -> SeleneaCastAnimation.Mood.RECOVERY;
            default -> SeleneaCastAnimation.Mood.CAST;
        };
        return SeleneaCastAnimation.pose(mood, index, tick, (tick - cast.started) / (double) cast.duration,
            cast.stage.shape == SeleneaArena.Shape.NONE ? 0 : SeleneaCastAnimation.release(tick, cast.ends));
    }

    /** 本の中心相対位置を、cast所有worldの絶対位置に変換します。 */
    private static Location bookLocation(Cast cast, SeleneaCastAnimation.Pose pose) {
        return cast.center.clone().add(pose.x(), pose.y(), pose.z());
    }

    /** 姿勢とscaleを表示用Transformationへ変換します。 */
    private static Transformation bookTransformation(SeleneaCastAnimation.Pose pose) {
        return new Transformation(new Vector3f(), new Quaternionf().rotationYXZ(pose.yaw(), pose.pitch(), pose.roll()),
            new Vector3f(pose.scale()), new Quaternionf());
    }

    /** 通常の波切替では同じ本を5tick補間で新しい姿勢へ移し、生成・破棄の点滅を防ぎます。 */
    private static void moveBook(Cast cast, ItemDisplay book, SeleneaCastAnimation.Pose pose) {
        Location point = bookLocation(cast, pose);
        if (!book.isValid() || !SeleneaArena.loaded(point)) return;
        book.teleport(point);
        book.setInterpolationDelay(0);
        book.setTransformation(bookTransformation(pose));
    }

    /** 10tickごとに危険面とスニークする紫の文字輪を描き、退避先の白い補助輪は表示しません。 */
    private void draw(Cast cast) {
        particles.spawnForNearbyViewers(cast.danger, SharedParticleDefinitions.BOSS_ATTACK_WARNING, true);
        List<Location> sneak = new ArrayList<>(96);
        for (Rune rune : cast.runes) if (!rune.done) ring(rune.point, RUNE_RADIUS, 32, sneak);
        particles.spawnForNearbyViewers(sneak, SharedParticleDefinitions.SELENEA_SNEAK_RING, true);
        particles.spawnForNearbyViewers(sneak, SharedParticleDefinitions.SELENEA_SNEAK_ACCENT, true);
    }

    /** 危険形状の面積に応じた点群へ火花と最大512地点の爆発を重ねます。 */
    private void impact(Cast cast) {
        particles.spawnForNearbyViewers(cast.danger, SharedParticleDefinitions.SELENEA_ATTACK_SPARK, true);
        int count = Math.min(cast.danger.size(), Math.clamp(cast.danger.size() / 3, 24, 512));
        List<Location> bursts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) bursts.add(cast.danger.get(i * cast.danger.size() / count));
        particles.spawnForNearbyViewers(bursts, SharedParticleDefinitions.SELENEA_ATTACK_BURST, true);
        cast.center.getWorld().playSound(cast.center, Sound.ENTITY_GENERIC_EXPLODE, 3.0F,
            cast.stage.shape == SeleneaArena.Shape.FORK ? 1.4F : 0.8F);
    }

    /** 残時間、解除枚数、個人の継続保持進捗を参加者のBossBarへ反映します。 */
    private void updateBar(Encounter state, List<Player> active, long tick) {
        Cast cast = state.cast;
        if (cast.bar == null) return;
        cast.bar.setProgress(Math.clamp((cast.ends - tick) / (double) cast.duration, 0, 1));
        if (cast.stage == Stage.SEALS) {
            long done = cast.runes.stream().filter(r -> r.done).count();
            long held = cast.runes.stream().flatMap(r -> r.holders.values().stream())
                .mapToLong(start -> tick - start).max().orElse(0L);
            cast.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7656.getId(), done, cast.required,
                Math.min(100, held * 100 / RUNE_HOLD)));
        }
        Set<UUID> ids = new HashSet<>();
        for (Player player : active) { ids.add(player.getUniqueId()); if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player); }
        for (Player player : List.copyOf(cast.bar.getPlayers())) if (!ids.contains(player.getUniqueId())) cast.bar.removePlayer(player);
    }

    /** @param state 送信先の個体 @param id 共通文言ID @param args 置換値 */
    private void announce(Encounter state, PlayerMsgId id, Object... args) {
        for (Player player : active(state.boss)) PlayerMessageService.getInstance().send(player, id, args);
    }

    /** @param center 輪の中心 @param radius 半径 @param count 有限点数 @param out 出力先 */
    private static void ring(Location center, double radius, int count, List<Location> out) {
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2 / count;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius);
            if (SeleneaArena.loaded(point)) out.add(point);
        }
    }

    /** 同じ会場内だけへ技の節目の音を送ります。 */
    private static void sound(Location point, Sound sound, float pitch) { point.getWorld().playSound(point, sound, 0.85F, pitch); }

    /** 共通RPGダメージへの委譲。 */
    @FunctionalInterface interface HitHandler {
        /** @param boss 攻撃者 @param player 確定参加者 @param ratio 魔法攻撃倍率 */
        void hit(MobInstance boss, Player player, double ratio);
    }

    /**
     * 専用フィールドの48m端から徒歩3m/sで退避・解除できる時間を、詠唱開始時に固定します。
     * @param stage 技 @param field 専用フィールドか @return 予兆または操作のtick時間
     */
    private static long warningDuration(Stage stage, boolean field) {
        if (!field) return stage.duration;
        return switch (stage) {
            case OUTER -> 180L;
            case PASSAGE, TURN_PAGE, ECLIPSE, ULTIMATE -> 220L;
            case SEALS -> 280L;
            default -> stage.duration;
        };
    }

    /** 技の予兆時間、倍率、形状、操作案内。 */
    private enum Stage {
        INTRO(60, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7650),
        FORK(50, 0.75D, SeleneaArena.Shape.FORK, PlayerMsgId.P_7651),
        ECHO(40, 0.75D, SeleneaArena.Shape.FORK, PlayerMsgId.P_7661),
        MARKS(50, 0.80D, SeleneaArena.Shape.MARKS, PlayerMsgId.P_7652),
        INNER(100, 0.85D, SeleneaArena.Shape.INNER, PlayerMsgId.P_7653),
        OUTER(75, 0.85D, SeleneaArena.Shape.OUTER, PlayerMsgId.P_7654),
        PASSAGE(100, 0.90D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7655),
        TURN_PAGE(100, 0.90D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7662),
        SEALS(220, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7660),
        EXPOSURE(100, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7657),
        ECLIPSE(120, 1.10D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7658),
        ULTIMATE(160, 1.60D, SeleneaArena.Shape.PASSAGE, PlayerMsgId.P_7690),
        RECOVERY(15, 0, SeleneaArena.Shape.NONE, PlayerMsgId.P_7663),
        RETURN(120, 0.9D, SeleneaArena.Shape.RETURN, PlayerMsgId.P_7664);
        final long duration;
        final double ratio;
        final SeleneaArena.Shape shape;
        final PlayerMsgId message;
        /** @param duration tick時間 @param ratio 倍率 @param shape 危険形状 @param message 文言 */
        Stage(long duration, double ratio, SeleneaArena.Shape shape, PlayerMsgId message) {
            this.duration = duration; this.ratio = ratio; this.shape = shape; this.message = message;
        }
    }

    /** 一個体だけに属する進行状態。 */
    private static final class Encounter {
        final MobInstance boss;
        final Location center;
        final boolean field;
        final double radius;
        final List<ItemDisplay> books = new ArrayList<>(SeleneaCastAnimation.BOOK_COUNT);
        int completedPhase = 1, requestedPhase = 1, pendingPhase, actionIndex, targetCursor;
        long nextAt, nextBoundaryAt;
        boolean introduced;
        @Nullable Cast cast;
        @Nullable Stage followUp;
        @Nullable Vector followDirection;
        /** @param boss 所有ボス @param field 専用フィールドなら48mの会場を固定 */
        Encounter(MobInstance boss, boolean field) {
            this.boss = boss; this.center = boss.spawnLocation().clone(); this.field = field;
            this.radius = field ? SeleneaArena.FIELD_RADIUS : SeleneaArena.RADIUS;
        }
    }

    /** 単一詠唱の固定情報と所有する演出。 */
    private static final class Cast {
        final Stage stage;
        final long started, ends, duration;
        final double radius;
        final boolean field;
        final Location center;
        final Vector direction;
        final List<Location> marks, danger = new ArrayList<>();
        final List<Entity> displays = new ArrayList<>();
        final List<Shard> shards = new ArrayList<>();
        final List<ItemDisplay> books;
        final List<Rune> runes = new ArrayList<>();
        @Nullable BossBar bar;
        int required;
        double shardRelease;
        boolean originalImmune;
        /** @param stage 技 @param tick 開始 @param center 中心 @param direction 固定方向 @param marks 固定落款
         * @param radius 凍結した攻撃半径 @param duration 歩行退避に必要な予兆tick時間
         * @param field 高跳びを水平形状で判定する専用フィールドか @param books 個体所有の本3冊 */
        Cast(Stage stage, long tick, Location center, Vector direction, List<Location> marks, double radius, long duration,
             boolean field, List<ItemDisplay> books) {
            this.stage = stage; started = tick; ends = tick + duration; this.duration = duration; this.radius = radius;
            this.center = center.clone(); this.direction = direction.clone(); this.marks = List.copyOf(marks);
            this.field = field; this.books = books;
        }
    }

    /** 一枚の封頁。保持時間は個人間で合算しません。 */
    private static final class Rune {
        final Location point;
        final Map<UUID, Long> holders = new HashMap<>();
        @Nullable BlockDisplay display;
        boolean done;
        /** @param point 到達可能な床 */
        Rune(Location point) { this.point = point; }
    }

    /** 落下する呪片と固定された着弾位置。 */
    private record Shard(BlockDisplay display, Location point) { }
}
