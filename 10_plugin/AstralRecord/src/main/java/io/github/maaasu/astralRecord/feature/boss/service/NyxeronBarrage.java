package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ニクセロンの弾幕・衝撃環・光刃・星雨を1tickごとに進め、当たり判定と代替粒子を処理します。
 *
 * <p>弾は貫通し、同じ参加者には短い間隔を空けて命中します。両版の表示は発射時に寿命と軌跡を渡し、
 * クライアントの補間で滑らかに動かします。統合版の弾は方位を1/256周に揃え、表示と判定の軌跡を一致させます。</p>
 */
final class NyxeronBarrage {
    static final double ARENA_EDGE = 41.0D;
    static final double BULLET_HEIGHT = 1.0D;
    static final float BULLET_SIZE = 0.9F;
    private static final double BULLET_HIT = 0.62D;
    private static final long BULLET_COOLDOWN = 10L;
    private static final double NOVA_START = 1.6D;
    private static final int NOVA_BULLETS = 48;
    private static final double LOW_CLEAR = 0.6D;
    private static final double SNEAK_CLEAR = 0.3D;
    private static final double RING_START = 2.0D;
    private static final double RING_HALF = 0.55D;
    static final double LOW_HEIGHT = 0.7D;
    static final double HIGH_LIFT = 1.55D;
    static final double HIGH_HEIGHT = 0.75D;
    private static final double BEAM_HALF = 0.65D;
    private static final float BEAM_WIDTH = 1.2F;
    private static final long BEAM_COOLDOWN = 12L;
    private static final double QUANTUM = 360.0D / 256.0D;
    private static final double FRAGMENT_PICKUP = 1.6D;
    private static final long GRACE_TICKS = 40L;
    private static final int MAX_BULLETS = 520;

    /** 専用表示を持たない接続へ送る代替粒子の種類。 */
    enum Mark { BULLET, LOW, HIGH, WARNING, FRAGMENT }

    /** 弾幕側から鳴らす効果音。 */
    enum Cue { BULLET, FRAGMENT_DROP, IMPACT }

    /** 遭遇側の処理。命中で挑戦が終わった場合は false を返します。 */
    interface Host {
        boolean hit(Player player, double ratio);

        void fallback(List<Player> active, List<Location> points, Mark mark);

        void sound(List<Player> active, Cue cue, float pitch);

        void collected(List<Player> active, Player player, int collected);
    }

    private final UUID bossId;
    private final Location center;
    private final NyxeronPackDisplay display;
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Emitter> emitters = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final List<Beam> beams = new ArrayList<>();
    private final List<Rain> rains = new ArrayList<>();
    private final List<Meteor> meteors = new ArrayList<>();
    private final List<Fragment> fragments = new ArrayList<>();
    private final List<Runnable> visuals = new ArrayList<>();
    private final Map<UUID, Long> bulletHit = new HashMap<>();
    private final Map<UUID, Long> lastInside = new HashMap<>();
    private final Map<UUID, Long> graceUntil = new HashMap<>();
    private int collected;
    private long nextBulletSound;

    /** @param bossId 所有個体 @param center 床の中心（参加者の足の高さ） @param display 対応パックの表示 */
    NyxeronBarrage(UUID bossId, Location center, NyxeronPackDisplay display) {
        this.bossId = bossId;
        this.center = center.clone();
        this.display = display;
    }

    /** @return 処理中の要素がある場合true */
    boolean active() {
        return !bullets.isEmpty() || !emitters.isEmpty() || !rings.isEmpty() || !beams.isEmpty() || !rains.isEmpty()
            || !meteors.isEmpty() || !fragments.isEmpty();
    }

    /** @return この星雨で拾われた星片の数 */
    int collected() {
        return collected;
    }

    /** 星片の集計と床の星片を次の星雨のために消します。 */
    void resetFragments() {
        collected = 0;
        for (Fragment fragment : fragments) fragment.visual.run();
        fragments.clear();
    }

    /** @param angle 方位（度） @return 統合版のyaw分解能へ揃えた方位 */
    static double quantize(double angle) {
        return Math.round(angle / QUANTUM) * QUANTUM;
    }

    // ------------------------------------------------------------------ spawning

