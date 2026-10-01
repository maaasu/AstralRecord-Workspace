package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** エンプーサの単一詠唱、三波儀式、予兆と反撃時間を同期 tick で管理します。 */
final class EmpusaSpellController {

    private static final double RANGE = 32.0D;
    private static final double MAX_HEIGHT_DELTA = 2.5D;
    private static final long RECOVERY_TICKS = 20L;
    private static final long RITUAL_RECOVERY_TICKS = 65L;
    private static final long NORMAL_INTERVAL_TICKS = 20L;
    private static final long RITUAL_INTERVAL_TICKS = 25L;
    private static final long RITUAL_FIRST_WARNING_TICKS = 60L;
    private static final long RITUAL_NEXT_WARNING_TICKS = 35L;
    private static final int MAX_DRAW_POINTS = 192;

    private final ParticleDisplayService particles;
    private final Function<Location, List<Player>> targets;
    private final HitHandler hitHandler;
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    /**
     * エンプーサ専用の同期進行役を構築します。
     *
     * @param particles 閲覧者の設定を適用する共通表示サービス
     * @param targets 中心から32ブロック以内の有効なプレイヤーを返す関数
     * @param hitHandler 共通DamageServiceへ魔法属性と倍率を渡す関数
     */
    EmpusaSpellController(
        @NotNull ParticleDisplayService particles,
        @NotNull Function<Location, List<Player>> targets,
        @NotNull HitHandler hitHandler
    ) {
        this.particles = particles;
        this.targets = targets;
        this.hitHandler = hitHandler;
    }

    /**
     * 追跡中のエンプーサを進め、同一個体で詠唱を一つだけ保持します。
     *
     * @param boss AIを実行できる生存個体
     * @param entity 同一worldで有効な実体
     * @param tick BossMechanicServiceの共通tick
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        Encounter state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Encounter(boss));
        if (state.cast != null) {
            if (nearestTarget(entity.getLocation()) == null
                || entity.getWorld() != state.cast.origin.getWorld()
                || horizontalSquared(entity.getLocation(), state.cast.origin) > RANGE * RANGE) {
                cancelCast(boss.instanceId());
                return;
            }
            try {
                advanceCast(state, entity, tick);
            } catch (RuntimeException exception) {
                Cast failed = state.cast;
                if (failed != null && failed.spell.ritual && tick >= failed.impactAt) {
                    state.pendingRitualWave = Math.max(state.pendingRitualWave, failed.wave + 1);
                    if (state.pendingRitualWave >= 3) {
                        state.completedPhase++;
                        state.pendingRitualWave = 0;
                        state.actionIndex = 0;
                    }
                }
                Logger.log(LogId.E_6506, exception,
                    failed == null ? "unknown" : failed.spell.name(), entity.getWorld().getName());
                cancelCast(boss.instanceId());
                state.nextAt = tick + 20L;
            }
            return;
        }
        if (tick < state.nextAt || boss.scriptedAction()) {
            return;
        }
        Player target = nearestTarget(entity.getLocation());
        if (target == null || !clearSight(entity.getLocation(), target.getLocation())) {
            state.nextAt = tick + 20L;
            return;
        }
        if (!state.introduced) {
            startCast(state, entity, target, Spell.INTRO, tick, 0);
            return;
        }
        int observedPhase = boss.maxHealth() > 0.0D
            ? (boss.currentHealth() <= boss.maxHealth() * 0.30D ? 3
                : boss.currentHealth() <= boss.maxHealth() * 0.65D ? 2 : 1)
            : 1;
        if (state.pendingRitualWave > 0 || observedPhase > state.completedPhase) {
            startCast(state, entity, target, state.completedPhase == 1 ? Spell.BRONZE_RITE : Spell.PHANTOM_RITE,
                tick, state.pendingRitualWave);
            return;
        }
        Spell[] rotation = state.completedPhase == 1 ? Spell.FIRST_ROTATION
            : state.completedPhase == 2 ? Spell.SECOND_ROTATION : Spell.THIRD_ROTATION;
        if (startCast(state, entity, target, rotation[Math.floorMod(state.actionIndex, rotation.length)], tick, 0)) {
            state.actionIndex++;
        } else {
            state.nextAt = tick + 20L;
        }
    }

    /**
     * CC・拘束・標的喪失時に現在の詠唱とBossBarを解除します。
     * 完了済みの閾値と儀式波を記憶し、未発動波だけを復帰後に完全に再予兆します。
     *
     * @param bossId 中断する個体ID
     */
    void cancelCast(@NotNull UUID bossId) {
        Encounter state = encounters.get(bossId);
        if (state == null || state.cast == null) {
            return;
        }
        state.cast.bar.removeAll();
        removeDisplays(state.cast.displays);
        state.cast = null;
        state.boss.scriptedAction(false);
        state.nextAt = 0L;
    }

