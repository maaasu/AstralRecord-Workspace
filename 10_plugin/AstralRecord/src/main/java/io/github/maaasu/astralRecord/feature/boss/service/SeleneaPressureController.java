package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 大技以外の三連魔法弾・跳躍可能な波と、最大五体の小さな分身を共通5tick時計で進めます。 */
final class SeleneaPressureController {
    private static final long VOLLEY_INTERVAL = 60L;
    private static final long WAVE_INTERVAL = 100L;
    private static final double BOLT_SPEED = 1.1D;
    private static final long BOLT_LIFETIME = 100L;
    private static final int MAX_BOLTS = 72;
    private static final double WAVE_SPEED = 0.4D;
    private static final double WAVE_HALF_WIDTH = 0.55D;
    private static final double JUMP_CLEARANCE = 0.65D;
    private final ParticleDisplayService particles;
    private final SeleneaEncounterController.HitHandler hitHandler;
    private final Map<UUID, Pressure> encounters = new HashMap<>();

    /** @param particles 閲覧者と密度を共通管理する描画サービス @param hitHandler 共通魔法ダメージへの委譲先 */
    SeleneaPressureController(ParticleDisplayService particles, SeleneaEncounterController.HitHandler hitHandler) {
        this.particles = particles;
        this.hitHandler = hitHandler;
    }