    /**
     * 48発の輪を放ちます。隙間は gapAngle を中心に等間隔の gaps か所、各 half*2+1 発分です。
     * @param speed 1tickあたりの距離 @param ratio 魔法攻撃倍率
     */
    void nova(long tick, double gapAngle, int gaps, int half, double speed, double ratio) {
        double base = quantize(gapAngle);
        int period = NOVA_BULLETS / Math.max(1, gaps);
        long life = (long) Math.ceil((ARENA_EDGE - NOVA_START) / speed);
        Location origin = center.clone().add(0, BULLET_HEIGHT, 0);
        List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
        for (int i = 0; i < NOVA_BULLETS; i++) {
            int offset = i % period;
            if (offset <= half || offset >= period - half) continue;
            double angle = base + i * 360.0D / NOVA_BULLETS;
            Bullet bullet = bullet(origin, angle, NOVA_START, speed, tick, life, ratio);
            if (bullet != null) shapes.add(bullet.shape(center, life).javaOnly());
        }
        shapes.add(NyxeronPackDisplay.Shape.nova(origin, base, NOVA_START, speed * 20.0D, gaps, half, BULLET_SIZE));
        visuals.add(display.effects(bossId, shapes, tick, life));
    }

    /** 回転しながら弾を撒く渦。 */
    void spiral(long tick, int arms, double angle, double omega, int interval, long duration, double speed, double ratio) {
        emitters.add(new Emitter(arms, angle, omega, interval, tick, tick + duration, speed, ratio));
    }

    /** 中心から広がる衝撃環。high は高い環（しゃがんで回避）。 */
    void ring(long tick, boolean high, double speed, double ratio) {
        long life = (long) Math.ceil((ARENA_EDGE + RING_HALF - RING_START) / speed);
        Ring ring = new Ring(high, tick, speed, life, ratio);
        ring.visual = display.effects(bossId, List.of(NyxeronPackDisplay.Shape.ring(
            high ? NyxeronPackDisplay.Style.RING_HIGH : NyxeronPackDisplay.Style.RING_LOW, center, RING_START,
            ARENA_EDGE + RING_HALF, high ? HIGH_HEIGHT : LOW_HEIGHT, high ? HIGH_LIFT : 0.0D)), tick, life);
        rings.add(ring);
    }

    /**
     * 等間隔に並ぶ回転光刃。highs[i] がtrueの刃はしゃがんで潜る高い刃です。
     * @param omega 1tickあたりの回転（度、+Xから+Zの向き）
     */
    void beams(long tick, double angle, boolean[] highs, double omega, long duration, double ratio) {
        for (int i = 0; i < highs.length; i++) {
            boolean high = highs[i];
            Beam beam = new Beam(high, angle + i * 360.0D / highs.length, omega, tick, tick + duration, ratio);
            beam.visual = display.beam(bossId, NyxeronPackDisplay.Shape.beam(
                high ? NyxeronPackDisplay.Style.BEAM_HIGH : NyxeronPackDisplay.Style.BEAM_LOW, center,
                high ? HIGH_LIFT : 0.0D, BEAM_WIDTH, (float) (high ? HIGH_HEIGHT : LOW_HEIGHT), (float) ARENA_EDGE),
                beam.angle, omega, tick, duration);
            beams.add(beam);
        }
    }

    /** 光刃の回転を反転します。 */
    void reverseBeams(long tick) {
        for (Beam beam : beams) {
            beam.angle = beam.angleAt(tick);
            beam.born = tick;
            beam.omega = -beam.omega;
            beam.visual.aim(beam.angle, beam.omega, tick);
        }
    }

    /**
     * 星雨。interval tickごとに perWave 個の星が fall tick後に落ち、every 個に1個が星片を残します。
     * @param targeted 参加者の位置を狙う割合
     */
    void rain(long tick, long duration, int interval, int perWave, long fall, double radius, double targeted,
              int every, double ratio) {
        rains.add(new Rain(tick, tick + duration, interval, perWave, fall, radius, targeted, every, ratio));
    }

    private Bullet bullet(Location origin, double angle, double start, double speed, long tick, long life, double ratio) {
        if (bullets.size() >= MAX_BULLETS) return null;
        double radians = Math.toRadians(angle);
        double dx = Math.cos(radians);
        double dz = Math.sin(radians);
        Bullet bullet = new Bullet(origin.getX() + dx * start, origin.getY(), origin.getZ() + dz * start, dx, dz, angle,
            speed, tick, tick + life, ratio);
        bullets.add(bullet);
        return bullet;
    }