    /**
     * 死亡・Mob破棄・リセット時に個体の全状態を回収します。
     *
     * @param bossId 回収する個体ID
     */
    void clear(@NotNull UUID bossId) {
        cancelCast(bossId);
        encounters.remove(bossId);
    }

    /** Plugin停止時に全個体の詠唱、BossBarと行動ロックを解放します。 */
    void clearAll() {
        for (UUID bossId : List.copyOf(encounters.keySet())) {
            clear(bossId);
        }
    }

    /**
     * Mob一覧から消えた個体を回収します。
     *
     * @param liveIds 今回の同期走査で有効だったエンプーサの個体ID
     */
    void retain(@NotNull Set<UUID> liveIds) {
        for (UUID bossId : List.copyOf(encounters.keySet())) {
            if (!liveIds.contains(bossId)) {
                clear(bossId);
            }
        }
    }

    /** 現在波を描き、発動時に一度だけ命中判定して次波または反撃時間へ移ります。 */
    private void advanceCast(@NotNull Encounter state, @NotNull Entity entity, long tick) {
        Cast cast = state.cast;
        if (cast == null) {
            return;
        }
        try {
            updateBar(cast, tick);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6504, exception, cast.spell.name(), cast.origin.getWorld().getName());
            cancelCast(state.boss.instanceId());
            state.nextAt = tick + 20L;
            return;
        }
        if (tick < cast.impactAt) {
            try {
                draw(cast, entity.getLocation(), tick);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6504, exception, cast.spell.name(), cast.origin.getWorld().getName());
                cancelCast(state.boss.instanceId());
                state.nextAt = tick + 20L;
            }
            return;
        }
        if (cast.spell.ritual) {
            // 発動後の例外で同じ波を再実行しないよう、命中処理より先にcheckpointを進める。
            state.pendingRitualWave = cast.wave + 1;
        }
        if (cast.spell != Spell.INTRO) {
            impact(state, cast);
        }
        if (cast.spell.ritual && cast.wave < 2) {
            Player target = nearestTarget(entity.getLocation());
            boolean advanced = false;
            try {
                advanced = target != null && startWave(cast, entity, target, tick, cast.wave + 1);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6504, exception, cast.spell.name(), cast.origin.getWorld().getName());
            }
            if (advanced) {
                sound(cast.origin, "block.bell.resonate", 1.1F, 0.65F + 0.16F * cast.wave);
                return;
            }
            // 配置不能時は未完了として中断し、再挑戦時に完全な予兆から始める。
            cancelCast(state.boss.instanceId());
            state.nextAt = tick + 20L;
            return;
        }
        cast.bar.removeAll();
        removeDisplays(cast.displays);
        state.cast = null;
        if (cast.spell == Spell.INTRO) {
            state.introduced = true;
        } else if (cast.spell.ritual) {
            state.completedPhase++;
            state.pendingRitualWave = 0;
            state.actionIndex = 0;
        }
        state.nextAt = tick + (cast.spell.ritual ? RITUAL_RECOVERY_TICKS + RITUAL_INTERVAL_TICKS
            : RECOVERY_TICKS + NORMAL_INTERVAL_TICKS);
        state.boss.scriptedAction(false);
    }

    /** 対象座標を固定し、詠唱中に命中域を追尾させずに新規波を開始します。 */
    private boolean startCast(
        @NotNull Encounter state,
        @NotNull Entity entity,
        @NotNull Player target,
        @NotNull Spell spell,
        long tick,
        int wave
    ) {
        BossBar bar = Bukkit.createBossBar(title(spell, wave), spell.ritual ? BarColor.PURPLE : BarColor.RED,
            BarStyle.SEGMENTED_10);
        Cast cast = new Cast(spell, entity.getLocation().clone(), bar);
        state.cast = cast;
        try {
            if (!startWave(cast, entity, target, tick, wave)) {
                cancelCast(state.boss.instanceId());
                state.nextAt = tick + 20L;
                return false;
            }
            state.boss.scriptedAction(true);
            sound(cast.origin, spell == Spell.INTRO ? "entity.witch.celebrate"
                : spell.ritual ? "entity.evoker.prepare_summon" : "entity.witch.throw", 1.15F,
                spell.ritual ? 0.65F : 0.9F);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6504, exception, spell.name(), cast.origin.getWorld().getName());
            cancelCast(state.boss.instanceId());
            state.nextAt = tick + 20L;
            return false;
        }
    }

    /** 波ごとに正確な当たり判定と同じ座標の予兆点を確定します。 */
    private boolean startWave(@NotNull Cast cast, @NotNull Entity entity, @NotNull Player target, long tick, int wave) {
        Location bossLocation = entity.getLocation();
        Location targetLocation = target.getLocation();
        if (!validCenter(targetLocation) || !clearSight(bossLocation, targetLocation)) {
            return false;
        }
        Location groundTarget = groundNear(targetLocation);
        Location groundBoss = groundNear(bossLocation);
        if (groundTarget == null || groundBoss == null) return false;
        Zone zone = zoneFor(cast.spell, wave, groundBoss, groundTarget);
        Location safe = zone == null || cast.spell == Spell.INTRO ? null : reachableSafeSpot(zone, groundTarget);
        if (zone == null || !zone.fullyLoaded() || zone.points.isEmpty()
            || (cast.spell != Spell.INTRO && safe == null)) {
            return false;
        }
        removeDisplays(cast.displays);
        cast.displays.clear();
        cast.zone = zone;
        cast.safeSpot = safe;
        cast.hitPlayers.clear();
        cast.wave = wave;
        cast.warningAt = tick;
        cast.impactAt = tick + (cast.spell == Spell.INTRO ? 40L
            : cast.spell.ritual ? (wave == 0 ? RITUAL_FIRST_WARNING_TICKS : RITUAL_NEXT_WARNING_TICKS)
            : cast.spell.warningTicks);
        cast.bar.setTitle(title(cast.spell, wave));
        updateBar(cast, tick);
        try {
            spawnDisplays(cast, bossLocation);
        } catch (RuntimeException exception) {
            removeDisplays(cast.displays);
            cast.displays.clear();
            Logger.log(LogId.E_6504, exception, cast.spell.name(), cast.origin.getWorld().getName());
            return false;
        }
        sound(zone.center, "block.amethyst_block.chime", 0.8F, 0.9F + wave * 0.15F);
        return true;
    }

    /** 命中判定用の形状を位置・向き・幅まで固定して生成します。 */
    private static @Nullable Zone zoneFor(
        @NotNull Spell spell,
        int wave,
        @NotNull Location boss,
        @NotNull Location target
    ) {
        Vector direction = target.toVector().subtract(boss.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize();
        Location targetCenter = target.clone().add(0, 0.12D, 0);
        Location bossCenter = boss.clone().add(0, 0.12D, 0);
        return switch (spell) {
            case INTRO -> new Zone(Shape.CIRCLE, bossCenter, direction, 2.8D, 0.0D);
            case FIRE_CIRCLE -> new Zone(Shape.CIRCLE, targetCenter, direction, 3.2D, 0.0D);
            case ICE_ANNULUS -> new Zone(Shape.ANNULUS, targetCenter, direction, 6.0D, 2.5D);
            case STORM_LINE -> new Zone(Shape.LINE, bossCenter, direction, 16.0D, 1.35D);
            case HEX_FAN -> new Zone(Shape.FAN, bossCenter, direction, 9.0D, Math.toRadians(32.0D));
            case BRONZE_CROSS -> new Zone(Shape.CROSS, targetCenter, direction, 9.0D, 1.2D);
            case BRONZE_RITE -> switch (wave) {
                case 0 -> new Zone(Shape.CIRCLE, targetCenter, direction, 3.2D, 0.0D);
                case 1 -> new Zone(Shape.ANNULUS, targetCenter, direction, 6.0D, 2.5D);
                default -> new Zone(Shape.CROSS, targetCenter, direction, 7.0D, 1.2D);
            };
            case PHANTOM_RITE -> switch (wave) {
                case 0 -> new Zone(Shape.LINE, bossCenter, direction, 16.0D, 1.35D);
                case 1 -> new Zone(Shape.CIRCLE, targetCenter, direction, 3.2D, 0.0D);
                default -> new Zone(Shape.FAN, bossCenter, direction, 9.0D, Math.toRadians(32.0D));
            };
        };
    }

    /** 同一波で既に被弾したプレイヤーを再度傷つけず、壁越しと別高度を除外します。 */
    private void impact(@NotNull Encounter state, @NotNull Cast cast) {
        Zone zone = cast.zone;
        if (zone == null) {
            return;
        }
        List<Location> burst = new ArrayList<>();
        for (Location point : zone.points) {
            if (burst.size() >= 32) break;
            burst.add(point.clone().add(0, 0.6D, 0));
        }
        particles.spawnForNearbyViewers(zone.center, burst, SharedParticleDefinitions.EMPUSA_IMPACT);
        sound(zone.center, cast.spell.elementForWave(cast.wave) == DamageElement.LIGHTNING ? "entity.lightning_bolt.impact"
            : "entity.generic.explode", 1.1F, 0.8F);
        DamageElement element = cast.spell.elementForWave(cast.wave);
        for (Player player : targets.apply(zone.center)) {
            if (cast.hitPlayers.contains(player.getUniqueId()) || !zone.contains(player.getLocation())
                || !clearSight(zone.center, player.getLocation())) {
                continue;
            }
            cast.hitPlayers.add(player.getUniqueId());
            try {
                hitHandler.hit(state.boss, player, element, cast.spell.ratio);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6505, exception, cast.spell.name(), player.getUniqueId());
            }
        }
    }

    /** 当たり判定を囲う地表点と頭上の魔力球・柱を近傍閲覧者へ一括描画します。 */
    private void draw(@NotNull Cast cast, @NotNull Location bossLocation, long tick) {
        Zone zone = cast.zone;
        if (zone == null) return;
        DamageElement element = cast.spell.elementForWave(cast.wave);
        SharedParticleDefinition color = switch (element) {
            case FIRE -> SharedParticleDefinitions.EMPUSA_FIRE;
            case ICE -> SharedParticleDefinitions.EMPUSA_ICE;
            case LIGHTNING -> SharedParticleDefinitions.EMPUSA_STORM;
            case NONE -> SharedParticleDefinitions.EMPUSA_HEX;
        };
        particles.spawnForNearbyViewers(zone.center, zone.points, color);
        if (cast.safeSpot != null) {
            List<Location> safeRing = new ArrayList<>(12);
            for (int index = 0; index < 12; index++) {
                double a = index * Math.PI / 6.0D;
                safeRing.add(cast.safeSpot.clone().add(Math.cos(a) * 0.75D, 0.35D, Math.sin(a) * 0.75D));
            }
            particles.spawnForNearbyViewers(cast.safeSpot, safeRing, SharedParticleDefinitions.EMPUSA_BEACON);
        }
        double angle = tick * 0.14D;
        List<Location> orbs = new ArrayList<>(12);
        for (int i = 0; i < 8; i++) {
            double a = angle + i * Math.PI / 4.0D;
            orbs.add(bossLocation.clone().add(Math.cos(a) * 1.5D, 1.35D + Math.sin(a * 2.0D) * 0.32D,
                Math.sin(a) * 1.5D));
        }
        if (cast.spell.ritual) {
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2.0D;
                orbs.add(zone.center.clone().add(Math.cos(a) * 2.0D, 1.0D + (tick % 20L) / 10.0D,
                    Math.sin(a) * 2.0D));
            }
        }
        particles.spawnForNearbyViewers(bossLocation, orbs, color);
        moveDisplays(cast, bossLocation, tick);
    }

    /** 一時的な魔導環、属性片、儀式柱を最大16個だけ生成します。統合版の危険表示は粒子側が担います。 */
    private static void spawnDisplays(@NotNull Cast cast, @NotNull Location bossLocation) {
        Zone zone = cast.zone;
        if (zone == null || zone.center.getWorld() == null) return;
        Material accent = switch (cast.spell.elementForWave(cast.wave)) {
            case FIRE -> Material.COPPER_BLOCK;
            case ICE -> Material.BLUE_ICE;
            case LIGHTNING -> Material.AMETHYST_BLOCK;
            case NONE -> Material.OBSIDIAN;
        };
        int count = cast.spell.ritual ? 16 : 12;
        for (int index = 0; index < count; index++) {
            Location position = displayPosition(cast, bossLocation, 0L, index);
            Material block = index % 3 == 0 ? Material.COPPER_BLOCK : accent;
            float size = index >= 12 ? 0.32F : index >= 8 ? 0.25F : 0.38F;
            BlockDisplay display = position.getWorld().spawn(position, BlockDisplay.class);
            cast.displays.add(display.getUniqueId());
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setBlock(block.createBlockData());
            display.setBrightness(new Display.Brightness(15, 15));
            display.setGlowing(true);
            display.setGlowColorOverride(Color.fromRGB(229, 115, 62));
            display.setViewRange(1.0F);
            display.setDisplayWidth(2.0F);
            display.setDisplayHeight(3.0F);
            display.setTeleportDuration(5);
            display.setTransformation(new Transformation(new Vector3f(-size / 2.0F, 0, -size / 2.0F),
                new Quaternionf(), new Vector3f(size, index >= 12 ? 1.45F : size, size), new Quaternionf()));
        }
    }

    /** 5tickごとに所有する表示だけを移動し、魔導環と破片へ回転・浮遊を与えます。 */
    private static void moveDisplays(@NotNull Cast cast, @NotNull Location bossLocation, long tick) {
        for (int index = 0; index < cast.displays.size(); index++) {
            Entity display = Bukkit.getEntity(cast.displays.get(index));
            if (display instanceof BlockDisplay block && display.isValid()) {
                block.teleport(displayPosition(cast, bossLocation, tick, index));
                Transformation prior = block.getTransformation();
                block.setTransformation(new Transformation(prior.getTranslation(),
                    new Quaternionf().rotateY((float) (tick * 0.035D + index * 0.3D)),
                    prior.getScale(), prior.getRightRotation()));
            }
        }
    }

    /** ボス周囲8片、危険域上4片、儀式時は追加4柱の位置を返します。 */
    private static @NotNull Location displayPosition(
        @NotNull Cast cast,
        @NotNull Location bossLocation,
        long tick,
        int index
    ) {
        Zone zone = cast.zone;
        Location center = index < 8 ? bossLocation : zone.center;
        int groupIndex = index < 8 ? index : (index - 8) % 4;
        int count = index < 8 ? 8 : 4;
        double angle = groupIndex * Math.PI * 2.0D / count + tick * (index < 8 ? 0.035D : -0.025D);
        double radius = index < 8 ? 1.65D : index >= 12 ? 2.6D : 1.25D;
        double height = index >= 12 ? 0.25D : 1.6D + Math.sin(tick * 0.1D + index) * 0.3D;
        return center.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
    }

    /** 詠唱終了・キャンセルで一時BlockDisplayをすべて回収します。 */
    private static void removeDisplays(@NotNull List<UUID> ids) {
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    /** 現在の詠唱だけを表示し、離れた人をBarから外します。 */
    private void updateBar(@NotNull Cast cast, long tick) {
        long duration = Math.max(1L, cast.impactAt - cast.warningAt);
        cast.bar.setProgress(Math.clamp((double) (cast.impactAt - tick) / duration, 0.0D, 1.0D));
        Set<UUID> desired = new HashSet<>();
        for (Player player : targets.apply(cast.zone.center)) {
            desired.add(player.getUniqueId());
            if (!cast.bar.getPlayers().contains(player)) cast.bar.addPlayer(player);
        }
        for (Player player : List.copyOf(cast.bar.getPlayers())) {
            if (!desired.contains(player.getUniqueId())) cast.bar.removePlayer(player);
        }
    }

    /** 有効な対象のうちボスに最も近いプレイヤーを選びます。 */
    private @Nullable Player nearestTarget(@NotNull Location center) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : targets.apply(center)) {
            double distance = horizontalSquared(player.getLocation(), center);
            if (Math.abs(player.getLocation().getY() - center.getY()) <= 5.0D && distance < best) {
                nearest = player;
                best = distance;
            }
        }
        return nearest;
    }

    /** 読み込み済みchunk・world高さ内の地面座標だけを予兆に採用します。 */
    private static boolean validCenter(@NotNull Location center) {
        World world = center.getWorld();
        return world != null && center.getY() > world.getMinHeight() + 1
            && center.getY() < world.getMaxHeight() - 3
            && world.isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4);
    }

    /** 各波の危険域から歩行可能な床へ35tick以内に移れる候補を確認します。 */
    private static @Nullable Location reachableSafeSpot(@NotNull Zone zone, @NotNull Location target) {
        if (safeCircleClear(zone, target) && walkable(target)) return target.clone();
        for (double radius : new double[] {2.5D, 4.5D, 6.0D}) {
            for (int index = 0; index < 16; index++) {
                double angle = index * Math.PI / 8.0D;
                Location candidate = groundNear(target.clone().add(Math.cos(angle) * radius, 0,
                    Math.sin(angle) * radius));
                if (candidate != null && Math.abs(candidate.getY() - target.getY()) <= 1.0D
                    && safeCircleClear(zone, candidate) && walkablePath(target, candidate)
                    && clearSight(target, candidate)) return candidate;
            }
        }
        return null;
    }

    /** 白い安全輪の内部が危険判定へ重ならない位置だけを採用します。 */
    private static boolean safeCircleClear(@NotNull Zone zone, @NotNull Location center) {
        if (zone.contains(center)) return false;
        for (int index = 0; index < 12; index++) {
            double angle = index * Math.PI / 6.0D;
            Location edge = center.clone().add(Math.cos(angle) * 0.8D, 0, Math.sin(angle) * 0.8D);
            if (zone.contains(edge) || !walkable(edge)) {
                return false;
            }
        }
        return true;
    }

    /** 読み込み済みで足元を支え、頭上に空間がある立ち位置を判定します。 */
    private static boolean walkable(@NotNull Location point) {
        if (!validCenter(point)) return false;
        World world = point.getWorld();
        int floorY = (int) Math.floor(point.getY() - 0.01D);
        Block floor = world.getBlockAt(point.getBlockX(), floorY, point.getBlockZ());
        if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid()
            || dangerousFloor(floor.getType())
            || Math.abs(floor.getBoundingBox().getMaxY() - point.getY()) > 0.11D) return false;
        for (int height = 1; height <= 2; height++) {
            Block air = world.getBlockAt(point.getBlockX(), floorY + height, point.getBlockZ());
            if (!air.isPassable() || air.isLiquid() || dangerousFloor(air.getType())) return false;
        }
        return true;
    }

    /** 跳躍中の対象から有限探索し、ハーフブロックも含む歩行面へ地表予兆を投影します。 */
    private static @Nullable Location groundNear(@NotNull Location point) {
        if (!validCenter(point)) return null;
        World world = point.getWorld();
        for (int y = point.getBlockY() + 1; y >= point.getBlockY() - 3; y--) {
            Block floor = world.getBlockAt(point.getBlockX(), y, point.getBlockZ());
            if (!floor.getType().isSolid() || floor.isPassable() || floor.isLiquid()
                || dangerousFloor(floor.getType())) continue;
            double top = floor.getBoundingBox().getMaxY();
            if (top < point.getY() - 3.0D || top > point.getY() + 1.0D) continue;
            Location candidate = point.clone();
            candidate.setY(top);
            if (walkable(candidate)) return candidate;
        }
        return null;
    }

    /** 0.5ブロック間隔で床・頭上・両肩幅と段差を確認します。 */
    private static boolean walkablePath(@NotNull Location from, @NotNull Location to) {
        Vector path = to.toVector().subtract(from.toVector()).setY(0.0D);
        int steps = Math.clamp((int) Math.ceil(path.length() / 0.5D), 1, 16);
        Vector side = path.clone();
        if (side.lengthSquared() < 0.01D) return walkable(to);
        side.setX(-path.getZ()).setZ(path.getX()).normalize().multiply(0.32D);
        double previousY = from.getY();
        for (int step = 0; step <= steps; step++) {
            double fraction = (double) step / steps;
            Location probe = from.clone().add(to.toVector().subtract(from.toVector()).multiply(fraction));
            Location point = groundNear(probe);
            if (point == null || Math.abs(point.getY() - previousY) > 1.0D
                || Math.abs(point.getY() - probe.getY()) > 1.0D) return false;
            Location left = groundNear(point.clone().add(side));
            Location right = groundNear(point.clone().subtract(side));
            if (left == null || right == null || Math.abs(left.getY() - point.getY()) > 1.0D
                || Math.abs(right.getY() - point.getY()) > 1.0D) return false;
            previousY = point.getY();
        }
        return true;
    }

    /** 魔法の安全標識を火傷・ダメージ床へ配置しません。 */
    private static boolean dangerousFloor(@NotNull Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE
            || material == Material.SOUL_CAMPFIRE || material == Material.LAVA
            || material == Material.FIRE || material == Material.SOUL_FIRE
            || material == Material.POWDER_SNOW || material == Material.CACTUS
            || material == Material.SWEET_BERRY_BUSH || material == Material.WITHER_ROSE;
    }

    /** 壁越しへの予兆と命中を避けます。 */
    private static boolean clearSight(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location eye = from.clone().add(0, 1.0D, 0);
        Location end = to.clone().add(0, 0.9D, 0);
        Vector direction = end.toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 0.01D) return true;
        double distance = direction.length();
        RayTraceResult hit = from.getWorld().rayTraceBlocks(eye, direction.normalize(),
            Math.max(0.0D, distance - 0.3D), FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    /** 水平方向の距離二乗を返します。 */
    private static double horizontalSquared(@NotNull Location a, @NotNull Location b) {
        double x = a.getX() - b.getX();
        double z = a.getZ() - b.getZ();
        return x * x + z * z;
    }

    /** 日本語の詠唱名と回避指示をplayer.propertiesから解決します。 */
    private static @NotNull String title(@NotNull Spell spell, int wave) {
        return PlayerMsgResource.format(spell.title.getId(), wave + 1);
    }

    /** 演出地点のworldで開始・発動音を鳴らします。 */
    private static void sound(@NotNull Location center, @NotNull String name, float volume, float pitch) {
        if (center.getWorld() != null) center.getWorld().playSound(center, name, volume, pitch);
    }

    /** 共通ダメージ計算へスキルの魔法属性と倍率を渡します。 */
    @FunctionalInterface
    interface HitHandler {
        /** 同一波で重複しない一撃を適用します。 */
        void hit(@NotNull MobInstance boss, @NotNull Player player, @NotNull DamageElement element, double ratio);
    }

    private enum Spell {
        INTRO(DamageElement.NONE, 0.0D, 40L, false, PlayerMsgId.P_7620),
        FIRE_CIRCLE(DamageElement.FIRE, 0.70D, 40L, false, PlayerMsgId.P_7621),
        ICE_ANNULUS(DamageElement.ICE, 0.68D, 45L, false, PlayerMsgId.P_7622),
        STORM_LINE(DamageElement.LIGHTNING, 0.72D, 35L, false, PlayerMsgId.P_7623),
        HEX_FAN(DamageElement.NONE, 0.70D, 40L, false, PlayerMsgId.P_7624),
        BRONZE_CROSS(DamageElement.FIRE, 0.78D, 45L, false, PlayerMsgId.P_7625),
        BRONZE_RITE(DamageElement.FIRE, 0.88D, 60L, true, PlayerMsgId.P_7626),
        PHANTOM_RITE(DamageElement.LIGHTNING, 0.96D, 60L, true, PlayerMsgId.P_7627);

        private static final Spell[] FIRST_ROTATION = {FIRE_CIRCLE, ICE_ANNULUS, STORM_LINE, HEX_FAN};
        private static final Spell[] SECOND_ROTATION = {BRONZE_CROSS, FIRE_CIRCLE, STORM_LINE, ICE_ANNULUS, HEX_FAN};
        private static final Spell[] THIRD_ROTATION = {STORM_LINE, BRONZE_CROSS, HEX_FAN, ICE_ANNULUS, FIRE_CIRCLE};
        private final DamageElement element;
        private final double ratio;
        private final long warningTicks;
        private final boolean ritual;
        private final PlayerMsgId title;

        Spell(DamageElement element, double ratio, long warningTicks, boolean ritual, PlayerMsgId title) {
            this.element = element;
            this.ratio = ratio;
            this.warningTicks = warningTicks;
            this.ritual = ritual;
            this.title = title;
        }

        /** 三波儀式の各波に対応する属性を返します。 */
        private DamageElement elementForWave(int wave) {
            if (this == BRONZE_RITE && wave == 1) return DamageElement.ICE;
            if (this == PHANTOM_RITE && wave == 1) return DamageElement.FIRE;
            if (this == PHANTOM_RITE && wave == 2) return DamageElement.NONE;
            return element;
        }
    }

    private enum Shape { CIRCLE, ANNULUS, LINE, FAN, CROSS }

    private static final class Encounter {
        private final MobInstance boss;
        private int completedPhase = 1;
        private int pendingRitualWave;
        private int actionIndex;
        private long nextAt;
        private boolean introduced;
        private @Nullable Cast cast;

        private Encounter(MobInstance boss) { this.boss = boss; }
    }

    private static final class Cast {
        private final Spell spell;
        private final Location origin;
        private final BossBar bar;
        private final Set<UUID> hitPlayers = new HashSet<>();
        private final List<UUID> displays = new ArrayList<>();
        private @Nullable Zone zone;
        private @Nullable Location safeSpot;
        private int wave;
        private long warningAt;
        private long impactAt;

        private Cast(Spell spell, Location origin, BossBar bar) {
            this.spell = spell;
            this.origin = origin;
            this.bar = bar;
        }
    }

    /** 正確な命中形状と同じ座標系で最大192点の予兆を生成します。 */
    private static final class Zone {
        private final Shape shape;
        private final Location center;
        private final Vector direction;
        private final double reach;
        private final double secondary;
        private final List<Location> points;

        private Zone(Shape shape, Location center, Vector direction, double reach, double secondary) {
            this.shape = shape;
            this.center = center;
            this.direction = direction;
            this.reach = reach;
            this.secondary = secondary;
            this.points = points();
        }

        /** 予兆の全外接範囲が読み込み済みの間だけ詠唱を許可します。 */
        private boolean fullyLoaded() {
            if (!validCenter(center)) return false;
            World world = center.getWorld();
            if (world == null) return false;
            int minimumX = (int) Math.floor(center.getX() - reach) >> 4;
            int maximumX = (int) Math.floor(center.getX() + reach) >> 4;
            int minimumZ = (int) Math.floor(center.getZ() - reach) >> 4;
            int maximumZ = (int) Math.floor(center.getZ() + reach) >> 4;
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    if (!world.isChunkLoaded(x, z)) return false;
                }
            }
            return true;
        }

        /** 当たり判定を半径0.3のプレイヤー幅込みで計算します。 */
        private boolean contains(@NotNull Location point) {
            if (point.getWorld() != center.getWorld()
                || Math.abs(point.getY() - center.getY()) > MAX_HEIGHT_DELTA) return false;
            double dx = point.getX() - center.getX();
            double dz = point.getZ() - center.getZ();
            double forward = dx * direction.getX() + dz * direction.getZ();
            double side = -dx * direction.getZ() + dz * direction.getX();
            double radius = Math.hypot(dx, dz);
            return switch (shape) {
                case CIRCLE -> radius <= reach;
                case ANNULUS -> radius >= secondary && radius <= reach;
                case LINE -> forward >= 0.0D && forward <= reach && Math.abs(side) <= secondary;
                case FAN -> forward >= 0.0D && radius <= reach
                    && Math.abs(Math.atan2(side, Math.max(0.01D, forward))) <= secondary;
                case CROSS -> Math.abs(forward) <= reach && Math.abs(side) <= secondary
                    || Math.abs(side) <= reach && Math.abs(forward) <= secondary;
            };
        }

        /** 地表を格子状に塗り、外周と内部の双方を低密度でも識別可能にします。 */
        private @NotNull List<Location> points() {
            List<Location> result = new ArrayList<>(MAX_DRAW_POINTS);
            if (shape == Shape.CIRCLE || shape == Shape.ANNULUS) {
                for (int index = 0; index < 32; index++) {
                    double angle = index * Math.PI / 16.0D;
                    addRingPoint(result, angle, reach);
                    if (shape == Shape.ANNULUS) addRingPoint(result, angle, secondary);
                }
            } else if (shape == Shape.LINE) {
                for (double distance = 0.0D; distance <= reach; distance += 0.75D) {
                    addLocalPoint(result, distance, secondary);
                    addLocalPoint(result, distance, -secondary);
                }
                for (double side = -secondary; side <= secondary; side += 0.45D) {
                    addLocalPoint(result, reach, side);
                }
            } else if (shape == Shape.FAN) {
                for (double distance = 0.0D; distance <= reach; distance += 0.75D) {
                    addLocalPoint(result, Math.cos(secondary) * distance, Math.sin(secondary) * distance);
                    addLocalPoint(result, Math.cos(secondary) * distance, -Math.sin(secondary) * distance);
                }
                for (int index = -12; index <= 12; index++) {
                    double angle = secondary * index / 12.0D;
                    addLocalPoint(result, Math.cos(angle) * reach, Math.sin(angle) * reach);
                }
            } else if (shape == Shape.CROSS) {
                for (double distance = -reach; distance <= reach; distance += 0.9D) {
                    addLocalPoint(result, distance, secondary);
                    addLocalPoint(result, distance, -secondary);
                    addLocalPoint(result, secondary, distance);
                    addLocalPoint(result, -secondary, distance);
                }
            }
            double extent = reach;
            double step = shape == Shape.CROSS ? 1.35D : 1.2D;
            for (double x = -extent; x <= extent && result.size() < MAX_DRAW_POINTS; x += step) {
                for (double z = -extent; z <= extent && result.size() < MAX_DRAW_POINTS; z += step) {
                    Location point = center.clone().add(x, 0, z);
                    if (validCenter(point) && contains(point)) result.add(point);
                }
            }
            return List.copyOf(result);
        }

        /** 輪の境界点を読み込み済み範囲だけへ追加します。 */
        private void addRingPoint(@NotNull List<Location> result, double angle, double radius) {
            Location point = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (validCenter(point) && result.size() < MAX_DRAW_POINTS) result.add(point);
        }

        /** 固定した向きで線・扇・十字の実際の境界座標を追加します。 */
        private void addLocalPoint(@NotNull List<Location> result, double forward, double side) {
            Location point = center.clone().add(direction.getX() * forward - direction.getZ() * side,
                0.0D, direction.getZ() * forward + direction.getX() * side);
            if (validCenter(point) && result.size() < MAX_DRAW_POINTS) result.add(point);
        }
    }
}
