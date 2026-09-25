package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不死鳥の炎の羽ばたきと尾を、実体の位置・向きから描画します。 */
final class ArchmagePhoenixVisuals {
    private static final double VIEW_DISTANCE = 32.0D;
    private static final double DETAIL_DISTANCE_SQUARED = 16.0D * 16.0D;
    private static final int MAX_VIEWERS = 16;
    private static final int MAX_POINTS = 80;
    private final ParticleDisplayService particles;

    /** 共有表示サービスを受け取ります。 */
    ArchmagePhoenixVisuals(ParticleDisplayService particles) {
        this.particles = particles;
    }

    /** 閲覧者を1回だけ近傍検索し、近距離2tick・遠距離6tickで描画します。 */
    void render(Player owner, Parrot parrot, Location body, long tick, int phase) {
        boolean distantFrame = (tick + phase) % 6L == 0L;
        List<Player> viewers = body.getWorld().getNearbyPlayers(body, VIEW_DISTANCE).stream()
                .filter(viewer -> viewer.isOnline() && viewer.canSee(parrot))
                .filter(viewer -> viewer.getWorld() == body.getWorld()
                        && viewer.getLocation().distanceSquared(body) <= VIEW_DISTANCE * VIEW_DISTANCE)
                .filter(viewer -> distantFrame || viewer.getLocation().distanceSquared(body) <= DETAIL_DISTANCE_SQUARED)
                .sorted(Comparator.comparingDouble(viewer -> viewer.getUniqueId().equals(owner.getUniqueId())
                        ? -1.0D : viewer.getLocation().distanceSquared(body)))
                .limit(MAX_VIEWERS)
                .toList();
        if (viewers.isEmpty()) return;

        Map<SharedParticleDefinition, List<Location>> frame = createFrame(body, tick, phase, parrot.getVelocity().length());
        if (frame.values().stream().mapToInt(List::size).sum() > MAX_POINTS) return;
        Map<SharedParticleDefinition, List<Location>> distant = distantFrame ? reduce(frame) : Map.of();
        for (Player viewer : viewers) {
            Map<SharedParticleDefinition, List<Location>> visible = viewer.getLocation().distanceSquared(body)
                    <= DETAIL_DISTANCE_SQUARED ? frame : distant;
            visible.forEach((definition, points) -> particles.spawnForViewer(viewer, points, definition));
        }
    }

    /** 炎の卵を琥珀色の積層殻として表示エンティティから組み立てます。 */
    List<BlockDisplay> spawnEgg(Location base) {
        List<BlockDisplay> layers = new ArrayList<>(11);
        float[] widths = {0.48F, 0.83F, 1.10F, 1.32F, 1.40F, 1.38F, 1.23F, 1.04F, 0.75F, 0.45F};
        for (int index = 0; index < widths.length; index++) {
            float width = widths[index];
            float height = 0.18F;
            Material material = index % 3 == 0 ? Material.SHROOMLIGHT
                    : index % 2 == 0 ? Material.ORANGE_STAINED_GLASS : Material.MAGMA_BLOCK;
            Location center = base.clone().add(0.0D, 0.15D + index * 0.19D, 0.0D);
            BlockDisplay layer = base.getWorld().spawn(center, BlockDisplay.class, display -> {
                display.setBlock(material.createBlockData());
                display.setTransformation(new Transformation(
                        new Vector3f(-width / 2.0F, -height / 2.0F, -width / 2.0F),
                        new Quaternionf(), new Vector3f(width, height, width), new Quaternionf()));
                display.setPersistent(false);
                display.setInvulnerable(true);
            });
            layers.add(layer);
        }
        return layers;
    }

    /** 卵の外周へ火の粉を回し、正面へ不死鳥の翼と尾を描きます。 */
    void renderEgg(Location base, long tick) {
        List<Location> sparks = new ArrayList<>(32);
        List<Location> emblem = new ArrayList<>(25);
        for (int index = 0; index < 24; index++) {
            double angle = index * Math.PI / 12.0D + tick * 0.025D;
            double height = 0.18D + (index % 8) * 0.24D;
            sparks.add(base.clone().add(Math.cos(angle) * 0.82D, height,
                    Math.sin(angle) * 0.82D));
        }
        for (int side : new int[] {-1, 1}) {
            for (int point = 0; point < 8; point++) {
                double u = point / 7.0D;
                emblem.add(base.clone().add(side * (0.10D + u * 0.45D),
                        0.95D + Math.sin(u * Math.PI) * 0.35D, 0.72D));
            }
        }
        for (int point = 0; point < 8; point++) {
            emblem.add(base.clone().add(0.0D, 0.83D - point * 0.08D, 0.75D));
        }
        particles.spawnForNearbyViewers(base, sparks, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_EMBER);
        particles.spawnForNearbyViewers(base, emblem, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_ORANGE);
    }