    // ------------------------------------------------------------------ progress

    /** @param inside 床面の参加者 @param tick 現在tick。復帰直後の参加者へ短い猶予を与えます */
    void observe(List<Player> inside, long tick) {
        for (Player player : inside) {
            UUID id = player.getUniqueId();
            Long last = lastInside.put(id, tick);
            if (last == null || tick - last > 10L) graceUntil.put(id, tick + GRACE_TICKS);
        }
    }

    /**
     * 全要素を1tick進めます。
     * @param active 確定参加者 @param tick 現在tick @return 挑戦が続く場合true
     */
    boolean tick(Host host, List<Player> active, long tick) {
        List<Player> inside = new ArrayList<>();
        for (Player player : active) if (NyxeronArena.inside(player.getLocation(), center)) inside.add(player);
        observe(inside, tick);
        List<Player> targets = new ArrayList<>();
        for (Player player : inside) if (tick >= graceUntil.getOrDefault(player.getUniqueId(), 0L)) targets.add(player);
        if (!beams.isEmpty() && tick % 5 == 0) display.strike(bossId, tick);
        boolean alive = emit(host, active, inside, tick)
            && advanceBullets(host, active, targets, tick)
            && advanceRings(host, active, targets, tick)
            && advanceBeams(host, active, targets, tick)
            && advanceMeteors(host, active, targets, tick)
            && advanceFragments(host, active, inside, tick);
        if (alive && !active() && !visuals.isEmpty()) visuals.clear();
        return alive;
    }

    private boolean emit(Host host, List<Player> active, List<Player> inside, long tick) {
        Iterator<Emitter> emitterIterator = emitters.iterator();
        while (emitterIterator.hasNext()) {
            Emitter emitter = emitterIterator.next();
            if (tick >= emitter.dies) {
                emitterIterator.remove();
                continue;
            }
            if ((tick - emitter.born) % emitter.interval != 0) continue;
            long life = (long) Math.ceil((ARENA_EDGE - 1.2D) / emitter.speed);
            Location origin = center.clone().add(0, BULLET_HEIGHT, 0);
            List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
            double angle = emitter.angle + emitter.omega * (tick - emitter.born);
            for (int arm = 0; arm < emitter.arms; arm++) {
                Bullet bullet = bullet(origin, quantize(angle + arm * 360.0D / emitter.arms), 1.2D, emitter.speed, tick,
                    life, emitter.ratio);
                if (bullet != null) shapes.add(bullet.shape(center, life));
            }
            visuals.add(display.effects(bossId, shapes, tick, life));
            display.strike(bossId, tick);
            if (tick >= nextBulletSound) {
                nextBulletSound = tick + 6L;
                host.sound(active, Cue.BULLET, 1.0F);
            }
        }
        Iterator<Rain> rainIterator = rains.iterator();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        while (rainIterator.hasNext()) {
            Rain rain = rainIterator.next();
            if (tick >= rain.dies) {
                rainIterator.remove();
                continue;
            }
            if ((tick - rain.born) % rain.interval != 0) continue;
            List<NyxeronPackDisplay.Shape> shapes = new ArrayList<>();
            for (int i = 0; i < rain.perWave; i++) {
                Location target;
                if (!inside.isEmpty() && random.nextDouble() < rain.targeted) {
                    Location at = inside.get(random.nextInt(inside.size())).getLocation();
                    target = center.clone().add(at.getX() - center.getX() + random.nextDouble(-1.2D, 1.2D), 0,
                        at.getZ() - center.getZ() + random.nextDouble(-1.2D, 1.2D));
                } else {
                    double angle = random.nextDouble(Math.PI * 2.0D);
                    double radius = 3.0D + Math.sqrt(random.nextDouble()) * 34.0D;
                    target = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                }
                boolean drops = ++rain.spawned % rain.every == 0 && NyxeronArena.horizontalDistance(target, center) <= 37.0D;
                meteors.add(new Meteor(target, tick + rain.fall, rain.radius, rain.ratio, drops));
                shapes.add(NyxeronPackDisplay.Shape.ring(NyxeronPackDisplay.Style.APPROACH, target, rain.radius, 0.3D, 0.3D, 0.0D));
                shapes.add(NyxeronPackDisplay.Shape.star(target, 12.0D, drops ? 1.6F : 1.2F));
            }
            visuals.add(display.effects(bossId, shapes, tick, rain.fall));
        }
        return true;
    }

