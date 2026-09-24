package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 視点先の地面へ水平の魔法陣を構築するアークメイジ発動スキルです。 */
public final class ArchmageCelestialCircleExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_celestial_circle";
    private static final int MAX_LEVEL = 10;
    private static final int DRAW_INTERVAL_TICKS = 4;
    private static final int COMPLETE_DRAW_INTERVAL_TICKS = 5;
    private final ArchmageCelestialCircleRuntimeService runtimeService;

    /**
     * 実行時状態と共通発動サービスを受け取ります。
     *
     * @param services 共通発動サービス
     * @param runtimeService 魔法陣とバフの状態
     */
    public ArchmageCelestialCircleExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull ArchmageCelestialCircleRuntimeService runtimeService
    ) {
        super(ID, services);
        this.runtimeService = runtimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        int level = params.getInt("effectLevel", 0);
        if (level < 1 || level > MAX_LEVEL || params.getDouble("radius", 0.0D) != level) {
            throw new SkillParameterException("effectLevel", "セレスティアルサークルのレベルと半径は1〜10の同じ値が必要です");
        }
        if (params.getDouble("placementRange", 0.0D) <= 0.0D || params.getInt("durationTicks", 0) < 1) {
            throw new SkillParameterException("placementRange", "セレスティアルサークルの射程と持続時間は正数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        Location center = groundAtSight(context, params.getDouble("placementRange", 24.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        UUID casterId = context.player().getUniqueId();
        Sigil visual = new Sigil(center, params.getInt("effectLevel", 1));
        context.services().tasks().cancel(casterId, ID);
        runtimeService.start(casterId, center, params.getInt("effectLevel", 1), params.getInt("durationTicks", 600));
        try {
            context.services().tasks().repeat(
                    casterId, ID, 0L, 1L, Integer.MAX_VALUE,
                    tick -> advance(context, casterId, tick, visual),
                    () -> runtimeService.end(casterId)
            );
        } catch (RuntimeException exception) {
            runtimeService.end(casterId);
            throw exception;
        }
        return context.success();
    }

    /**
     * 視線が指す水平位置で歩行できる地面を探します。
     *
     * @param context 発動者と地形判定サービス
     * @param range 視線の最大距離
     * @return 地面上の中心。発見できなければnull
     */
    private static @Nullable Location groundAtSight(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Location impact = context.services().targeting().blockImpact(eye, context.direction(), range);
        Location probe = impact == null ? eye.clone().add(context.direction().multiply(range)) : impact;
        Location ground = context.services().targeting().groundAt(probe, 3, 32);
        Block floor = ground.clone().subtract(0.0D, 0.20D, 0.0D).getBlock();
        if (floor.isPassable() || !ground.getBlock().isPassable() || eye.distance(ground) > range) {
            return null;
        }
        return ground;
    }

    /**
     * 構築・維持を進め、表示間隔が来た場合だけ水平の図形を描画します。
     *
     * @param context 発動者の共有表示サービス
     * @param casterId 発動者UUID
     * @param tick 実行経過tick
     * @param visual 発動時に計算済みの水平魔法陣
     */
    private void advance(@NotNull PlayerActiveSkillContext context, @NotNull UUID casterId,
                         int tick, @NotNull Sigil visual) {
        if (!runtimeService.advance(casterId)) {
            context.services().tasks().cancel(casterId, ID);
            return;
        }
        ArchmageCelestialCircleRuntimeService.Snapshot snapshot = runtimeService.snapshot(casterId);
        if (snapshot == null || tick % (snapshot.complete() ? COMPLETE_DRAW_INTERVAL_TICKS : DRAW_INTERVAL_TICKS) != 0) {
            return;
        }
        visual.draw(context.services(), snapshot.progress(), snapshot.complete());
    }

    /** 半径に応じて円周の点を増やし、隣接点の間隔を約0.42m以下に抑えます。 */
    private static int ringSamples(double radius, int minimum) {
        return Math.max(minimum, (int) Math.ceil(Math.PI * 2.0D * radius / 0.42D));
    }

    /** 半径方向へ広がる円周の点を追加します。 */
    private static void ring(Location center, double radius, int count, List<Location> output) {
        for (int index = 0; index < count; index++) {
            double angle = index * Math.PI * 2.0D / count;
            point(center, radius, angle, output);
        }
    }

    /** 極座標の二点間に粒子を追加します。 */
    private static void line(Location center, double fromRadius, double fromAngle,
                             double toRadius, double toAngle, List<Location> output) {
        double dx = Math.cos(toAngle) * toRadius - Math.cos(fromAngle) * fromRadius;
        double dz = Math.sin(toAngle) * toRadius - Math.sin(fromAngle) * fromRadius;
        int segments = Math.max(5, (int) Math.ceil(Math.hypot(dx, dz) / 0.45D));
        for (int index = 0; index <= segments; index++) {
            double ratio = (double) index / segments;
            point(center, fromRadius + (toRadius - fromRadius) * ratio,
                    fromAngle + (toAngle - fromAngle) * ratio, output);
        }
    }

    /** 四方と中央の八芒星を追加します。 */
    private static void star(Location center, double offsetRadius, double angle, double size,
                             List<Location> output) {
        double x = Math.cos(angle) * offsetRadius;
        double z = Math.sin(angle) * offsetRadius;
        for (int index = 0; index < 8; index++) {
            double a = index * Math.PI / 4.0D;
            double b = (index + 1) * Math.PI / 4.0D;
            double aRadius = index % 2 == 0 ? size : size * 0.34D;
            double bRadius = (index + 1) % 2 == 0 ? size : size * 0.34D;
            for (int step = 0; step < 2; step++) {
                double ratio = step / 2.0D;
                double px = x + Math.cos(a) * aRadius * (1.0D - ratio) + Math.cos(b) * bRadius * ratio;
                double pz = z + Math.sin(a) * aRadius * (1.0D - ratio) + Math.sin(b) * bRadius * ratio;
                cartesian(center, px, pz, output);
            }
        }
    }

    /** 四象限の月形の弧を追加します。 */
    private static void crescent(Location center, double offsetRadius, double angle, double size,
                                 List<Location> output) {
        double x = Math.cos(angle) * offsetRadius;
        double z = Math.sin(angle) * offsetRadius;
        for (int index = 0; index <= 8; index++) {
            double sweep = -Math.PI * 0.65D + index * Math.PI * 1.3D / 8.0D;
            cartesian(center, x + Math.cos(sweep) * size, z + Math.sin(sweep) * size,
                    output);
            cartesian(center, x + size * 0.35D + Math.cos(sweep) * size * 0.72D,
                    z + Math.sin(sweep) * size * 0.72D, output);
        }
    }

    /** 水平面の極座標点を追加します。 */
    private static void point(Location center, double radius, double angle, List<Location> output) {
        cartesian(center, radius * Math.cos(angle), radius * Math.sin(angle), output);
    }

    /** 同じY座標を維持して粒子点を追加します。 */
    private static void cartesian(Location center, double x, double z, List<Location> output) {
        output.add(center.clone().add(x, 0.0D, z));
    }

    /** 発動ごとに一度作った粒子座標を再利用し、完成後は点群を作り直さずに表示します。 */
    private static final class Sigil {
        private final Location center;
        private final double radius;
        private final List<Location> azure;
        private final List<Location> violet;
        private final List<Location> red;

        /**
         * 指定された地面の高さへ図形を固定し、半径に応じた点数を計算します。
         *
         * @param ground 地面上の魔法陣中心
         * @param level 半径と同じスキルレベル
         */
        private Sigil(@NotNull Location ground, int level) {
            center = ground.clone().add(0.0D, 0.10D, 0.0D);
            radius = level;
            List<Location> azurePoints = new ArrayList<>();
            List<Location> violetPoints = new ArrayList<>();
            List<Location> redPoints = new ArrayList<>();
            ring(center, radius * 0.97D, ringSamples(radius * 0.97D, 48), azurePoints);
            ring(center, radius * 0.84D, ringSamples(radius * 0.84D, 40), violetPoints);
            ring(center, radius * 0.48D, ringSamples(radius * 0.48D, 32), violetPoints);
            ring(center, radius * 0.27D, ringSamples(radius * 0.27D, 24), azurePoints);
            for (int index = 0; index < 8; index++) {
                double angle = index * Math.PI / 4.0D;
                line(center, radius * 0.24D, angle, radius * 0.70D, angle, azurePoints);
                line(center, radius * 0.70D, angle, radius * 0.79D,
                        angle + Math.PI / 18.0D, violetPoints);
                line(center, radius * 0.79D, angle + Math.PI / 18.0D,
                        radius * 0.70D, angle + Math.PI / 9.0D, violetPoints);
            }
            for (int index = 0; index < 4; index++) {
                double angle = index * Math.PI / 2.0D;
                star(center, radius * 0.90D, angle, radius * 0.12D, azurePoints);
                star(center, radius * 0.90D, angle, radius * 0.045D, redPoints);
                crescent(center, radius * 0.57D, angle + Math.PI / 4.0D,
                        radius * 0.09D, azurePoints);
            }
            star(center, 0.0D, 0.0D, radius * 0.24D, azurePoints);
            star(center, 0.0D, 0.0D, radius * 0.065D, redPoints);
            azure = List.copyOf(azurePoints);
            violet = List.copyOf(violetPoints);
            red = List.copyOf(redPoints);
        }

        /**
         * 完成時はキャッシュ済みの全点、構築中は進行半径内の点だけを一括描画します。
         *
         * @param services 近傍プレイヤーへの粒子表示サービス
         * @param progress 0から1までの構築進行率
         * @param complete 構築が完了している場合はtrue
         */
        private void draw(@NotNull ActiveSkillServices services, double progress, boolean complete) {
            if (center.getWorld() == null || center.getWorld().getPlayers().stream()
                    .noneMatch(player -> player.getLocation().distanceSquared(center) <= 64.0D * 64.0D)) {
                return;
            }
            double revealSquared = Math.pow(radius * 1.05D * progress, 2.0D);
            services.effects().points(center, complete ? azure : visible(azure, revealSquared),
                    SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_AZURE);
            services.effects().points(center, complete ? violet : visible(violet, revealSquared),
                    SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET);
            services.effects().points(center, complete ? red : visible(red, revealSquared),
                    SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_RED);
        }

        /** 構築済み半径内だけのキャッシュ済み座標を返します。 */
        private List<Location> visible(List<Location> points, double revealSquared) {
            List<Location> result = new ArrayList<>();
            for (Location point : points) {
                double dx = point.getX() - center.getX();
                double dz = point.getZ() - center.getZ();
                if (dx * dx + dz * dz <= revealSquared + 1.0E-8D) {
                    result.add(point);
                }
            }
            return result;
        }
    }
}