    /** 攻撃中に前方へ伸びる炎の軌跡を描きます。 */
    void renderAttack(Location body, long tick) {
        List<Location> flames = new ArrayList<>(15);
        Vector forward = body.getDirection().normalize();
        for (int index = 0; index < 15; index++) {
            double distance = 0.3D + index * 0.11D;
            double wobble = Math.sin(tick * 0.55D + index * 1.3D) * 0.18D;
            flames.add(body.clone().add(forward.clone().multiply(distance))
                    .add(wobble, 0.15D + Math.sin(index) * 0.12D, -wobble));
        }
        particles.spawnForNearbyViewers(body, flames, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_FLAME);
    }

    /** 命中地点に赤橙の炎を短く噴き上げます。 */
    void renderAttackImpact(Location impact) {
        List<Location> burst = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = index * Math.PI / 12.0D;
            burst.add(impact.clone().add(Math.cos(angle) * 0.55D, 0.3D + index % 4 * 0.22D,
                    Math.sin(angle) * 0.55D));
        }
        particles.spawnForNearbyViewers(impact, burst, SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_FLAME);
    }

    /** 羽根40点・尾18点・胸元6点・火の粉4点・翼端の金色4点・青炎2点の計74点に旧DUSTと炎を混ぜます。 */
    static Map<SharedParticleDefinition, List<Location>> createFrame(Location body, long tick, int phase, double speed) {
        Vector forward = body.getDirection().setY(0.0D).normalize();
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        double animation = (tick + phase * 4L) * Math.PI / 12.0D;
        double flap = Math.sin(animation) * 0.48D;
        double sweep = 0.12D + Math.min(0.3D, Math.max(0.0D, speed) * 0.4D);
        List<Location> flame = new ArrayList<>(29);
        List<Location> small = new ArrayList<>(35);
        List<Location> gold = new ArrayList<>(20);
        List<Location> orange = new ArrayList<>(10);
        List<Location> red = new ArrayList<>(10);
        List<Location> embers = new ArrayList<>(4);
        List<Location> blue = new ArrayList<>(2);
        for (int side : new int[] {-1, 1}) {
            for (int feather = 0; feather < 5; feather++) {
                double span = 1.1D + feather * 0.23D;
                double trail = sweep + feather * 0.25D;
                Location tip = null;
                for (int segment = 1; segment <= 4; segment++) {
                    double u = segment / 4.0D;
                    double lift = (0.65D - feather * 0.08D) * u
                            + Math.sin(flap) * span * u + Math.sin(u * Math.PI) * 0.12D;
                    tip = point(body, right, forward, side * (0.18D + span * u * Math.cos(flap)),
                            0.28D + lift, -trail * u);
                    if (segment == 1 || segment == 3) {
                        (feather <= 1 ? gold : feather <= 3 ? orange : red).add(tip);
                    } else {
                        (segment == 2 ? flame : small).add(tip);
                    }
                }
                if (feather == 0 || feather == 4) gold.add(tip.clone());
                if (feather == 2 || feather == 4) embers.add(tip.clone());
            }
            blue.add(point(body, right, forward, side * 0.25D, 0.28D, -0.12D));
        }
        for (int strand = -1; strand <= 1; strand++) {
            for (int segment = 1; segment <= 6; segment++) {
                double u = segment / 6.0D;
                double wave = Math.sin(animation * 0.8D - u * 4.0D + strand * 0.5D);
                Location location = point(body, right, forward,
                        strand * (0.12D + 0.45D * u) + wave * 0.16D * u,
                        0.15D - 1.25D * u + Math.cos(animation - u * 3.0D) * 0.09D * u,
                        -(2.4D + sweep) * u);
                if (segment % 2 == 1) {
                    (segment <= 2 ? gold : segment <= 4 ? orange : red).add(location);
                } else {
                    (segment == 2 ? flame : small).add(location);
                }
            }
        }
        for (int index = 0; index < 6; index++) {
            double angle = animation * 0.35D + index * Math.PI / 3.0D;
            small.add(point(body, right, forward, Math.cos(angle) * 0.22D,
                    0.30D + index * 0.055D, Math.sin(angle) * 0.18D));
        }
        Map<SharedParticleDefinition, List<Location>> frame = new LinkedHashMap<>();
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_FLAME, flame);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_SMALL_FLAME, small);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_GOLD, gold);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_ORANGE, orange);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_RED, red);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_EMBER, embers);
        frame.put(SharedParticleDefinitions.SKILL_ARCHMAGE_PHOENIX_BLUE, blue);
        return frame;
    }

    /** 遠距離では各層を3点ごとに間引き、輪郭と火の粉だけを残します。 */
    private static Map<SharedParticleDefinition, List<Location>> reduce(Map<SharedParticleDefinition, List<Location>> frame) {
        Map<SharedParticleDefinition, List<Location>> reduced = new LinkedHashMap<>();
        frame.forEach((definition, points) -> {
            List<Location> sampled = new ArrayList<>();
            for (int index = 0; index < points.size(); index += 3) sampled.add(points.get(index));
            reduced.put(definition, sampled);
        });
        return reduced;
    }

    /** 不死鳥の向きを基準とする局所座標をワールド座標へ変換します。 */
    private static Location point(Location body, Vector right, Vector forward, double x, double y, double z) {
        return body.clone().add(right.clone().multiply(x)).add(0.0D, y, 0.0D).add(forward.clone().multiply(z));
    }
}