    private boolean advanceBullets(Host host, List<Player> active, List<Player> targets, long tick) {
        List<Location> points = tick % 2 == 0 ? new ArrayList<>() : null;
        Iterator<Bullet> iterator = bullets.iterator();
        while (iterator.hasNext()) {
            Bullet bullet = iterator.next();
            if (tick >= bullet.dies) {
                iterator.remove();
                continue;
            }
            double travelled = bullet.speed * (tick - bullet.born);
            double x = bullet.x + bullet.dx * travelled;
            double z = bullet.z + bullet.dz * travelled;
            if (points != null) points.add(new Location(center.getWorld(), x, bullet.y, z));
            for (Player player : targets) {
                Location at = player.getLocation();
                double ddx = at.getX() - x;
                double ddz = at.getZ() - z;
                if (ddx * ddx + ddz * ddz > BULLET_HIT * BULLET_HIT) continue;
                double top = at.getY() + (player.isSneaking() ? 1.5D : 1.8D);
                if (bullet.y < at.getY() - 0.35D || bullet.y > top + 0.35D) continue;
                UUID id = player.getUniqueId();
                if (tick < bulletHit.getOrDefault(id, Long.MIN_VALUE)) continue;
                bulletHit.put(id, tick + BULLET_COOLDOWN);
                if (!host.hit(player, bullet.ratio)) return false;
            }
        }
        if (points != null && !points.isEmpty()) host.fallback(active, points, Mark.BULLET);
        return true;
    }

    private boolean advanceRings(Host host, List<Player> active, List<Player> targets, long tick) {
        Iterator<Ring> iterator = rings.iterator();
        while (iterator.hasNext()) {
            Ring ring = iterator.next();
            double radius = RING_START + ring.speed * (tick - ring.born);
            if (tick >= ring.born + ring.life) {
                iterator.remove();
                ring.visual.run();
                continue;
            }
            for (Player player : targets) {
                UUID id = player.getUniqueId();
                if (ring.crossed.contains(id)) continue;
                Location at = player.getLocation();
                double after = NyxeronArena.horizontalDistance(at, center) - radius;
                Double before = ring.previous.put(id, after);
                // 環の中心線が足元を通過した瞬間に、跳躍・しゃがみの成否を判定する。
                boolean crossing = before != null && before > 0.0D && after <= 0.0D && before - after < 3.0D;
                if (!crossing) continue;
                ring.crossed.add(id);
                if (dodged(player, ring.high)) continue;
                if (!host.hit(player, ring.ratio)) return false;
            }
            if (tick % 2 == 0) {
                List<Location> points = new ArrayList<>();
                int count = Math.clamp((int) Math.ceil(Math.PI * 2.0D * radius / 1.0D), 12, 260);
                double lift = ring.high ? HIGH_LIFT + HIGH_HEIGHT / 2.0D : 0.3D;
                for (int i = 0; i < count; i++) {
                    double angle = i * Math.PI * 2.0D / count;
                    points.add(center.clone().add(Math.cos(angle) * radius, lift, Math.sin(angle) * radius));
                }
                host.fallback(active, points, ring.high ? Mark.HIGH : Mark.LOW);
            }
        }
        return true;
    }

