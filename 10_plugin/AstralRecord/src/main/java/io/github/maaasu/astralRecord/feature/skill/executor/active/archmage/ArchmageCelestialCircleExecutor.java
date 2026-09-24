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
        context.services().tasks().cancel(casterId, ID);
        runtimeService.start(casterId, center, params.getInt("effectLevel", 1), params.getInt("durationTicks", 600));
        try {
            context.services().tasks().repeat(
                    casterId, ID, 0L, 1L, Integer.MAX_VALUE,
                    tick -> advance(context, casterId, tick),
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
     */
    private void advance(@NotNull PlayerActiveSkillContext context, @NotNull UUID casterId, int tick) {
        if (!runtimeService.advance(casterId)) {
            context.services().tasks().cancel(casterId, ID);
            return;
        }
        ArchmageCelestialCircleRuntimeService.Snapshot snapshot = runtimeService.snapshot(casterId);
        if (snapshot == null || tick % (snapshot.complete() ? 20 : DRAW_INTERVAL_TICKS) != 0) {
            return;
        }
        render(context.services(), snapshot);
    }

    /**
     * 添付図の同心円、八芒星、四方の星、月弧を3色の水平粒子として描きます。
     *
     * @param services 粒子を一括配信するサービス
     * @param snapshot 描画時の中心、半径、構築率
     */
    private static void render(@NotNull ActiveSkillServices services,
                               @NotNull ArchmageCelestialCircleRuntimeService.Snapshot snapshot) {
        Location center = snapshot.center().add(0.0D, 0.10D, 0.0D);
        if (center.getWorld() == null || center.getWorld().getPlayers().stream()
                .noneMatch(player -> player.getLocation().distanceSquared(center) <= 64.0D * 64.0D)) {
            return;
        }
        double radius = snapshot.level();
        double progress = radius * 1.05D * snapshot.progress();
        List<Location> azure = new ArrayList<>();
        List<Location> violet = new ArrayList<>();
        List<Location> red = new ArrayList<>();
        ring(center, radius * 0.97D, 48, progress, azure);
        ring(center, radius * 0.84D, 40, progress, violet);
        ring(center, radius * 0.48D, 32, progress, violet);
        ring(center, radius * 0.27D, 24, progress, azure);
        for (int index = 0; index < 8; index++) {
            double angle = index * Math.PI / 4.0D;
            line(center, radius * 0.24D, angle, radius * 0.70D, angle, progress, azure);
            line(center, radius * 0.70D, angle, radius * 0.79D, angle + Math.PI / 18.0D, progress, violet);
            line(center, radius * 0.79D, angle + Math.PI / 18.0D,
                    radius * 0.70D, angle + Math.PI / 9.0D, progress, violet);
        }
        for (int index = 0; index < 4; index++) {
            double angle = index * Math.PI / 2.0D;
            star(center, radius * 0.90D, angle, radius * 0.12D, progress, azure);
            star(center, radius * 0.90D, angle, radius * 0.045D, progress, red);
            crescent(center, radius * 0.57D, angle + Math.PI / 4.0D, radius * 0.09D, progress, azure);
        }
        star(center, 0.0D, 0.0D, radius * 0.24D, progress, azure);
        star(center, 0.0D, 0.0D, radius * 0.065D, progress, red);
        services.effects().points(center, azure, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_AZURE);
        services.effects().points(center, violet, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET);
        services.effects().points(center, red, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_RED);
    }

    /** 半径方向へ広がる円周の点を追加します。 */
    private static void ring(Location center, double radius, int count, double progress, List<Location> output) {
        for (int index = 0; index < count; index++) {
            double angle = index * Math.PI * 2.0D / count;
            point(center, radius, angle, progress, output);
        }
    }

    /** 極座標の二点間に粒子を追加します。 */
    private static void line(Location center, double fromRadius, double fromAngle,
                             double toRadius, double toAngle, double progress, List<Location> output) {
        for (int index = 0; index <= 5; index++) {
            double ratio = index / 5.0D;
            point(center, fromRadius + (toRadius - fromRadius) * ratio,
                    fromAngle + (toAngle - fromAngle) * ratio, progress, output);
        }
    }

    /** 四方と中央の八芒星を追加します。 */
    private static void star(Location center, double offsetRadius, double angle, double size,
                             double progress, List<Location> output) {
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
                cartesian(center, px, pz, progress, output);
            }
        }
    }

    /** 四象限の月形の弧を追加します。 */
    private static void crescent(Location center, double offsetRadius, double angle, double size,
                                 double progress, List<Location> output) {
        double x = Math.cos(angle) * offsetRadius;
        double z = Math.sin(angle) * offsetRadius;
        for (int index = 0; index <= 8; index++) {
            double sweep = -Math.PI * 0.65D + index * Math.PI * 1.3D / 8.0D;
            cartesian(center, x + Math.cos(sweep) * size, z + Math.sin(sweep) * size,
                    progress, output);
            cartesian(center, x + size * 0.35D + Math.cos(sweep) * size * 0.72D,
                    z + Math.sin(sweep) * size * 0.72D, progress, output);
        }
    }

    /** 構築済み半径内の極座標点だけを追加します。 */
    private static void point(Location center, double radius, double angle,
                              double progress, List<Location> output) {
        cartesian(center, radius * Math.cos(angle), radius * Math.sin(angle), progress, output);
    }

    /** 同じY座標を維持して粒子点を追加します。 */
    private static void cartesian(Location center, double x, double z,
                                  double progress, List<Location> output) {
        if (x * x + z * z <= progress * progress + 1.0E-8D) {
            output.add(center.clone().add(x, 0.0D, z));
        }
    }
}
