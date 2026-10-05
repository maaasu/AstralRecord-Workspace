package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 交差する三条の魔力を前方へ放つ、無属性の貫通魔法です。 */
public final class WizardArcaneLanceExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_arcane_lance";
    private static final int FRAME_INTERVAL_TICKS = 2;
    private static final int FLIGHT_FRAMES = 6;
    private static final int AFTERGLOW_FRAMES = 3;
    private static final double TRAIL_LENGTH = 5.0D;
    private static final double SAMPLE_INTERVAL = 0.18D;
    private static final double CROSSING_DISTANCE = 3.0D;
    private static final SharedParticleDefinition[] STRANDS = {
            SharedParticleDefinitions.WIZARD_ARCANE_LANCE_VIOLET,
            SharedParticleDefinitions.WIZARD_ARCANE_LANCE_AZURE,
            SharedParticleDefinitions.WIZARD_ARCANE_LANCE_WHITE
    };

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardArcaneLanceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        animateLance(context);
        for (var target : line(context)) {
            hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
        }
        return context.success();
    }

    /** 発動時の射線を固定し、有限の飛翔演出を発動者のlifecycleへ登録します。 */
    private static void animateLance(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Vector forward = context.direction();
        Location end = context.services().targeting().clippedEnd(origin, forward, value(context, "range"));
        double distance = origin.distance(end);
        Vector reference = Math.abs(forward.getY()) > 0.95D
                ? new Vector(1.0D, 0.0D, 0.0D) : new Vector(0.0D, 1.0D, 0.0D);
        Vector right = forward.clone().crossProduct(reference).normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        UUID casterId = context.player().getUniqueId();
        String scope = ID + ":visual:" + UUID.randomUUID();

        drawRing(context, origin, right, up, Math.min(0.5D, distance), 0.48D, 0.0D,
                SharedParticleDefinitions.WIZARD_ARCANE_LANCE_VIOLET);
        context.services().tasks().repeat(casterId, scope, 0L, FRAME_INTERVAL_TICKS,
                FLIGHT_FRAMES + AFTERGLOW_FRAMES, frame -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != origin.getWorld()) {
                        context.services().tasks().cancel(casterId, scope);
                        return;
                    }
                    if (frame < FLIGHT_FRAMES) {
                        double front = distance * (frame + 1.0D) / FLIGHT_FRAMES;
                        drawFlight(context, origin, forward, right, up, front, frame);
                        if (frame < 2) {
                            drawRing(context, origin, right, up, Math.min(0.5D, distance),
                                    0.34D - frame * 0.12D, frame * 0.35D,
                                    SharedParticleDefinitions.WIZARD_ARCANE_LANCE_AZURE);
                        }
                    } else {
                        int glowFrame = frame - FLIGHT_FRAMES;
                        // 遮蔽面の手前へ残光を置き、斜めの壁では埋まる点を除外します。
                        Location glow = end.clone().subtract(forward.clone().multiply(Math.min(0.12D, distance)));
                        drawRing(context, glow, right, up, 0.0D,
                                0.24D + glowFrame * 0.18D, glowFrame * 0.3D,
                                SharedParticleDefinitions.WIZARD_ARCANE_LANCE_WHITE);
                        if (glowFrame == 0) {
                            drawBurst(context, glow, right, up);
                        }
                    }
                });
    }

    /** 三条を等位相で編み込み、一定距離ごとに射線上で交差させて先端へ収束します。 */
    private static void drawFlight(@NotNull PlayerActiveSkillContext context, @NotNull Location origin,
                                   @NotNull Vector forward, @NotNull Vector right, @NotNull Vector up,
                                   double front, int frame) {
        double start = Math.max(0.0D, front - TRAIL_LENGTH);
        int samples = Math.max(1, (int) Math.ceil((front - start) / SAMPLE_INTERVAL));
        for (int strand = 0; strand < STRANDS.length; strand++) {
            List<Location> points = new ArrayList<>(samples + 1);
            for (int sample = 0; sample <= samples; sample++) {
                double along = start + (front - start) * sample / samples;
                double taper = Math.min(1.0D, (front - along) / 0.85D);
                double radius = 0.46D * Math.sin(Math.PI * along / CROSSING_DISTANCE) * taper;
                double angle = Math.PI * 2.0D * along / CROSSING_DISTANCE
                        + strand * Math.PI * 2.0D / STRANDS.length;
                Location point = origin.clone().add(forward.clone().multiply(along))
                        .add(right.clone().multiply(Math.cos(angle) * radius))
                        .add(up.clone().multiply(Math.sin(angle) * radius));
                addVisible(points, point);
            }
            context.services().effects().points(origin, points, STRANDS[strand]);
        }
        List<Location> sparks = new ArrayList<>(4);
        for (int spark = 0; spark < 4; spark++) {
            double along = Math.max(start, front - spark * 0.28D);
            addVisible(sparks, origin.clone().add(forward.clone().multiply(along)));
        }
        context.services().effects().points(origin, sparks, SharedParticleDefinitions.WIZARD_ARCANE_LANCE_SPARK);
        if (frame == FLIGHT_FRAMES - 1) {
            drawRing(context, origin, right, up, front, 0.16D, 0.0D,
                    SharedParticleDefinitions.WIZARD_ARCANE_LANCE_WHITE);
        }
    }

    /** 射線に垂直な24点の光輪を一括表示します。瞬間演出のため魔法陣には登録しません。 */
    private static void drawRing(@NotNull PlayerActiveSkillContext context, @NotNull Location origin,
                                 @NotNull Vector right, @NotNull Vector up, double along,
                                 double radius, double rotation, @NotNull SharedParticleDefinition particle) {
        Vector forward = up.clone().crossProduct(right).normalize();
        Location center = origin.clone().add(forward.multiply(along));
        List<Location> points = new ArrayList<>(24);
        for (int point = 0; point < 24; point++) {
            double angle = rotation + Math.PI * 2.0D * point / 24.0D;
            addVisible(points, center.clone().add(right.clone().multiply(Math.cos(angle) * radius))
                    .add(up.clone().multiply(Math.sin(angle) * radius)));
        }
        context.services().effects().points(center, points, particle);
    }

    /** 終端の白い火花を八方向へ配置し、地形とchunkを確認して一括表示します。 */
    private static void drawBurst(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                                  @NotNull Vector right, @NotNull Vector up) {
        List<Location> points = new ArrayList<>(8);
        for (int point = 0; point < 8; point++) {
            double angle = Math.PI * 2.0D * point / 8.0D;
            addVisible(points, center.clone().add(right.clone().multiply(Math.cos(angle) * 0.12D))
                    .add(up.clone().multiply(Math.sin(angle) * 0.12D)));
        }
        context.services().effects().points(center, points, SharedParticleDefinitions.WIZARD_ARCANE_LANCE_BURST);
    }

    /** 未読込chunkと地形内部を避け、表示できる粒子座標だけを追加します。 */
    private static void addVisible(@NotNull List<Location> points, @NotNull Location point) {
        var world = point.getWorld();
        if (world != null && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                && point.getBlock().isPassable()) {
            points.add(point);
        }
    }
}
