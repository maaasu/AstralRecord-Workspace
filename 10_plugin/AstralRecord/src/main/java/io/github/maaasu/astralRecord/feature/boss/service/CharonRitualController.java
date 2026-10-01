package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** カロンの持続する魂柱と、一度限りのHP境界儀式を同期tickで進行します。 */
final class CharonRitualController {

    private static final long DRAW_INTERVAL = 10L;
    private static final long SUMMON_TICKS = 60L;
    private static final long PILLAR_LIFETIME = 600L;
    private static final long PILLAR_COOLDOWN = 900L;
    private static final long PILLAR_COOLDOWN_REDUCTION_PER_PLAYER = 40L;
    private static final int BASE_PILLAR_COUNT = 3;
    private static final long BEAM_WARNING = 30L;
    private static final long BEAM_INTERVAL = 60L;
    private static final double BEAM_HALF_WIDTH = 0.9D;
    private static final double RIVER_HALF_LENGTH = 14.0D;
    private static final double RIVER_HALF_WIDTH = 2.2D;
    private static final double VERDICT_RADIUS = 24.0D;
    private static final double SAFE_RADIUS = 3.0D;
    private static final long FIRST_WAVE_TICKS = 70L;
    private static final long RECOVERY_TICKS = 60L;
    private static final int MAX_PARTICLE_POINTS = 512;
    private static final Color VIOLET = Color.fromRGB(180, 75, 235);
    private static final Color CYAN = Color.fromRGB(80, 235, 255);

    private final MobService mobService;
    private final ParticleDisplayService particles;
    private final Function<Location, List<Player>> targets;
    private final ToIntFunction<MobInstance> participantCounts;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /**
     * 共通の対象選別・ダメージ経路を再利用するカロン専用の進行役を作成します。
     *
     * @param mobService 無敵状態と位置を同期するMobサービス
     * @param particles 閲覧者ごとの密度を適用する表示サービス
     * @param targets 中心から32ブロック以内の有効な管理対象Playerを解決する関数
     * @param participantCounts 召喚時の確定参加人数または対象パーティー人数を解決する関数
     * @param hitHandler 魔法・無属性のスキルダメージを適用する関数
     */
    CharonRitualController(
        @NotNull MobService mobService,
        @NotNull ParticleDisplayService particles,
        @NotNull Function<Location, List<Player>> targets,
        @NotNull ToIntFunction<MobInstance> participantCounts,
        @NotNull HitHandler hitHandler
    ) {
        this.mobService = mobService;
        this.particles = particles;
        this.targets = targets;
        this.participantCounts = participantCounts;
        this.hitHandler = hitHandler;
    }