    /**
     * 確定参加者の一人を維持して全ウィッチを向け、大技中は飛翔弾と波だけを解除します。
     * @param boss 所有ボス @param entity ボス実体 @param active 生存する確定参加者
     * @param tick 共通時計 @param suspended 大技・登場中か @param radius 中央の攻撃半径 @param field 専用フィールドか
     * @param partySize 死亡・一時退出で減らさない確定参加人数
     */
    void tick(MobInstance boss, Entity entity, List<Player> active, long tick, boolean suspended,
              double radius, boolean field, int partySize) {
        Pressure state = encounters.computeIfAbsent(boss.instanceId(), ignored -> new Pressure(tick));
        Player target = active.stream().filter(player -> player.getUniqueId().equals(boss.targetId())).findFirst()
            .orElseGet(() -> active.stream().min(Comparator.comparingDouble(player ->
                SeleneaArena.squared(player.getLocation(), entity.getLocation()))).orElse(null));
        if (target == null) { clear(boss.instanceId()); return; }
        boss.targetId(target.getUniqueId());
        face(entity, target);
        syncClones(state, entity, partySize - 1);
        for (Witch clone : state.clones) face(clone, target);
        if (suspended) {
            state.bolts.clear();
            state.waves.clear();
            state.nextVolleyAt = tick + 30L;
            state.nextWaveAt = tick + 40L;
            return;
        }
        advanceBolts(boss, state, active, tick);
        if (encounters.get(boss.instanceId()) != state) return;
        advanceWaves(boss, state, active, tick, radius, field);
        if (encounters.get(boss.instanceId()) != state) return;
        if (tick >= state.nextVolleyAt) {
            state.nextVolleyAt = tick + VOLLEY_INTERVAL;
            launch(state, entity.getLocation().add(0, 1.45D, 0), target, tick);
            for (Witch clone : state.clones) launch(state, clone.getEyeLocation(), target, tick);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.7F, 1.6F);
        }
        if (tick >= state.nextWaveAt) {
            state.nextWaveAt = tick + WAVE_INTERVAL;
            if (state.waves.size() < 2) state.waves.add(new Wave(boss.spawnLocation(), tick));
        }
    }

    /** AIの停止中も胴と頭のyaw/pitchを同じターゲットへ向けます。 */
    private static void face(Entity entity, Player target) {
        if (entity instanceof Mob mob) {
            mob.setTarget(target);
            mob.lookAt(target.getEyeLocation());
        }
        Location facing = entity.getLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(facing.clone().add(0, entity.getHeight() * 0.8D, 0).toVector());
        if (direction.lengthSquared() < 0.001D) return;
        facing.setDirection(direction);
        entity.setRotation(facing.getYaw(), facing.getPitch());
    }

    /** 参加人数-1体を中央に固定し、AI・衝突・通常攻撃・報酬を持たない非永続の分身を所有します。 */
    private static void syncClones(Pressure state, Entity boss, int desired) {
        int count = Math.clamp(desired, 0, 5);
        state.clones.removeIf(clone -> !clone.isValid() || clone.isDead());
        while (state.clones.size() > count) state.clones.removeLast().remove();
        while (state.clones.size() < count) {
            Location anchor = cloneAnchor(boss.getLocation(), state.clones.size(), count);
            if (!SeleneaArena.loaded(anchor)) break;
            Witch clone = boss.getWorld().spawn(anchor, Witch.class);
            state.clones.add(clone);
            clone.setPersistent(false);
            clone.setAI(false);
            clone.setGravity(false);
            clone.setInvulnerable(true);
            clone.setCollidable(false);
            clone.setSilent(true);
            clone.setRemoveWhenFarAway(false);
            var scale = clone.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(0.3D);
        }
        for (int index = 0; index < state.clones.size(); index++) {
            Witch clone = state.clones.get(index);
            Location anchor = cloneAnchor(boss.getLocation(), index, count);
            if (SeleneaArena.loaded(anchor) && clone.getLocation().distanceSquared(anchor) > 0.01D) clone.teleport(anchor);
        }
    }

    /** 中央の周囲3.5m・高さ1mに小型ウィッチを等間隔で浮かべます。 */
    private static Location cloneAnchor(Location center, int index, int count) {
        double angle = index * Math.PI * 2.0D / Math.max(1, count);
        return center.clone().add(Math.cos(angle) * 3.5D, 1.0D, Math.sin(angle) * 3.5D);
    }

    /** 左右と中央の三弾を同じ発射時の狙いへ収束させ、最大72弾に制限します。 */
    private static void launch(Pressure state, Location source, Player target, long tick) {
        Location aim = target.getLocation().add(0, 0.9D, 0);
        Vector forward = SeleneaArena.direction(source, aim);
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        for (int index = -1; index <= 1 && state.bolts.size() < MAX_BOLTS; index++) {
            Location origin = source.clone().add(side.clone().multiply(index * 0.45D));
            Vector direction = aim.toVector().subtract(origin.toVector());
            if (direction.lengthSquared() < 0.001D) direction = forward.clone();
            state.bolts.add(new Bolt(origin, direction.normalize(), target.getUniqueId(), tick));
        }
    }

    /** 5tick間の線分で対象の体と壁を検査し、粒子の飛び越しによる命中漏れと壁貫通を防ぎます。 */
    private void advanceBolts(MobInstance boss, Pressure state, List<Player> active, long tick) {
        List<Location> trail = new ArrayList<>(MAX_BOLTS * 8);
        Iterator<Bolt> iterator = state.bolts.iterator();
        while (iterator.hasNext()) {
            Bolt bolt = iterator.next();
            Player target = active.stream().filter(player -> player.getUniqueId().equals(bolt.targetId)).findFirst().orElse(null);
            if (target == null || tick - bolt.startedAt > BOLT_LIFETIME) { iterator.remove(); continue; }
            Location from = bolt.point;
            double distance = Math.min(5L, Math.max(0L, tick - bolt.lastTick)) * BOLT_SPEED;
            bolt.lastTick = tick;
            Location to = from.clone().add(bolt.direction.clone().multiply(distance));
            if (!SeleneaArena.loaded(from) || !SeleneaArena.loaded(to) || distance <= 0) { iterator.remove(); continue; }
            boolean loaded = true;
            for (int index = 0; index <= 8; index++) {
                Location point = from.clone().add(bolt.direction.clone().multiply(distance * index / 8.0D));
                if (!SeleneaArena.loaded(point)) { loaded = false; break; }
            }
            if (!loaded) { iterator.remove(); continue; }
            RayTraceResult wall = from.getWorld().rayTraceBlocks(from, bolt.direction, distance, FluidCollisionMode.NEVER, true);
            var box = target.getBoundingBox().expand(0.25D);
            RayTraceResult hit = box.rayTrace(from.toVector(), bolt.direction, distance);
            boolean touches = box.contains(from.toVector()) || hit != null;
            double hitDistance = box.contains(from.toVector()) ? 0.0D : hit == null ? Double.POSITIVE_INFINITY
                : hit.getHitPosition().distance(from.toVector());
            if (touches && (wall == null || hitDistance <= wall.getHitPosition().distance(from.toVector()))) {
                iterator.remove();
                particles.spawnForNearbyViewers(target.getLocation().add(0, 0.9D, 0), SharedParticleDefinitions.SELENEA_BOLT_IMPACT, true);
                hitHandler.hit(boss, target, 0.22D);
                if (encounters.get(boss.instanceId()) != state || boss.currentHealth() <= 0) return;
                continue;
            }
            if (wall != null) { iterator.remove(); continue; }
            for (int index = 1; index <= 8; index++) trail.add(from.clone().add(bolt.direction.clone().multiply(distance * index / 8.0D)));
            bolt.point = to;
        }
        particles.spawnForNearbyViewers(trail, SharedParticleDefinitions.SELENEA_BOLT, true);
    }

    /** 対象の前回位置と広がる波の相対距離から交差を検査し、交差時の跳躍高さで回避を判定します。 */
    private void advanceWaves(MobInstance boss, Pressure state, List<Player> active, long tick, double maximum, boolean field) {
        List<Location> points = new ArrayList<>(768);
        Iterator<Wave> iterator = state.waves.iterator();
        while (iterator.hasNext()) {
            Wave wave = iterator.next();
            double radius = Math.min(maximum + WAVE_HALF_WIDTH, (tick - wave.startedAt) * WAVE_SPEED);
            Set<UUID> present = new HashSet<>();
            for (Player player : active) {
                UUID id = player.getUniqueId();
                present.add(id);
                Location feet = player.getLocation();
                Location previous = wave.previousFeet.put(id, feet.clone());
                if (previous == null) previous = feet;
                if (wave.crossed.contains(id) || !SeleneaArena.inAttackArena(feet, wave.center, maximum, field)) continue;
                double before = Math.sqrt(SeleneaArena.squared(previous, wave.center)) - wave.previousRadius;
                double after = Math.sqrt(SeleneaArena.squared(feet, wave.center)) - radius;
                if (Math.min(before, after) > WAVE_HALF_WIDTH || Math.max(before, after) < -WAVE_HALF_WIDTH) continue;
                double fraction = before > WAVE_HALF_WIDTH ? (before - WAVE_HALF_WIDTH) / (before - after)
                    : before < -WAVE_HALF_WIDTH ? (-WAVE_HALF_WIDTH - before) / (after - before) : 0.0D;
                Location crossing = previous.clone().add(feet.toVector().subtract(previous.toVector())
                    .multiply(Math.clamp(fraction, 0, 1)));
                wave.crossed.add(id);
                Location floor = SeleneaArena.ground(crossing, wave.center, field);
                if (floor == null || crossing.getY() - floor.getY() >= JUMP_CLEARANCE) continue;
                hitHandler.hit(boss, player, 0.55D);
                if (encounters.get(boss.instanceId()) != state || boss.currentHealth() <= 0) return;
            }
            wave.previousFeet.keySet().retainAll(present);
            if (tick >= wave.nextRenderAt) {
                wave.nextRenderAt = tick + 10L;
                int count = Math.clamp((int) Math.ceil(Math.PI * 2.0D * radius / 0.8D), 32, 384);
                for (int index = 0; index < count; index++) {
                    double angle = index * Math.PI * 2.0D / count;
                    Location floor = SeleneaArena.ground(wave.center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                    if (floor != null) points.add(floor.add(0, 0.3D, 0));
                }
            }
            wave.previousRadius = radius;
            if (radius >= maximum + WAVE_HALF_WIDTH) iterator.remove();
        }
        particles.spawnForNearbyViewers(points, SharedParticleDefinitions.SELENEA_GROUND_WAVE, true);
    }

    /** 中断・死亡・挑戦終了では、分身・飛翔弾・波を所有ボスごとに回収します。 */
    void clear(UUID bossId) {
        Pressure state = encounters.remove(bossId);
        if (state == null) return;
        for (Witch clone : state.clones) clone.remove();
        state.clones.clear();
        state.bolts.clear();
        state.waves.clear();
    }

    /** @param playerId 瞬間転送した参加者。通過済み判定を保ち、歩行用の前回座標だけを解除します */
    void forgetPlayerPosition(UUID playerId) {
        for (Pressure state : encounters.values()) {
            for (Wave wave : state.waves) wave.previousFeet.remove(playerId);
        }
    }

    /** 生存参加者が減っても発射済み弾の対象と波ごとの通過判定を混ぜません。 */
    private static final class Pressure {
        final List<Witch> clones = new ArrayList<>(5);
        final List<Bolt> bolts = new ArrayList<>(MAX_BOLTS);
        final List<Wave> waves = new ArrayList<>(2);
        long nextVolleyAt, nextWaveAt;
        /** @param tick 初回の予備時間を設定する共通時計 */
        Pressure(long tick) { nextVolleyAt = tick + 40L; nextWaveAt = tick + 60L; }
    }

    /** 発射時に固定した方向・対象と、現在の飛翔点です。 */
    private static final class Bolt {
        Location point;
        final Vector direction;
        final UUID targetId;
        final long startedAt;
        long lastTick;
        /** @param point 発射位置 @param direction 単位方向 @param targetId 対象 @param tick 発射時刻 */
        Bolt(Location point, Vector direction, UUID targetId, long tick) {
            this.point = point; this.direction = direction; this.targetId = targetId; startedAt = tick; lastTick = tick;
        }
    }

    /** 波の通過済み参加者を、命中した場合とジャンプで回避した場合の両方で保持します。 */
    private static final class Wave {
        final Location center;
        final long startedAt;
        final Set<UUID> crossed = new HashSet<>();
        final Map<UUID, Location> previousFeet = new HashMap<>();
        double previousRadius;
        long nextRenderAt;
        /** @param center 発生中心 @param tick 発生時刻 */
        Wave(Location center, long tick) { this.center = center.clone(); startedAt = tick; nextRenderAt = tick; }
    }
}
