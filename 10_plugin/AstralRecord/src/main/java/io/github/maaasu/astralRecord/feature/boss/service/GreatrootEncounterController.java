package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
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

/** 根脈の心守の固定予兆、六種の攻撃、召喚結界と反撃時間を同期時計で管理します。 */
final class GreatrootEncounterController {
    static final String BOSS_ID = "eriva_greatroot_heartwarden";
    private static final String BUD_ID = "eriva_greatroot_nourishing_bud";
    private static final String EXPOSURE_EFFECT = "greatroot-disconnected-exposure";
    private static final long LINK_TICKS = 500L;
    private static final long EXPOSURE_TICKS = 120L;
    private static final long DRAW_PERIOD = 10L;
    private static final Move[] ROTATION = {Move.ROOT_LINE, Move.FAN, Move.RINGS, Move.BLOOM, Move.SPIRAL};

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final Map<UUID, UUID> addOwners = new HashMap<>();
    private @Nullable TemporarySkillEffectService effects;

    /**
     * @param mobs 実体・無敵・召喚所有物を操作する既存サービス
     * @param particles 閲覧者設定を適用する共通表示サービス
     * @param participants 攻略中の部屋・会場にいる確定参加者だけを返す関数
     * @param hitHandler 共通RPGダメージへ無属性の攻撃倍率を渡す関数
     */
    GreatrootEncounterController(@NotNull MobService mobs, @NotNull ParticleDisplayService particles,
        @NotNull Function<MobInstance, List<Player>> participants, @NotNull HitHandler hitHandler) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.hitHandler = hitHandler;
    }

    /** @param service 全芽撃破後の短い被ダメージ倍率を管理する既存サービス */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) { effects = service; }

    /**
     * 条件不能中も結界期限・芽消滅・反撃満了を処理し、一個体に一つだけ予兆を進めます。
     * @param boss 生存する専用ボス
     * @param entity 有効なボス実体
     * @param tick BossMechanicServiceの単調時計
     * @param canAct CC・拘束・詠唱禁止を含め、新しい攻撃を実行できる場合はtrue
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick, boolean canAct) {
        try {
            advanceEncounter(boss, entity, tick, canAct);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6530, exception, BOSS_ID, entity.getWorld().getName());
            // 予兆だけでなく結界も解除し、例外を理由に無敵を残さない。
            Encounter state = encounters.get(boss.instanceId());
            if (state != null) {
                if (state.linkUntil > 0L || !state.buds.isEmpty() || state.ownsImmunity) {
                    endLink(state, active(boss), tick, false);
                } else {
                    cancelCast(boss.instanceId());
                    state.nextAt = tick + 20L;
                }
            }
        }
    }

    /** 参加者・world・帰還を先に検査し、HP境界を反撃窓の後に一つずつ消化します。 */
    private void advanceEncounter(MobInstance boss, Entity entity, long tick, boolean canAct) {
        List<Player> players = active(boss);
        if (players.isEmpty() || entity.getWorld() != boss.spawnLocation().getWorld()) {
            clear(boss.instanceId());
            return;
        }
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (!GreatrootArena.contains(entity.getLocation(), state.center)) {
            clear(boss.instanceId());
            Location home = GreatrootArena.ground(boss.spawnLocation());
            if (home != null) mobs.resetPosition(boss, home);
            return;
        }
        int observed = boss.maxHealth() <= 0.0D ? 1 : boss.currentHealth() <= boss.maxHealth() * 0.35D ? 3
            : boss.currentHealth() <= boss.maxHealth() * 0.70D ? 2 : 1;
        state.requestedPhase = Math.max(state.requestedPhase, observed);
        mobs.stopPathfinding(boss);
        boss.scriptedAction(true);
        entity.setVelocity(new Vector());
        if (state.linkUntil > 0L) {
            refreshBuds(state);
            if (state.buds.isEmpty() || tick >= state.linkUntil) {
                endLink(state, players, tick, state.buds.isEmpty());
            } else {
                if (tick % DRAW_PERIOD == 0L) drawLink(state, players, tick);
                if (!canAct) cancelCast(boss.instanceId());
                else if (state.cast != null) advanceCast(state, entity, players, tick);
                else if (tick >= state.nextAt) begin(state, players, Move.ROOT_LINE, tick, true);
                return;
            }
        }
        if (state.recoveryUntil > 0L) {
            if (tick < state.recoveryUntil) {
                if (state.recoveryBar != null) {
                    syncBar(state.recoveryBar, players);
                    state.recoveryBar.setProgress(Math.clamp((double) (state.recoveryUntil - tick)
                        / state.recoveryDuration, 0.0D, 1.0D));
                }
                return;
            }
            finishRecovery(state);
        }
        if (!canAct) { cancelCast(boss.instanceId()); return; }
        if (state.cast != null) { advanceCast(state, entity, players, tick); return; }
        if (tick < state.nextAt) return;
        if (!state.introduced) { begin(state, players, Move.INTRO, tick, false); return; }
        if (state.completedPhase < state.requestedPhase) {
            startLink(state, players, tick, state.completedPhase + 1);
            return;
        }
        // 最終段階は五つの通常技と循環を一巡にし、循環の後も根走りから始めます。
        int rotationLength = ROTATION.length + (state.completedPhase >= 3 ? 1 : 0);
        int rotationIndex = state.actionIndex % rotationLength;
        Move move = rotationIndex == ROTATION.length ? Move.CYCLE : ROTATION[rotationIndex];
        // 中断した技は開始時に既に数えています。再開で次の技を飛ばさないようにします。
        boolean resuming = state.resumeMove != null;
        if (begin(state, players, move, tick, false) && !resuming) state.actionIndex++;
    }

    /** @return 所有する芽なら対応ボス。消滅済みまたは無関係ならnull */
    @Nullable MobInstance ownerForAdd(@NotNull UUID id) {
        UUID owner = addOwners.get(id);
        return owner == null ? null : mobs.getInstance(owner);
    }

    /** @return この遭遇が所有する芽ならtrue */
    boolean ownsAdd(@NotNull UUID id) { return addOwners.containsKey(id); }

    /**
     * ボスまたは芽の破棄通知を受け、最後の芽が消えた際は次の走査を待たず無敵を復元します。
     * @param id 破棄されたMob個体ID
     */
    void handleDestroyed(@NotNull UUID id) {
        UUID owner = addOwners.remove(id);
        if (owner == null) { clear(id); return; }
        Encounter state = encounters.get(owner);
        if (state == null) return;
        state.buds.remove(id);
        if (state.linkUntil > 0L && state.buds.isEmpty()) {
            restoreImmunity(state);
            // 結界終了・反撃の時計は共通tickの次回走査で一度だけ確定する。
        }
    }

    /**
     * CCで未発動の一波だけを回収します。結界期限・完了済み波・フェーズは保持します。
     * @param id 中断するボス個体ID
     */
    void cancelCast(@NotNull UUID id) {
        Encounter state = encounters.get(id);
        if (state == null || state.cast == null) return;
        Cast cast = state.cast;
        state.cast = null;
        state.resumeMove = cast.move;
        state.resumeWave = Math.max(cast.wave, state.resumeWave);
        if (state.resumeWave >= cast.move.waves) {
            state.resumeMove = null;
            state.resumeWave = 0;
        }
        removeDisplays(cast.displays);
        closeBar(cast.bar);
        state.nextAt = 0L;
    }

    /**
     * 死亡・退出・帰還・再挑戦の所有物を回収し、保存していた無敵状態を復元します。
     * @param id 終了するボス個体ID。重複呼出しは何もしません
     */
    void clear(@NotNull UUID id) {
        Encounter state = encounters.remove(id);
        if (state == null) return;
        Cast cast = state.cast;
        state.cast = null;
        // 対応表を先に切り離し、destroy callbackによる再入で反撃を開始させない。
        for (UUID bud : state.buds.keySet()) addOwners.remove(bud);
        restoreImmunity(state);
        if (cast != null) { removeDisplays(cast.displays); closeBar(cast.bar); }
        if (state.linkBar != null) closeBar(state.linkBar);
        finishRecovery(state);
        destroyBuds(state);
        state.boss.scriptedAction(state.previousAction);
    }

    /** @param live 生存する専用ボスID。死亡・実体消失した所有物を回収します */
    void retain(@NotNull Set<UUID> live) {
        for (UUID id : List.copyOf(encounters.keySet())) if (!live.contains(id)) clear(id);
    }

    /** Plugin停止時に、全ての芽・Bar・Display・一時倍率・無敵を回収します。 */
    void clearAll() {
        for (UUID id : List.copyOf(encounters.keySet())) clear(id);
        addOwners.clear();
    }

    /** 有効な確定参加者を会場内だけへ絞り、内部状態からPlayerを持ち越しません。 */
    private @NotNull List<Player> active(MobInstance boss) {
        return participants.apply(boss).stream().filter(Player::isOnline).filter(Player::isValid)
            .filter(player -> !player.isDead() && GreatrootArena.contains(player.getLocation(), boss.spawnLocation())).toList();
    }

    /** 一波の照準・地面・安全地点を開始時に固定し、未完了の中断波だけを再開します。 */
    private boolean begin(Encounter state, List<Player> players, Move requested, long tick, boolean weak) {
        Move move = weak ? Move.ROOT_LINE : state.resumeMove == null ? requested : state.resumeMove;
        int wave = weak || state.resumeMove != move ? 0 : state.resumeWave;
        Location mark = GreatrootArena.ground(players.getFirst().getLocation());
        if (mark == null || !GreatrootArena.fullyLoaded(state.center)) { state.nextAt = tick + 20L; return false; }
        Vector direction = mark.toVector().subtract(state.center.toVector()).setY(0);
        if (direction.lengthSquared() < 0.01D) direction = new Vector(1, 0, 0);
        direction.normalize();
        List<Location> marks = move == Move.BLOOM ? players.stream().limit(3).map(Player::getLocation)
            .map(GreatrootArena::ground).filter(java.util.Objects::nonNull).toList() : List.of(mark);
        List<Location> shelters = move == Move.CYCLE ? cycleShelters(state.center, mark) : List.of();
        if (move == Move.CYCLE && shelters.size() != 3) { state.nextAt = tick + 20L; return false; }
        BossBar bar = Bukkit.createBossBar(title(move, wave), BarColor.GREEN, BarStyle.SEGMENTED_10);
        Cast cast = new Cast(move, wave, state.center, mark, direction, marks, shelters, bar, weak);
        state.cast = cast;
        if (!prepareWave(state, cast, players, tick)) {
            state.cast = null;
            closeBar(bar);
            state.nextAt = tick + 20L;
            return false;
        }
        state.resumeMove = null;
        state.resumeWave = 0;
        return true;
    }

    /** 予兆と同じ固定危険形状を使い、命中より先に次波checkpointを保存します。 */
    private void advanceCast(Encounter state, Entity entity, List<Player> players, long tick) {
        Cast cast = state.cast;
        if (cast == null) return;
        if (!GreatrootArena.fullyLoaded(cast.center)) { cancelCast(state.boss.instanceId()); return; }
        syncBar(cast.bar, players);
        cast.bar.setProgress(Math.clamp((double) (cast.impactAt - tick) / cast.warningTicks, 0.0D, 1.0D));
        if (tick % DRAW_PERIOD == 0L) draw(cast);
        if (tick < cast.impactAt) return;
        int completedWave = cast.wave;
        state.resumeMove = cast.move;
        state.resumeWave = completedWave + 1;
        particles.spawnForNearbyViewers(cast.center, cast.dangerPoints, SharedParticleDefinitions.MOB_GRANBAL_IMPACT);
        entity.getWorld().playSound(cast.mark, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.1F, 0.6F);
        if (cast.move != Move.INTRO) {
            for (Player player : players) {
                if (!danger(cast, player.getLocation()) || !GreatrootArena.sight(cast.center, player.getLocation())
                    || !cast.hit.add(player.getUniqueId())) continue;
                hitHandler.hit(state.boss, player, cast.weak ? 0.30D : cast.move.ratio);
                if (state.cast != cast || state.boss.currentHealth() <= 0.0D) return;
            }
        }
        removeDisplays(cast.displays);
        if (completedWave + 1 < cast.move.waves) {
            cast.wave++;
            if (prepareWave(state, cast, players, tick)) return;
            state.cast = null;
            closeBar(cast.bar);
            state.nextAt = tick + 20L;
            return;
        }
        state.cast = null;
        state.resumeMove = null;
        state.resumeWave = 0;
        closeBar(cast.bar);
        if (cast.move == Move.INTRO) state.introduced = true;
        if (state.linkUntil > 0L) state.nextAt = tick + 100L;
        else startRecovery(state, players, tick, cast.move == Move.CYCLE ? 70L : 35L, false);
    }

    /** 各波の危険点と安全輪を事前計算し、床がなく逃げられない予兆は開始しません。 */
    private boolean prepareWave(Encounter state, Cast cast, List<Player> players, long tick) {
        cast.safeSpots.clear();
        for (Player player : players) {
            Location safe = GreatrootArena.safe(player.getLocation(), cast.center, point -> danger(cast, point));
            if (cast.move != Move.INTRO && safe == null) return false;
            if (safe != null) cast.safeSpots.add(safe);
        }
        cast.hit.clear();
        cast.warningTicks = cast.move.warningTicks;
        cast.impactAt = tick + cast.warningTicks;
        cast.bar.setTitle(title(cast.move, cast.wave));
        cast.bar.setProgress(1.0D);
        syncBar(cast.bar, players);
        cast.dangerPoints = sample(cast, true);
        cast.safePoints = cast.move == Move.SPIRAL || cast.move == Move.CYCLE ? sample(cast, false) : List.of();
        if (cast.move != Move.INTRO && cast.dangerPoints.isEmpty()) return false;
        for (Location anchor : cast.move == Move.BLOOM ? cast.marks : List.of(cast.mark)) {
            spawnDisplay(cast, anchor);
        }
        draw(cast);
        WorldSound.play(cast.center, Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.0F, 0.65F);
        return true;
    }

    /** 3地点の安全円を互いに歩ける5m以内へ配置し、次波まで移動可能な床だけを採用します。 */
    private @NotNull List<Location> cycleShelters(Location arena, Location target) {
        List<Location> result = new ArrayList<>(3);
        Vector offset = target.toVector().subtract(arena.toVector()).setY(0);
        if (offset.length() > 6.0D) offset.normalize().multiply(6.0D);
        Location first = GreatrootArena.ground(arena.clone().add(offset));
        if (first == null || !GreatrootArena.safeCircle(first, arena, ignored -> false, 2.8D)) return List.of();
        result.add(first);
        Location previous = first;
        for (int wave = 1; wave < 3; wave++) {
            Location chosen = null;
            for (int i = 0; i < 12; i++) {
                double angle = wave * Math.PI * 2 / 3 + i * Math.PI / 6;
                Location candidate = GreatrootArena.ground(previous.clone().add(Math.cos(angle) * 4.0D, 0, Math.sin(angle) * 4.0D));
                if (candidate != null && GreatrootArena.safeCircle(candidate, arena, ignored -> false, 2.8D)
                    && GreatrootArena.squared(candidate, arena) <= 64.0D && GreatrootArena.path(previous, candidate)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) return List.of();
            result.add(chosen);
            previous = chosen;
        }
        return List.copyOf(result);
    }

    /** 予兆中は照準を動かさず、全ての段で同一のworld・高さ・形状判定を使用します。 */
    private boolean danger(Cast cast, Location point) {
        if (!GreatrootArena.contains(point, cast.center)) return false;
        double dx = point.getX() - cast.center.getX(), dz = point.getZ() - cast.center.getZ();
        double forward = dx * cast.direction.getX() + dz * cast.direction.getZ();
        double side = -dx * cast.direction.getZ() + dz * cast.direction.getX();
        double radius = Math.hypot(dx, dz);
        return switch (cast.move) {
            case INTRO -> false;
            case ROOT_LINE -> forward >= -1.0D && forward <= 14.0D && Math.abs(side) <= (cast.weak ? 0.85D : 1.35D);
            case FAN -> radius <= 11.0D && forward >= 0.0D && Math.abs(Math.atan2(side, forward)) <= Math.toRadians(55.0D);
            case RINGS -> {
                double range = Math.sqrt(GreatrootArena.squared(point, cast.mark));
                yield cast.wave == 0 ? range >= 4.5D && range <= 9.0D : range <= 5.5D;
            }
            case BLOOM -> cast.marks.stream().anyMatch(mark -> GreatrootArena.squared(point, mark) <= 2.2D * 2.2D);
            case SPIRAL -> radius <= 10.5D && radius >= 2.0D
                && Math.abs(Math.atan2(side, forward)) > Math.toRadians(50.0D);
            case CYCLE -> radius <= 11.0D && GreatrootArena.squared(point, cast.shelters.get(cast.wave)) > 2.8D * 2.8D;
        };
    }

    /** 一波につき最大192点を計算し、描画周期中に床探索を繰り返しません。 */
    private @NotNull List<Location> sample(Cast cast, boolean dangerous) {
        List<Location> points = new ArrayList<>(192);
        for (double x = -GreatrootArena.RADIUS; x <= GreatrootArena.RADIUS && points.size() < 192; x += 1.5D) {
            for (double z = -GreatrootArena.RADIUS; z <= GreatrootArena.RADIUS && points.size() < 192; z += 1.5D) {
                Location surface = GreatrootArena.ground(cast.center.clone().add(x, 0, z));
                if (surface == null || !GreatrootArena.contains(surface, cast.center)
                    || danger(cast, surface) != dangerous || !GreatrootArena.sight(cast.center, surface)) continue;
                if (!dangerous && cast.move == Move.CYCLE
                    && GreatrootArena.squared(surface, cast.shelters.get(cast.wave)) > 2.8D * 2.8D) continue;
                if (!dangerous && cast.move == Move.SPIRAL
                    && GreatrootArena.squared(surface, cast.center) > 10.5D * 10.5D) continue;
                points.add(surface.add(0, 0.15D, 0));
            }
        }
        return List.copyOf(points);
    }

    /** 攻撃予見・現在の安全輪・次の移動先を異なる共通定義でまとめて表示します。 */
    private void draw(Cast cast) {
        particles.spawnForNearbyViewers(cast.center, cast.dangerPoints, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        if (!cast.safePoints.isEmpty()) BossParticleEffect.safeArea(particles, cast.center, cast.safePoints);
        else for (Location safe : cast.safeSpots) BossParticleEffect.safeArea(particles, safe, GreatrootArena.ring(safe, 0.65D, 16));
        if (cast.move == Move.CYCLE && cast.wave + 1 < cast.shelters.size()) {
            particles.spawnForNearbyViewers(cast.center, GreatrootArena.ring(cast.shelters.get(cast.wave + 1), 2.8D, 24),
                SharedParticleDefinitions.BOSS_SAFE_BEACON);
        }
        if (cast.move == Move.ROOT_LINE) {
            List<Location> line = new ArrayList<>(12);
            for (int i = 1; i <= 12; i++) line.add(cast.center.clone().add(cast.direction.clone().multiply(i)).add(0, 0.4D, 0));
            particles.spawnForNearbyViewers(cast.center, line, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        }
        if (cast.move == Move.SPIRAL) {
            List<Location> roots = new ArrayList<>(72);
            double facing = Math.atan2(cast.direction.getZ(), cast.direction.getX());
            for (int arm = 0; arm < 3; arm++) {
                for (int i = 0; i < 24; i++) {
                    double radius = 2.0D + i * 0.35D;
                    double angle = facing + arm * Math.PI * 2 / 3 + radius * 0.35D;
                    Location point = GreatrootArena.ground(cast.center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                    if (point != null && danger(cast, point)) roots.add(point.add(0, 0.35D, 0));
                }
            }
            particles.spawnForNearbyViewers(cast.center, roots, SharedParticleDefinitions.BOSS_ATTACK_WARNING);
        }
    }

    /** 非衝突Displayで種の固定位置を示し、worldの実ブロックを変更しません。 */
    private void spawnDisplay(Cast cast, Location anchor) {
        if (!GreatrootArena.loaded(anchor)) return;
        BlockDisplay display = anchor.getWorld().spawn(anchor.clone().add(-0.2D, 0.1D, -0.2D), BlockDisplay.class, entity -> {
            entity.setBlock((cast.move == Move.BLOOM ? Material.MOSS_BLOCK : Material.ROOTED_DIRT).createBlockData());
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setViewRange(0.6F);
            entity.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(0.4F, 0.8F, 0.4F), new org.joml.Quaternionf()));
        });
        cast.displays.add(display.getUniqueId());
    }

    /** 全芽生成を確認してから無敵を開始します。部分失敗は全回収し、攻撃可能な反撃時間へ進めます。 */
    private void startLink(Encounter state, List<Player> players, long tick, int phase) {
        state.linkPhase = phase;
        state.resumeMove = null;
        state.resumeWave = 0;
        List<Location> anchors = GreatrootArena.budAnchors(state.center, phase);
        if (anchors.size() != phase) { endLink(state, players, tick, false); return; }
        try {
            for (Location anchor : anchors) {
                MobInstance bud = mobs.spawn(BUD_ID, anchor);
                if (bud == null) { endLink(state, players, tick, false); return; }
                if (encounters.get(state.boss.instanceId()) != state || mobs.getInstance(state.boss.instanceId()) != state.boss
                    || state.boss.currentHealth() <= 0.0D) {
                    // spawn中の外部callbackで所有ボスが終了した場合、戻ってきた新個体も残さない。
                    mobs.destroy(bud.instanceId());
                    destroyBuds(state);
                    return;
                }
                state.buds.put(bud.instanceId(), anchor.clone());
                addOwners.put(bud.instanceId(), state.boss.instanceId());
                bud.rewardEligible(false);
                bud.keepWhenUnobserved(true);
                bud.scriptedAction(true);
                mobs.stopPathfinding(bud);
                Entity entity = mobs.entityController().getEntity(bud);
                if (entity == null || !entity.isValid() || entity.isDead()) { endLink(state, players, tick, false); return; }
                entity.setVelocity(new Vector());
            }
            if (state.buds.size() != phase || state.buds.keySet().stream().anyMatch(id -> !alive(id))) {
                endLink(state, players, tick, false);
                return;
            }
            state.savedImmune = state.boss.damageImmune();
            state.ownsImmunity = true;
            mobs.setDamageImmune(state.boss, true);
            state.linkUntil = tick + LINK_TICKS;
            state.linkBar = Bukkit.createBossBar(PlayerMsgResource.format(PlayerMsgId.P_6748.getId(), state.buds.size()),
                BarColor.PURPLE, BarStyle.SEGMENTED_10);
            state.nextAt = tick + 60L;
            drawLink(state, players, tick);
            WorldSound.play(state.center, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0F, 0.6F);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6530, exception, BOSS_ID, state.center.getWorld().getName());
            endLink(state, players, tick, false);
        }
    }

    /** 死亡/期限判定を共通の残り時間Barと、中心から各芽への根脈表示に反映します。 */
    private void drawLink(Encounter state, List<Player> players, long tick) {
        if (state.linkBar != null) {
            state.linkBar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_6748.getId(), state.buds.size()));
            state.linkBar.setProgress(Math.clamp((double) (state.linkUntil - tick) / LINK_TICKS, 0.0D, 1.0D));
            syncBar(state.linkBar, players);
        }
        List<Location> roots = new ArrayList<>(96);
        for (Map.Entry<UUID, Location> entry : state.buds.entrySet()) {
            MobInstance bud = mobs.getInstance(entry.getKey());
            if (bud != null) mobs.holdPosition(bud, entry.getValue());
            Vector delta = entry.getValue().toVector().subtract(state.center.toVector());
            for (int i = 0; i <= 20; i++) roots.add(state.center.clone().add(delta.clone().multiply(i / 20.0D)).add(0, 0.3D, 0));
            roots.addAll(GreatrootArena.ring(entry.getValue(), 0.8D, 10));
        }
        particles.spawnForNearbyViewers(state.center, roots, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
    }

    /** 結界を先に解除してから芽を回収し、次閾値の前に6秒の反撃時間を必ず挟みます。 */
    private void endLink(Encounter state, List<Player> players, long tick, boolean defeated) {
        state.completedPhase = Math.max(state.completedPhase, state.linkPhase);
        state.actionIndex = 0;
        state.linkUntil = 0L;
        cancelCast(state.boss.instanceId());
        state.resumeMove = null;
        state.resumeWave = 0;
        restoreImmunity(state);
        if (state.linkBar != null) { closeBar(state.linkBar); state.linkBar = null; }
        destroyBuds(state);
        for (Player player : players) PlayerMessageService.getInstance().send(player,
            defeated ? PlayerMsgId.P_6750 : PlayerMsgId.P_6751);
        startRecovery(state, players, tick, EXPOSURE_TICKS, defeated);
    }

    /** @return Mob・実体・同一world・HPが生存している芽ならtrue */
    private boolean alive(UUID id) {
        MobInstance instance = mobs.getInstance(id);
        if (instance == null || instance.currentHealth() <= 0.0D) return false;
        Entity entity = mobs.entityController().getEntity(instance);
        if (entity == null || !entity.isValid() || entity.isDead()
            || entity.getWorld() != instance.spawnLocation().getWorld()) return false;
        return true;
    }

    /** 消失・別worldの芽も所有物として破棄し、UUIDを一覧から落とすだけで実体を残しません。 */
    private void refreshBuds(Encounter state) {
        for (UUID id : List.copyOf(state.buds.keySet())) {
            if (alive(id)) continue;
            state.buds.remove(id);
            addOwners.remove(id);
            try { mobs.destroy(id); }
            catch (RuntimeException exception) { Logger.log(LogId.E_6531, exception, BOSS_ID, id); }
        }
    }

    /** 自分が設定した結界だけを元の状態へ戻し、他の既定無敵を解除しません。 */
    private void restoreImmunity(Encounter state) {
        if (!state.ownsImmunity) return;
        state.ownsImmunity = false;
        try { mobs.setDamageImmune(state.boss, state.savedImmune || state.boss.template().damageImmune()); }
        catch (RuntimeException exception) { Logger.log(LogId.E_6531, exception, BOSS_ID, state.boss.instanceId()); }
    }

    /** 所有者対応と芽一覧を先に除去し、各destroyの失敗が他の芽の回収を止めないようにします。 */
    private void destroyBuds(Encounter state) {
        List<UUID> ids = List.copyOf(state.buds.keySet());
        state.buds.clear();
        for (UUID id : ids) addOwners.remove(id);
        for (UUID id : ids) {
            try { mobs.destroy(id); }
            catch (RuntimeException exception) { Logger.log(LogId.E_6531, exception, BOSS_ID, id); }
        }
    }

    /** 無攻撃の回復・反撃時間を開始します。全芽撃破だけ専用の反撃Barを表示します。 */
    private void startRecovery(Encounter state, List<Player> players, long tick, long duration, boolean exposure) {
        finishRecovery(state);
        state.recoveryUntil = tick + duration;
        state.recoveryDuration = duration;
        state.nextAt = state.recoveryUntil;
        if (exposure) {
            if (effects != null) effects.apply(state.boss.instanceId(), EXPOSURE_EFFECT, duration, 1.25D, 1.0D, 1.0D);
            state.recoveryBar = Bukkit.createBossBar(PlayerMsgResource.format(PlayerMsgId.P_6749.getId()), BarColor.GREEN,
                BarStyle.SEGMENTED_10);
            syncBar(state.recoveryBar, players);
        }
    }

    /** 反撃Barと自分の一時倍率だけを回収します。 */
    private void finishRecovery(Encounter state) {
        if (state.recoveryBar != null) { closeBar(state.recoveryBar); state.recoveryBar = null; }
        state.recoveryUntil = 0L;
        if (effects != null) effects.clear(state.boss.instanceId(), EXPOSURE_EFFECT);
    }

    /** 確定参加者だけをBarに同期し、退出・死亡したPlayerを表示対象から外します。 */
    private void syncBar(BossBar bar, List<Player> players) {
        Set<UUID> desired = new HashSet<>();
        for (Player player : players) {
            desired.add(player.getUniqueId());
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        }
        for (Player previous : List.copyOf(bar.getPlayers())) if (!desired.contains(previous.getUniqueId())) bar.removePlayer(previous);
    }

    /** 一個のDisplay失敗でも残りの回収を続けます。 */
    private void removeDisplays(List<UUID> ids) {
        for (UUID id : ids) {
            try { Entity entity = Bukkit.getEntity(id); if (entity != null) entity.remove(); }
            catch (RuntimeException exception) { Logger.log(LogId.E_6531, exception, BOSS_ID, id); }
        }
        ids.clear();
    }

    /** Bar回収失敗を記録し、無敵・召喚の回収を継続します。 */
    private void closeBar(BossBar bar) {
        try { bar.removeAll(); }
        catch (RuntimeException exception) { Logger.log(LogId.E_6531, exception, BOSS_ID, bar.getTitle()); }
    }

    /** 日本語の回避案内を一波の番号とともに正本propertiesから解決します。 */
    private static @NotNull String title(Move move, int wave) {
        PlayerMsgId id = move == Move.RINGS ? wave == 0 ? PlayerMsgId.P_6743 : PlayerMsgId.P_6744 : move.title;
        return move == Move.RINGS || move == Move.CYCLE ? PlayerMsgResource.format(id.getId(), wave + 1)
            : PlayerMsgResource.format(id.getId());
    }

    /** 同一段で一回だけ共通戦闘へ無属性攻撃を委譲します。 */
    @FunctionalInterface
    interface HitHandler {
        /** @param boss 攻撃者 @param target 確定参加者 @param ratio 攻撃倍率 */
        void hit(@NotNull MobInstance boss, @NotNull Player target, double ratio);
    }

    private enum Move {
        INTRO(50L, 0.0D, 1, PlayerMsgId.P_6740),
        ROOT_LINE(45L, 0.65D, 1, PlayerMsgId.P_6741),
        FAN(45L, 0.60D, 1, PlayerMsgId.P_6742),
        RINGS(55L, 0.45D, 2, PlayerMsgId.P_6743),
        BLOOM(60L, 0.75D, 1, PlayerMsgId.P_6745),
        SPIRAL(60L, 0.70D, 1, PlayerMsgId.P_6746),
        CYCLE(60L, 0.45D, 3, PlayerMsgId.P_6747);
        private final long warningTicks;
        private final double ratio;
        private final int waves;
        private final PlayerMsgId title;
        Move(long warningTicks, double ratio, int waves, PlayerMsgId title) {
            this.warningTicks = warningTicks; this.ratio = ratio; this.waves = waves; this.title = title;
        }
    }

    private static final class Encounter {
        private final MobInstance boss;
        private final Location center;
        private final boolean previousAction;
        private int requestedPhase = 1;
        private int completedPhase = 1;
        private int linkPhase = 1;
        private int actionIndex;
        private boolean introduced;
        private long nextAt;
        private long linkUntil;
        private boolean savedImmune;
        private boolean ownsImmunity;
        private final Map<UUID, Location> buds = new LinkedHashMap<>();
        private @Nullable BossBar linkBar;
        private @Nullable Cast cast;
        private @Nullable Move resumeMove;
        private int resumeWave;
        private long recoveryUntil;
        private long recoveryDuration;
        private @Nullable BossBar recoveryBar;
        /** 最初の接敵時の会場と行動状態を保存します。 */
        private Encounter(MobInstance boss) {
            this.boss = boss;
            center = boss.spawnLocation().clone();
            previousAction = boss.scriptedAction();
        }
    }

    private static final class Cast {
        private final Move move;
        private int wave;
        private final Location center;
        private final Location mark;
        private final Vector direction;
        private final List<Location> marks;
        private final List<Location> shelters;
        private final BossBar bar;
        private final boolean weak;
        private final List<Location> safeSpots = new ArrayList<>();
        private List<Location> dangerPoints = List.of();
        private List<Location> safePoints = List.of();
        private final List<UUID> displays = new ArrayList<>();
        private final Set<UUID> hit = new HashSet<>();
        private long warningTicks;
        private long impactAt;
        /** 一つの大技が使う固定座標を保持します。 */
        private Cast(Move move, int wave, Location center, Location mark, Vector direction, List<Location> marks,
                     List<Location> shelters, BossBar bar, boolean weak) {
            this.move = move; this.wave = wave; this.center = center.clone(); this.mark = mark.clone();
            this.direction = direction.clone(); this.marks = marks.stream().map(Location::clone).toList();
            this.shelters = shelters.stream().map(Location::clone).toList(); this.bar = bar; this.weak = weak;
        }
    }

    /** 同じworldでのみ音を再生する小さな同期adapterです。 */
    private static final class WorldSound {
        /** @param point 演出位置 @param sound 既存のBukkit音 @param volume 音量 @param pitch 音程 */
        private static void play(Location point, Sound sound, float volume, float pitch) {
            if (point.getWorld() != null) point.getWorld().playSound(point, sound, volume, pitch);
        }
    }
}