    private boolean advanceBeams(Host host, List<Player> active, List<Player> targets, long tick) {
        Iterator<Beam> iterator = beams.iterator();
        while (iterator.hasNext()) {
            Beam beam = iterator.next();
            if (tick >= beam.dies) {
                iterator.remove();
                beam.visual.remove();
                continue;
            }
            double angle = beam.angleAt(tick);
            for (Player player : targets) {
                Location at = player.getLocation();
                double dx = at.getX() - center.getX();
                double dz = at.getZ() - center.getZ();
                double distance = Math.hypot(dx, dz);
                UUID id = player.getUniqueId();
                if (distance > ARENA_EDGE + 0.5D) {
                    beam.previous.remove(id);
                    continue;
                }
                double relative = wrap(Math.toDegrees(Math.atan2(dz, dx)) - angle);
                Double before = beam.previous.put(id, relative);
                boolean touching = Math.abs(relative) < 90.0D && distance * Math.sin(Math.toRadians(Math.abs(relative))) <= BEAM_HALF;
                // 刃の中心線が通過した瞬間に判定する。初回の観測で刃の上にいる場合だけ接触で判定する。
                boolean crossed = before != null && Math.signum(before) != Math.signum(relative)
                    && Math.abs(before - relative) < 90.0D;
                if (!(crossed || before == null && touching)) continue;
                if (tick < beam.lastHit.getOrDefault(id, Long.MIN_VALUE)) continue;
                if (dodged(player, beam.high)) continue;
                beam.lastHit.put(id, tick + BEAM_COOLDOWN);
                if (!host.hit(player, beam.ratio)) return false;
            }
            if (tick % 2 == 0) {
                List<Location> points = new ArrayList<>();
                double radians = Math.toRadians(angle);
                double lift = beam.high ? HIGH_LIFT + HIGH_HEIGHT / 2.0D : 0.35D;
                for (double r = 1.0D; r <= ARENA_EDGE; r += 0.8D)
                    points.add(center.clone().add(Math.cos(radians) * r, lift, Math.sin(radians) * r));
                host.fallback(active, points, beam.high ? Mark.HIGH : Mark.LOW);
            }
        }
        return true;
    }

    private boolean advanceMeteors(Host host, List<Player> active, List<Player> targets, long tick) {
        List<NyxeronPackDisplay.Shape> bursts = new ArrayList<>();
        List<Location> warn = tick % 4 == 0 ? new ArrayList<>() : null;
        Iterator<Meteor> iterator = meteors.iterator();
        while (iterator.hasNext()) {
            Meteor meteor = iterator.next();
            if (tick < meteor.strikeAt) {
                if (warn != null) {
                    for (int i = 0; i < 10; i++) {
                        double angle = i * Math.PI / 5.0D;
                        warn.add(meteor.at.clone().add(Math.cos(angle) * meteor.radius, 0.2D, Math.sin(angle) * meteor.radius));
                    }
                }
                continue;
            }
            iterator.remove();
            for (Player player : targets) {
                Location at = player.getLocation();
                if (Math.abs(at.getY() - meteor.at.getY()) > 3.0D) continue;
                double dx = at.getX() - meteor.at.getX();
                double dz = at.getZ() - meteor.at.getZ();
                if (dx * dx + dz * dz > meteor.radius * meteor.radius) continue;
                if (!host.hit(player, meteor.ratio)) return false;
            }
            bursts.add(NyxeronPackDisplay.Shape.circle(NyxeronPackDisplay.Style.IMPACT, meteor.at, meteor.radius, 1.6D));
            if (meteor.drops) {
                Fragment fragment = new Fragment(meteor.at, tick + 160L);
                fragment.visual = display.effects(bossId, List.of(new NyxeronPackDisplay.Shape(NyxeronPackDisplay.Style.FRAGMENT,
                    meteor.at.clone().add(0, 0.06D, 0), 1.6F, 1.6F, 1.6F)), tick, 160L);
                fragments.add(fragment);
                host.sound(active, Cue.FRAGMENT_DROP, 1.0F);
            }
        }
        if (!bursts.isEmpty()) {
            visuals.add(display.effects(bossId, bursts, tick, 10L));
            host.sound(active, Cue.IMPACT, 0.9F + ThreadLocalRandom.current().nextFloat() * 0.3F);
        }
        if (warn != null && !warn.isEmpty()) host.fallback(active, warn, Mark.WARNING);
        return true;
    }