    /**
     * 戦闘中のカロンを5tick周期で進行します。通常AI停止中は柱ビームを保留します。
     *
     * @param boss 戦闘・AI実行が有効なカロン
     * @param entity ボスの有効な実体
     * @param tick 共通サービスの経過tick
     * @return 召喚・境界儀式・反撃時間を管理中で、通常行動の予約を止める場合はtrue
     */
    boolean tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, tick + 100L));
        if (state.sequence != null) {
            tickSequence(state, entity, tick);
            return true;
        }
        if (state.summonUntil > 0L) {
            holdPose(state);
            if (tick % DRAW_INTERVAL == 0L) {
                drawSummon(state, tick);
            }
            if (tick >= state.summonUntil) {
                finishPose(state, false);
                state.summonUntil = 0L;
                state.pillarsExpireAt = tick + PILLAR_LIFETIME;
                state.nextBeamAt = tick + 20L;
                sound(state.center, "block.respawn_anchor.charge", 1.2F, 0.65F);
            }
            return true;
        }
        if (state.recoveryUntil > 0L) {
            if (tick < state.recoveryUntil) {
                return true;
            }
            state.recoveryUntil = 0L;
            boss.scriptedAction(false);
        }
        tickPillars(state, entity, tick);
        return false;
    }

    /** 次回の柱召喚時刻に達し、前の柱が残っていないかを判定します。 */
    boolean summonDue(@NotNull UUID bossId, long tick) {
        Encounter state = encounters.get(bossId);
        return state != null && state.pillars.isEmpty() && tick >= state.nextPillarAt;
    }

    /**
     * 人数に応じた数の魂柱を安全な床へ表示し、3秒間だけ浮遊・無敵にします。
     * 1人では3本・45秒間隔とし、追加1人ごとに1本増やして間隔を2秒短縮します。
     *
     * @param boss 発動するカロン。既存予兆と反撃時間は呼び出し元が解除済みであること
     * @param entity ボスの有効な実体
     * @param tick 現在tick
     * @return 必要な地点を確保して召喚を開始した場合はtrue。配置不能時は5秒後へ延期します
     */
    boolean startPillars(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.get(boss.instanceId());
        if (state == null) {
            return false;
        }
        int participantCount = Math.clamp(participantCounts.applyAsInt(boss), 1, PartyService.MAX_MEMBERS);
        int pillarCount = BASE_PILLAR_COUNT + participantCount - 1;
        List<Location> grounds = pillarGrounds(entity.getLocation(), pillarCount);
        if (grounds.size() != pillarCount) {
            state.nextPillarAt = tick + 100L;
            return false;
        }
        state.center = entity.getLocation();
        state.nextPillarAt = tick + PILLAR_COOLDOWN
            - PILLAR_COOLDOWN_REDUCTION_PER_PLAYER * (participantCount - 1);
        state.summonUntil = tick + SUMMON_TICKS;
        state.pillarsExpireAt = 0L;
        state.beamIndex = 0;
        for (Location ground : grounds) {
            state.pillars.add(new Pillar(ground, List.of(
                spawnDisplay(ground, Material.POLISHED_DEEPSLATE, 1.3F, 4.0F, 1.3F),
                spawnDisplay(ground.clone().add(0, 4.0D, 0), Material.CRYING_OBSIDIAN, 1.8F, 0.6F, 1.8F),
                spawnDisplay(ground.clone().add(0, 4.6D, 0), Material.SOUL_LANTERN, 1.2F, 1.2F, 1.2F)
            )));
        }
        beginPose(state, entity);
        sound(state.center, "entity.evoker.prepare_summon", 1.5F, 0.6F);
        return true;
    }

    /**
     * HP60%の大渡航またはHP30%の魂灯裁定を開始します。前の柱とビームは回収します。
     *
     * @param boss 発動するカロン。HP境界の一度限りの管理は呼び出し元が行います
     * @param entity ボスの有効な実体
     * @param phase 2なら大渡航、3なら魂灯裁定
     * @param tick 現在tick
     */
    void startPhase(@NotNull MobInstance boss, @NotNull Entity entity, int phase, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss, tick));
        clearPillars(state);
        state.center = entity.getLocation();
        List<Player> players = targets.apply(state.center);
        Location primary = players.stream().min((left, right) -> Double.compare(
            horizontalSquared(left.getLocation(), state.center), horizontalSquared(right.getLocation(), state.center)
        )).map(Player::getLocation).orElse(state.center);
        Vector facing = primary.toVector().subtract(state.center.toVector()).setY(0.0D);
        if (facing.lengthSquared() < 0.001D) {
            facing = new Vector(0, 0, 1);
        }
        Kind kind = phase == 2 ? Kind.RIVER : Kind.VERDICT;
        List<Location> safeCenters = kind == Kind.VERDICT ? safeGrounds(primary) : List.of();
        Location arena = kind == Kind.VERDICT ? safeCenters.getFirst() : state.center;
        BossBar bar = Bukkit.createBossBar(phaseTitle(kind, 1), BarColor.PURPLE, BarStyle.SEGMENTED_10);
        state.sequence = new Sequence(kind, arena, facing.normalize(), safeCenters, bar, tick, tick + FIRST_WAVE_TICKS);
        if (kind == Kind.RIVER) {
            for (int index = 0; index < 3; index++) {
                Location origin = arena.clone();
                state.sequence.displays.add(spawnDisplay(origin, Material.DARK_OAK_PLANKS, 2.6F, 0.65F, 6.0F));
                state.sequence.displays.add(spawnDisplay(origin.clone().add(0, 0.65D, 0), Material.CYAN_STAINED_GLASS, 2.4F, 1.7F, 0.3F));
                state.sequence.displays.add(spawnDisplay(origin.clone().add(0, 2.35D, 0), Material.SOUL_LANTERN, 1.2F, 1.2F, 1.2F));
            }
        } else {
            for (Location safe : safeCenters) {
                state.sequence.displays.add(spawnDisplay(safe.clone().add(0, 3.0D, 0), Material.SOUL_LANTERN, 1.7F, 1.7F, 1.7F));
            }
            for (int index = 0; index < 8; index++) {
                state.sequence.displays.add(spawnDisplay(arena, Material.CRYING_OBSIDIAN, 0.65F, 1.6F, 0.65F));
            }
        }
        beginPose(state, entity);
        sound(arena, "entity.wither.spawn", 1.3F, kind == Kind.RIVER ? 0.8F : 0.55F);
    }

    /**
     * 離脱・拘束・死亡・アンロード時に表示、BossBar、重力、専用行動、無敵を解除します。
     *
     * @param bossId 回収するカロン個体のID
     */
    void clear(@NotNull UUID bossId) {
        Encounter state = encounters.remove(bossId);
        if (state == null) {
            return;
        }
        clearPillars(state);
        clearSequence(state);
        finishPose(state, false);
        state.boss.scriptedAction(false);
    }

    /** Plugin停止時に全カロン個体の一時状態を回収します。 */
    void clearAll() {
        for (UUID bossId : List.copyOf(encounters.keySet())) {
            clear(bossId);
        }
    }

    /** 柱の寿命と1辺ずつの予兆・発射を処理し、1発につき各Playerへ一度だけ命中判定します。 */
    private void tickPillars(@NotNull Encounter state, @NotNull Entity entity, long tick) {
        if (state.pillars.isEmpty()) {
            return;
        }
        if (tick >= state.pillarsExpireAt || entity.getWorld() != state.center.getWorld()
            || horizontalSquared(entity.getLocation(), state.center) > 32.0D * 32.0D) {
            clearPillars(state);
            return;
        }
        if (state.boss.scriptedAction()) {
            state.beam = null;
            state.nextBeamAt = tick + 40L;
            return;
        }
        if (state.beam == null && tick >= state.nextBeamAt) {
            int pillarCount = state.pillars.size();
            Pillar from = state.pillars.get(state.beamIndex % pillarCount);
            Pillar to = state.pillars.get((state.beamIndex + 1) % pillarCount);
            state.beam = clippedBeam(from.ground, to.ground);
            state.beamImpactAt = tick + BEAM_WARNING;
            state.nextBeamAt = tick + BEAM_INTERVAL;
            state.beamIndex++;
            sound(from.ground, "block.beacon.power_select", 0.9F, 0.7F);
        }
        if (state.beam != null && tick == state.beamImpactAt) {
            for (Player player : targets.apply(state.center)) {
                if (insideBeam(player.getLocation(), state.beam)) {
                    hitHandler.hit(state.boss, player, 0.50D);
                }
            }
            sound(state.beam.from, "entity.warden.sonic_boom", 1.0F, 1.2F);
        }
        if (tick % DRAW_INTERVAL == 0L) {
            List<Location> pillarPoints = new ArrayList<>();
            for (Pillar pillar : state.pillars) {
                ring(pillarPoints, pillar.ground.clone().add(0, 5.5D, 0), 0.9D, 16);
            }
            draw(state.center, pillarPoints, SharedParticleDefinitions.CHARON_SAFE_LIGHT);
            if (state.beam != null) {
                List<Location> beamPoints = new ArrayList<>();
                appendBeam(beamPoints, state.beam, BEAM_HALF_WIDTH);
                draw(state.center, beamPoints, tick < state.beamImpactAt
                    ? SharedParticleDefinitions.CHARON_DANGER : SharedParticleDefinitions.CHARON_BEAM);
            }
        }
        if (state.beam != null && tick >= state.beamImpactAt + 10L) {
            state.beam = null;
        }
    }

    /** 境界儀式のチャージ、段階予兆、3回の発動、終了後の反撃時間を進行します。 */
    private void tickSequence(@NotNull Encounter state, @NotNull Entity entity, long tick) {
        Sequence sequence = state.sequence;
        if (sequence == null) {
            return;
        }
        if (state.pose != null) {
            if (tick - sequence.startTick >= 40L) {
                finishPose(state, true);
            } else {
                holdPose(state);
            }
        }
        List<Player> players = targets.apply(sequence.center);
        sequence.bar.getPlayers().stream().filter(player -> !players.contains(player)).toList().forEach(sequence.bar::removePlayer);
        players.stream().filter(player -> !sequence.bar.getPlayers().contains(player)).forEach(sequence.bar::addPlayer);
        long duration = sequence.step == 0 ? FIRST_WAVE_TICKS : sequence.kind == Kind.RIVER ? 40L : 50L;
        sequence.bar.setProgress(Math.clamp((double) (sequence.impactAt - tick) / duration, 0.0D, 1.0D));
        if (tick >= sequence.impactAt) {
            applyWave(state, sequence, players);
            sequence.step++;
            if (sequence.step == 3) {
                clearSequence(state);
                state.recoveryUntil = tick + RECOVERY_TICKS;
                state.nextPillarAt = state.recoveryUntil + 180L;
                state.boss.scriptedAction(true);
                return;
            }
            sequence.impactAt = tick + (sequence.kind == Kind.RIVER ? 40L : 50L);
            sequence.bar.setTitle(phaseTitle(sequence.kind, sequence.step + 1));
            sound(sequence.center, "block.bell.resonate", 1.2F, 0.65F + sequence.step * 0.2F);
        }
        if (tick % DRAW_INTERVAL == 0L) {
            drawSequence(sequence, tick);
            animateSequence(sequence, tick);
        }
        entity.setVelocity(new Vector());
    }

    /** 現在の冥河または安全円を除く魂灯裁定へ、表示と同じ境界で一度だけ攻撃します。 */
    private void applyWave(@NotNull Encounter state, @NotNull Sequence sequence, @NotNull List<Player> players) {
        List<Location> impactPoints = new ArrayList<>();
        if (sequence.kind == Kind.RIVER) {
            Beam river = riverBeam(sequence, sequence.step);
            for (Player player : players) {
                if (insideBeam(player.getLocation(), river, RIVER_HALF_WIDTH)) {
                    hitHandler.hit(state.boss, player, 0.85D);
                }
            }
            appendBeam(impactPoints, river, RIVER_HALF_WIDTH);
            draw(sequence.center, impactPoints, SharedParticleDefinitions.CHARON_BEAM);
        } else {
            Location safe = sequence.safeCenters.get(sequence.step);
            for (Player player : players) {
                Location point = player.getLocation();
                if (horizontalSquared(point, sequence.center) <= VERDICT_RADIUS * VERDICT_RADIUS
                    && horizontalSquared(point, safe) > SAFE_RADIUS * SAFE_RADIUS) {
                    hitHandler.hit(state.boss, player, 1.0D + sequence.step * 0.1D);
                }
            }
            for (double x = -VERDICT_RADIUS; x <= VERDICT_RADIUS; x += 3.0D) {
                for (double z = -VERDICT_RADIUS; z <= VERDICT_RADIUS; z += 3.0D) {
                    Location point = sequence.center.clone().add(x, 0.3D, z);
                    if (x * x + z * z <= VERDICT_RADIUS * VERDICT_RADIUS
                        && horizontalSquared(point, safe) > SAFE_RADIUS * SAFE_RADIUS) {
                        impactPoints.add(point);
                    }
                }
            }
            draw(sequence.center, impactPoints, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION);
        }
        sound(sequence.center, "entity.generic.explode", 1.4F, 0.7F);
    }

    /** 冥河3方向の次の1方向、または次の安全灯を強調し、切替先の円も薄く表示します。 */
    private void drawSequence(@NotNull Sequence sequence, long tick) {
        List<Location> danger = new ArrayList<>();
        List<Location> safe = new ArrayList<>();
        if (sequence.kind == Kind.RIVER) {
            for (int index = sequence.step; index < 3; index++) {
                appendBeam(index == sequence.step ? danger : safe, riverBeam(sequence, index), RIVER_HALF_WIDTH);
            }
        } else {
            ring(danger, sequence.center, VERDICT_RADIUS, 96);
            for (int index = 0; index < 3; index++) {
                List<Location> points = index == sequence.step ? safe : danger;
                for (int layer = 0; layer < 3; layer++) {
                    ring(points, sequence.safeCenters.get(index).clone().add(0, 0.2D + layer, 0), SAFE_RADIUS, 32);
                }
            }
        }
        List<Location> spiral = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            double angle = tick * 0.07D + index * Math.PI / 10.0D;
            double radius = 2.0D + index * 0.1D;
            spiral.add(sequence.center.clone().add(Math.cos(angle) * radius, 1.0D + index * 0.1D, Math.sin(angle) * radius));
        }
        draw(sequence.center, danger, SharedParticleDefinitions.CHARON_DANGER);
        draw(sequence.center, safe, sequence.kind == Kind.RIVER
            ? SharedParticleDefinitions.BOSS_MECHANIC_PORTAL : SharedParticleDefinitions.CHARON_SAFE_LIGHT);
        draw(sequence.center, spiral, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE);
    }

    /** 大渡航の舟3隻、または安全灯3個と外周の石片8個を上限付きで動かします。 */
    private void animateSequence(@NotNull Sequence sequence, long tick) {
        for (int index = 0; index < sequence.displays.size(); index++) {
            Entity display = Bukkit.getEntity(sequence.displays.get(index));
            if (!(display instanceof BlockDisplay block)) {
                continue;
            }
            Location destination;
            if (sequence.kind == Kind.RIVER) {
                int boat = index / 3;
                Beam river = riverBeam(sequence, boat);
                double progress = boat == sequence.step
                    ? Math.clamp(1.0D - (sequence.impactAt - tick) / 20.0D, 0.0D, 1.0D) : 0.0D;
                destination = interpolate(river, progress);
                destination.add(0, switch (index % 3) { case 1 -> 0.65D; case 2 -> 2.35D; default -> 0.0D; }, 0);
                Vector direction = river.to.toVector().subtract(river.from.toVector());
                destination.setYaw((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())));
                block.setGlowColorOverride(boat == sequence.step ? VIOLET : CYAN);
            } else if (index < 3) {
                destination = sequence.safeCenters.get(index).clone().add(0, 3.0D + Math.sin(tick * 0.08D) * 0.25D, 0);
                block.setGlowColorOverride(index == sequence.step ? CYAN : VIOLET);
            } else {
                double angle = tick * 0.04D + (index - 3) * Math.PI / 4.0D;
                destination = sequence.center.clone().add(Math.cos(angle) * 8.0D, 3.0D, Math.sin(angle) * 8.0D);
            }
            block.teleport(destination);
        }
    }

    /** 召喚中の柱を地面から立ち上げ、浮遊するカロンへ魂炎を集めます。 */
    private void drawSummon(@NotNull Encounter state, long tick) {
        double progress = Math.clamp(1.0D - (state.summonUntil - tick) / (double) SUMMON_TICKS, 0.0D, 1.0D);
        List<Location> points = new ArrayList<>();
        for (Pillar pillar : state.pillars) {
            ring(points, pillar.ground, 2.0D, 24);
            for (int index = 0; index < pillar.displays.size(); index++) {
                Entity display = Bukkit.getEntity(pillar.displays.get(index));
                if (display != null) {
                    double height = switch (index) { case 1 -> 4.0D; case 2 -> 4.6D; default -> 0.0D; };
                    display.teleport(pillar.ground.clone().add(0, height - (1.0D - progress) * 4.0D, 0));
                }
            }
        }
        ring(points, state.center.clone().add(0, 1.0D + progress * 3.0D, 0), 3.0D - progress * 2.0D, 32);
        draw(state.center, points, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE);
    }

    /** 元位置と重力を保存し、天井までの余裕がある分だけ上昇して無敵化します。 */
    private void beginPose(@NotNull Encounter state, @NotNull Entity entity) {
        Location origin = entity.getLocation();
        Location hover = origin.clone();
        for (int height = 1; height <= 4; height++) {
            Location probe = origin.clone().add(0, height + 1.0D, 0);
            if (probe.getY() >= entity.getWorld().getMaxHeight() || !probe.getBlock().isPassable()) {
                break;
            }
            hover = origin.clone().add(0, height, 0);
        }
        state.pose = new Pose(entity, origin, hover, entity.hasGravity(), state.boss.damageImmune());
        entity.setGravity(false);
        state.boss.scriptedAction(true);
        mobService.setDamageImmune(state.boss, true);
        holdPose(state);
    }

    /** 浮遊中だけ実体の位置と速度を固定します。 */
    private void holdPose(@NotNull Encounter state) {
        if (state.pose != null && state.pose.entity.isValid()) {
            mobService.resetPosition(state.boss, state.pose.hover);
            state.pose.entity.setVelocity(new Vector());
        }
    }

    /** 浮遊の終了・中断時に元位置、重力、開始前の無敵状態を復元します。 */
    private void finishPose(@NotNull Encounter state, boolean keepScripted) {
        Pose pose = state.pose;
        if (pose == null) {
            return;
        }
        state.pose = null;
        if (pose.entity.isValid()) {
            pose.entity.setGravity(pose.gravity);
            if (!pose.entity.isDead() && state.boss.currentHealth() > 0.0D
                && pose.entity.getWorld() == pose.origin.getWorld()
                && mobService.getInstance(state.boss.instanceId()) == state.boss) {
                mobService.resetPosition(state.boss, pose.origin);
                pose.entity.setVelocity(new Vector());
            }
        }
        mobService.setDamageImmune(state.boss, pose.damageImmune || state.boss.template().damageImmune());
        state.boss.scriptedAction(keepScripted);
    }

    /**
     * 未ロードchunkと頭上が詰まった床を除き、互いに6ブロック以上離れた柱の床を選びます。
     *
     * @param center ボスの現在位置
     * @param pillarCount 参加人数から算出した3〜8本の柱数
     * @return 必要な全地点。確保できない場合は空リスト
     */
    private static @NotNull List<Location> pillarGrounds(@NotNull Location center, int pillarCount) {
        List<Location> result = new ArrayList<>();
        double startAngle = Math.toRadians(center.getYaw());
        for (int index = 0; index < pillarCount; index++) {
            Location chosen = null;
            for (double radius : new double[] {16.0D, 20.0D, 24.0D, 10.0D}) {
                for (double offset : new double[] {0.0D, 0.25D, -0.25D}) {
                    double angle = startAngle + index * Math.PI * 2.0D / pillarCount + offset;
                    Location ground = groundAt(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), 6);
                    if (ground != null && result.stream().allMatch(other -> horizontalSquared(other, ground) >= 36.0D)) {
                        chosen = ground;
                        break;
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            if (chosen == null) {
                return List.of();
            }
            result.add(chosen);
        }
        return result;
    }

    /** 最初の安全灯を対象の足元へ置き、次の灯は6ブロック先の歩ける床へ順に配置します。 */
    private static @NotNull List<Location> safeGrounds(@NotNull Location primary) {
        // 頭上に別階の床があっても、対象が立つ面より上の床を初期灯にしない。
        Location first = groundAt(primary, 3, primary.getY() - 1.5D, primary.getY() + 0.05D);
        first = first == null ? primary.clone() : first;
        List<Location> result = new ArrayList<>(List.of(first));
        for (int index = 1; index < 3; index++) {
            Location previous = result.getLast();
            Location chosen = previous;
            for (int attempt = 0; attempt < 8; attempt++) {
                double angle = Math.PI * index * 2.0D / 3.0D + attempt * Math.PI / 4.0D;
                Location ground = groundAt(
                    previous.clone().add(Math.cos(angle) * 6.0D, 0, Math.sin(angle) * 6.0D),
                    3, previous.getY() - 1.0D, previous.getY() + 1.0D
                );
                if (ground == null || Math.abs(ground.getY() - previous.getY()) > 1.0D
                    || horizontalSquared(ground, first) > 144.0D
                    || result.stream().anyMatch(other -> horizontalSquared(other, ground) < 25.0D)) {
                    continue;
                }
                Vector path = ground.toVector().subtract(previous.toVector());
                RayTraceResult collision = ground.getWorld().rayTraceBlocks(
                    previous.clone().add(0, 0.9D, 0), path, path.length(), FluidCollisionMode.NEVER, true
                );
                if (collision == null && walkablePath(previous, ground)) {
                    chosen = ground;
                    break;
                }
            }
            result.add(chosen.clone());
        }
        return List.copyOf(result);
    }

    /**
     * 灯の間を0.5ブロック以下の間隔で走査し、プレイヤー幅の床と頭上空間を保証します。
     *
     * @param from 現在の安全灯
     * @param to 次の安全灯の候補
     * @return 途中の穴、液体、危険床、高さ1ブロックを超す段差、狭い通路がない場合はtrue
     */
    private static boolean walkablePath(@NotNull Location from, @NotNull Location to) {
        Vector direction = to.toVector().subtract(from.toVector()).setY(0.0D);
        int samples = Math.clamp((int) Math.ceil(direction.length() / 0.5D), 1, 16);
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize().multiply(0.35D);
        double lastHeight = from.getY();
        for (int index = 0; index <= samples; index++) {
            double fraction = (double) index / samples;
            Location probe = from.clone().add(to.toVector().subtract(from.toVector()).multiply(fraction));
            Location middle = groundAt(
                probe, 3, Math.max(probe.getY() - 1.0D, lastHeight - 1.0D),
                Math.min(probe.getY() + 1.0D, lastHeight + 1.0D)
            );
            if (middle == null || Math.abs(middle.getY() - lastHeight) > 1.0D
                || Math.abs(middle.getY() - probe.getY()) > 1.0D) {
                return false;
            }
            for (double offset : new double[] {-1.0D, 1.0D}) {
                Location edge = groundAt(
                    probe.clone().add(side.clone().multiply(offset)), 3,
                    middle.getY() - 1.0D, middle.getY() + 1.0D
                );
                if (edge == null || Math.abs(edge.getY() - middle.getY()) > 1.0D) {
                    return false;
                }
            }
            lastHeight = middle.getY();
        }
        return true;
    }

    /** 入力Y付近だけを有限走査し、固い床と指定高さの空間を持つ地点を返します。 */
    private static @Nullable Location groundAt(@NotNull Location probe, int clearance) {
        return groundAt(probe, clearance, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    /**
     * 連続した歩行面の高さ範囲内で、床と必要な頭上空間を持つ地点を有限走査します。
     *
     * @param probe 探索する水平地点と基準高さ
     * @param clearance 床上に確保する空間の高さ
     * @param minSurfaceHeight 採用できる床の上面高さの下限
     * @param maxSurfaceHeight 採用できる床の上面高さの上限
     * @return 対象の歩行面に連続する床。別階しか見つからない場合はnull
     */
    private static @Nullable Location groundAt(
        @NotNull Location probe, int clearance, double minSurfaceHeight, double maxSurfaceHeight
    ) {
        World world = probe.getWorld();
        if (world == null || !world.isChunkLoaded(probe.getBlockX() >> 4, probe.getBlockZ() >> 4)) {
            return null;
        }
        int top = Math.min(world.getMaxHeight() - clearance - 1, probe.getBlockY() + 3);
        int bottom = Math.max(world.getMinHeight(), probe.getBlockY() - 6);
        for (int y = top; y >= bottom; y--) {
            Block floor = world.getBlockAt(probe.getBlockX(), y, probe.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid()
                || dangerousGround(floor.getType())) {
                continue;
            }
            double surfaceHeight = floor.getBoundingBox().getMaxY();
            if (surfaceHeight < minSurfaceHeight || surfaceHeight > maxSurfaceHeight) {
                continue;
            }
            boolean clear = true;
            for (int height = 1; height <= clearance; height++) {
                Block air = world.getBlockAt(probe.getBlockX(), y + height, probe.getBlockZ());
                clear &= air.isPassable() && !air.isLiquid() && !dangerousGround(air.getType());
            }
            if (clear) {
                return new Location(world, probe.getBlockX() + 0.5D, surfaceHeight, probe.getBlockZ() + 0.5D);
            }
        }
        return null;
    }

    /** 灯までの歩行で接触ダメージを発生させる床・空間を除外します。 */
    private static boolean dangerousGround(@NotNull Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CACTUS
            || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
            || material == Material.FIRE || material == Material.SOUL_FIRE
            || material == Material.SWEET_BERRY_BUSH || material == Material.WITHER_ROSE
            || material == Material.POWDER_SNOW;
    }

    /** 柱間の線分を壁までに切り詰め、予兆と命中判定で同じ端点を使用します。 */
    private static @NotNull Beam clippedBeam(@NotNull Location from, @NotNull Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        RayTraceResult wall = from.getWorld().rayTraceBlocks(
            from.clone().add(0, 1.3D, 0), delta, distance, FluidCollisionMode.NEVER, true
        );
        Location end = wall == null ? to.clone() : wall.getHitPosition().toLocation(from.getWorld()).subtract(0, 1.3D, 0);
        return new Beam(from.clone(), end);
    }

    /** 大渡航の3本の異なる河道を、中心から前後14ブロックへ固定します。 */
    private static @NotNull Beam riverBeam(@NotNull Sequence sequence, int index) {
        Vector direction = sequence.direction.clone().rotateAroundY(index * Math.PI / 3.0D);
        return new Beam(
            sequence.center.clone().subtract(direction.clone().multiply(RIVER_HALF_LENGTH)),
            sequence.center.clone().add(direction.clone().multiply(RIVER_HALF_LENGTH))
        );
    }

    /** 通常の柱ビーム幅で、床から3ブロックの帯にいるかを判定します。 */
    private static boolean insideBeam(@NotNull Location point, @NotNull Beam beam) {
        return insideBeam(point, beam, BEAM_HALF_WIDTH);
    }

    /** 線分への水平射影と床の補間高さから、表示した幅・高さの帯だけを命中範囲にします。 */
    private static boolean insideBeam(@NotNull Location point, @NotNull Beam beam, double halfWidth) {
        double dx = beam.to.getX() - beam.from.getX();
        double dz = beam.to.getZ() - beam.from.getZ();
        double lengthSquared = dx * dx + dz * dz;
        if (point.getWorld() != beam.from.getWorld() || lengthSquared < 0.001D) {
            return false;
        }
        double t = ((point.getX() - beam.from.getX()) * dx + (point.getZ() - beam.from.getZ()) * dz) / lengthSquared;
        if (t < 0.0D || t > 1.0D) {
            return false;
        }
        Location nearest = interpolate(beam, t);
        return horizontalSquared(point, nearest) <= halfWidth * halfWidth
            && point.getY() >= nearest.getY() - 0.5D && point.getY() <= nearest.getY() + 3.0D;
    }

    /** 高さを含めた線分上の地点を返します。 */
    private static @NotNull Location interpolate(@NotNull Beam beam, double t) {
        return beam.from.clone().add(beam.to.toVector().subtract(beam.from.toVector()).multiply(t));
    }

    /** ビーム中央と両側の境界を、1ブロック以下の間隔・有限の点数で描きます。 */
    private static void appendBeam(@NotNull List<Location> points, @NotNull Beam beam, double halfWidth) {
        Vector direction = beam.to.toVector().subtract(beam.from.toVector()).setY(0);
        int count = Math.clamp((int) Math.ceil(direction.length()), 1, 52);
        if (direction.lengthSquared() < 0.001D) {
            return;
        }
        Vector side = new Vector(-direction.getZ(), 0, direction.getX()).normalize().multiply(halfWidth);
        for (int index = 0; index <= count; index++) {
            Location center = interpolate(beam, (double) index / count);
            points.add(center.clone().add(0, 1.3D, 0));
            points.add(center.clone().add(side).add(0, 0.15D, 0));
            points.add(center.clone().subtract(side).add(0, 0.15D, 0));
        }
    }

    /** 同一worldの水平距離を計算します。 */
    private static double horizontalSquared(@NotNull Location left, @NotNull Location right) {
        double dx = left.getX() - right.getX();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    /** 有限点数の円周を、地面より少し上へ追加します。 */
    private static void ring(@NotNull List<Location> points, @NotNull Location center, double radius, int count) {
        for (int index = 0; index < count; index++) {
            double angle = index * Math.PI * 2.0D / count;
            points.add(center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius));
        }
    }

    /** 表示点数を制限し、閲覧者と密度の解決をバッチごとに一度だけ行います。 */
    private void draw(@NotNull Location center, @NotNull List<Location> points, @NotNull SharedParticleDefinition definition) {
        particles.spawnForNearbyViewers(center, points.subList(0, Math.min(points.size(), MAX_PARTICLE_POINTS)), definition);
    }

    /** 地形に触れない、明るく発光する一時BlockDisplayを生成します。 */
    private static @NotNull UUID spawnDisplay(@NotNull Location location, @NotNull Material material, float x, float y, float z) {
        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, block -> {
            block.setPersistent(false);
            block.setInvulnerable(true);
            block.setGravity(false);
            block.setBlock(material.createBlockData());
            block.setBrightness(new Display.Brightness(15, 15));
            block.setGlowing(true);
            block.setGlowColorOverride(VIOLET);
            block.setViewRange(1.0F);
            block.setDisplayWidth(Math.max(x, z));
            block.setDisplayHeight(y);
            block.setTeleportDuration((int) DRAW_INTERVAL);
            block.setTransformation(new Transformation(new Vector3f(-x / 2, 0, -z / 2), new Quaternionf(), new Vector3f(x, y, z), new Quaternionf()));
        });
        return display.getUniqueId();
    }

    /** 柱と未発射ビームを回収します。 */
    private static void clearPillars(@NotNull Encounter state) {
        for (Pillar pillar : state.pillars) {
            removeDisplays(pillar.displays);
        }
        state.pillars.clear();
        state.beam = null;
    }

    /** 儀式の舟・灯・石片と専用BossBarを回収します。 */
    private static void clearSequence(@NotNull Encounter state) {
        if (state.sequence != null) {
            removeDisplays(state.sequence.displays);
            state.sequence.bar.removeAll();
            state.sequence.bar.setVisible(false);
            state.sequence = null;
        }
    }

    /** 所有する一時表示を重複回収可能な形で削除します。 */
    private static void removeDisplays(@NotNull List<UUID> ids) {
        for (UUID id : ids) {
            Entity display = Bukkit.getEntity(id);
            if (display != null) {
                display.remove();
            }
        }
    }

    /** 儀式名と攻略指示をplayer.propertiesから解決します。 */
    private static @NotNull String phaseTitle(@NotNull Kind kind, int wave) {
        return PlayerMsgResource.format((kind == Kind.RIVER ? PlayerMsgId.P_6540 : PlayerMsgId.P_6541).getId(), wave);
    }

    /** 同じworldの近傍へ開始・切替・発動音を鳴らします。 */
    private static void sound(@NotNull Location center, @NotNull String name, float volume, float pitch) {
        if (center.getWorld() != null) {
            center.getWorld().playSound(center, name, volume, pitch);
        }
    }

    /** 既存の共通ダメージ計算へカロンの魔法攻撃を渡す契約です。 */
    @FunctionalInterface
    interface HitHandler {
        /** 指定倍率のスキルダメージを管理対象Playerへ一度だけ適用します。 */
        void hit(@NotNull MobInstance boss, @NotNull Player player, double ratio);
    }

    private enum Kind { RIVER, VERDICT }

    private static final class Encounter {
        private final MobInstance boss;
        private final List<Pillar> pillars = new ArrayList<>();
        private Location center;
        private long nextPillarAt;
        private long summonUntil;
        private long pillarsExpireAt;
        private long nextBeamAt;
        private long beamImpactAt;
        private int beamIndex;
        private long recoveryUntil;
        private @Nullable Beam beam;
        private @Nullable Pose pose;
        private @Nullable Sequence sequence;

        private Encounter(@NotNull MobInstance boss, long nextPillarAt) {
            this.boss = boss;
            this.center = boss.currentLocation().clone();
            this.nextPillarAt = nextPillarAt;
        }
    }

    private static final class Sequence {
        private final Kind kind;
        private final Location center;
        private final Vector direction;
        private final List<Location> safeCenters;
        private final BossBar bar;
        private final List<UUID> displays = new ArrayList<>();
        private final long startTick;
        private long impactAt;
        private int step;

        private Sequence(Kind kind, Location center, Vector direction, List<Location> safeCenters, BossBar bar, long startTick, long impactAt) {
            this.kind = kind;
            this.center = center.clone();
            this.direction = direction.clone();
            this.safeCenters = safeCenters;
            this.bar = bar;
            this.startTick = startTick;
            this.impactAt = impactAt;
        }
    }

    private record Pillar(@NotNull Location ground, @NotNull List<UUID> displays) {}
    private record Beam(@NotNull Location from, @NotNull Location to) {}
    private record Pose(@NotNull Entity entity, @NotNull Location origin, @NotNull Location hover, boolean gravity, boolean damageImmune) {}
}