    private boolean advanceFragments(Host host, List<Player> active, List<Player> inside, long tick) {
        List<Location> points = tick % 5 == 0 ? new ArrayList<>() : null;
        Iterator<Fragment> iterator = fragments.iterator();
        while (iterator.hasNext()) {
            Fragment fragment = iterator.next();
            if (tick >= fragment.until) {
                iterator.remove();
                fragment.visual.run();
                continue;
            }
            Player taker = null;
            for (Player player : inside) {
                Location at = player.getLocation();
                if (Math.abs(at.getY() - fragment.at.getY()) > 2.5D) continue;
                double dx = at.getX() - fragment.at.getX();
                double dz = at.getZ() - fragment.at.getZ();
                if (dx * dx + dz * dz <= FRAGMENT_PICKUP * FRAGMENT_PICKUP) {
                    taker = player;
                    break;
                }
            }
            if (taker != null) {
                iterator.remove();
                fragment.visual.run();
                collected++;
                host.collected(active, taker, collected);
                continue;
            }
            if (points != null) {
                points.add(fragment.at.clone().add(0, 1.0D, 0));
                points.add(fragment.at.clone().add(0, 0.4D, 0));
            }
        }
        if (points != null && !points.isEmpty()) host.fallback(active, points, Mark.FRAGMENT);
        return true;
    }

    /** @return 低い攻撃は跳躍中、高い攻撃はしゃがんで床に立っている場合true */
    private boolean dodged(Player player, boolean high) {
        double feet = player.getLocation().getY() - center.getY();
        return high ? player.isSneaking() && feet < SNEAK_CLEAR : feet >= LOW_CLEAR;
    }

    private static double wrap(double degrees) {
        double value = degrees % 360.0D;
        if (value > 180.0D) value -= 360.0D;
        if (value <= -180.0D) value += 360.0D;
        return value;
    }

    /** 進行中の要素と表示を全て解除します（星片の集計は残します）。 */
    void clear() {
        for (Runnable visual : visuals) visual.run();
        visuals.clear();
        for (Ring ring : rings) ring.visual.run();
        for (Beam beam : beams) beam.visual.remove();
        for (Fragment fragment : fragments) fragment.visual.run();
        bullets.clear();
        emitters.clear();
        rings.clear();
        beams.clear();
        rains.clear();
        meteors.clear();
        fragments.clear();
    }

    private record Bullet(double x, double y, double z, double dx, double dz, double angle, double speed, long born,
                          long dies, double ratio) {
        NyxeronPackDisplay.Shape shape(Location world, long life) {
            Location origin = new Location(world.getWorld(), x, y, z);
            return NyxeronPackDisplay.Shape.bullet(origin, angle, speed * life, life / 20.0D, BULLET_SIZE);
        }
    }

    private record Emitter(int arms, double angle, double omega, int interval, long born, long dies, double speed,
                           double ratio) {}

    private static final class Ring {
        final boolean high;
        final long born;
        final double speed;
        final long life;
        final double ratio;
        final Map<UUID, Double> previous = new HashMap<>();
        final Set<UUID> crossed = new HashSet<>();
        Runnable visual = () -> { };

        Ring(boolean high, long born, double speed, long life, double ratio) {
            this.high = high;
            this.born = born;
            this.speed = speed;
            this.life = life;
            this.ratio = ratio;
        }
    }

    private static final class Beam {
        final boolean high;
        final long dies;
        final double ratio;
        final Map<UUID, Double> previous = new HashMap<>();
        final Map<UUID, Long> lastHit = new HashMap<>();
        double angle;
        double omega;
        long born;
        NyxeronPackDisplay.Beam visual = NyxeronPackDisplay.Beam.NONE;

        Beam(boolean high, double angle, double omega, long born, long dies, double ratio) {
            this.high = high;
            this.angle = angle;
            this.omega = omega;
            this.born = born;
            this.dies = dies;
            this.ratio = ratio;
        }

        double angleAt(long tick) {
            return angle + omega * (tick - born);
        }
    }

    private static final class Rain {
        final long born;
        final long dies;
        final int interval;
        final int perWave;
        final long fall;
        final double radius;
        final double targeted;
        final int every;
        final double ratio;
        int spawned;

        Rain(long born, long dies, int interval, int perWave, long fall, double radius, double targeted, int every,
             double ratio) {
            this.born = born;
            this.dies = dies;
            this.interval = interval;
            this.perWave = perWave;
            this.fall = fall;
            this.radius = radius;
            this.targeted = targeted;
            this.every = Math.max(1, every);
            this.ratio = ratio;
        }
    }

    private record Meteor(Location at, long strikeAt, double radius, double ratio, boolean drops) {}

    private static final class Fragment {
        final Location at;
        final long until;
        Runnable visual = () -> { };

        Fragment(Location at, long until) {
            this.at = at;
            this.until = until;
        }
    }
}
