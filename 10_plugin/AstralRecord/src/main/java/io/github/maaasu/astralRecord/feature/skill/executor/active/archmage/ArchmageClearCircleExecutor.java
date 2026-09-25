package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 視点先の地面に水平な解除陣を置き、最初に状態異常を解除したプレイヤーで消費します。 */
public final class ArchmageClearCircleExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_clear_circle";
    private static final int DISPLAY_INTERVAL_TICKS = 5;
    private static final double CONTACT_HEIGHT = 0.45D;
    private static final double GROUND_SEARCH_DEPTH = 8.0D;
    private static final double TWO_PI = Math.PI * 2.0D;

    /**
     * 発動スキルの対象判定、解除、粒子、反復タスクを受け取ります。
     * @param services 共有発動スキルサービス
     */
    public ArchmageClearCircleExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "radius");
        if (params.getInt("durationTicks", 0) < 1) {
            throw new SkillParameterException("durationTicks", "クリアサークルの持続時間は1tick以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        Location center = groundAtSight(context, params.getDouble("range", 12.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        double radius = params.getDouble("radius", 3.0D);
        int durationTicks = params.getInt("durationTicks", 120);
        UUID casterId = context.player().getUniqueId();
        String scope = ID + ":circle:" + UUID.randomUUID();
        Sigil sigil = new Sigil(center, radius);
        sigil.draw(context.services());
        context.services().tasks().repeat(
                casterId,
                scope,
                1L,
                1L,
                durationTicks,
                tick -> {
                    if (tick % DISPLAY_INTERVAL_TICKS == 0) {
                        sigil.draw(context.services());
                    }
                    for (AstPlayer target : context.services().targeting()
                            .playersInRadius(center, radius, CONTACT_HEIGHT)) {
                        if (context.services().combat().clearNegativeEffects(target) == 0) {
                            continue;
                        }
                        context.services().effects().point(
                                target.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D),
                                SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_WHITE
                        );
                        context.services().tasks().cancel(casterId, scope);
                        return;
                    }
                }
        );
        return context.success();
    }

    /**
     * 視線が当たった地表、または視点先の下にある地表を返します。
     * @param context 発動者の視線と対象判定
     * @param range 最大設置距離
     * @return 地表からわずかに浮かせた水平面の中心。地面がなければnull
     */
    private static @Nullable Location groundAtSight(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Vector direction = context.direction();
        SkillTargetingService.BlockHit hit = context.services().targeting().blockHit(eye, direction, range);
        Location sight = hit == null ? eye.clone().add(direction.multiply(range)) : hit.location();
        World world = sight.getWorld();
        if (world == null) {
            return null;
        }
        Location ground;
        if (hit != null && hit.normal().getY() > 0.5D) {
            ground = sight;
        } else {
            SkillTargetingService.BlockHit floor = context.services().targeting().blockHit(
                    sight.clone().add(0.0D, 0.5D, 0.0D),
                    new Vector(0.0D, -1.0D, 0.0D),
                    GROUND_SEARCH_DEPTH
            );
            if (floor == null || floor.normal().getY() <= 0.5D) {
                return null;
            }
            ground = floor.location();
        }
        return ground.distanceSquared(eye) > range * range + 1.0E-6D
                ? null
                : ground.add(0.0D, 0.06D, 0.0D);
    }

    /**
     * 必須の正数パラメータを検証します。
     * @param params 解決済みパラメータ
     * @param key 検証するパラメータ名
     * @throws SkillParameterException 値が正数ではない場合
     */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "クリアサークルの params[" + key + "] は正数が必要です");
        }
    }

    /** 添付図の青い二重円、中央の金の星、四方の紋様を単一の水平面へ描きます。 */
    private static final class Sigil {
        private final Location center;
        private final List<Location> cyan = new ArrayList<>();
        private final List<Location> white = new ArrayList<>();
        private final List<Location> gold = new ArrayList<>();

        private Sigil(@NotNull Location center, double radius) {
            this.center = center;
            ring(cyan, radius * 0.95D, 64);
            ring(white, radius * 0.51D, 40);
            diamond(cyan, 0.0D, 0.0D, radius * 0.26D);
            star(gold, 0.0D, 0.0D, radius * 0.22D);
            for (int side = 0; side < 4; side++) {
                double angle = side * Math.PI / 2.0D;
                diamond(cyan, angle, radius * 0.77D, radius * 0.17D);
                star(gold, angle, radius * 0.77D, radius * 0.10D);
                arc(cyan, angle, radius * 0.67D, radius * 0.12D, 11);
                dot(white, angle, radius * 0.54D);
            }
            for (int corner = 0; corner < 4; corner++) {
                double angle = Math.PI / 4.0D + corner * Math.PI / 2.0D;
                cross(cyan, angle, radius * 0.78D, radius * 0.055D);
                dot(gold, angle, radius * 1.04D);
            }
            for (int spoke = 0; spoke < 8; spoke++) {
                double angle = spoke * Math.PI / 4.0D;
                dot(white, angle, radius * 0.50D);
                dot(cyan, angle, radius * 0.96D);
            }
        }

        /** 色ごとに閲覧者を一度だけ解決して描画します。 */
        private void draw(@NotNull ActiveSkillServices services) {
            services.effects().points(center, cyan, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_CYAN);
            services.effects().points(center, white, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_WHITE);
            services.effects().points(center, gold, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_GOLD);
        }

        /** 水平な円を追加します。 */
        private void ring(@NotNull List<Location> points, double radius, int count) {
            for (int index = 0; index < count; index++) {
                dot(points, TWO_PI * index / count, radius);
            }
        }

        /** 四方の菱形を追加します。 */
        private void diamond(@NotNull List<Location> points, double angle, double distance, double size) {
            for (int edge = 0; edge < 4; edge++) {
                double from = edge * Math.PI / 2.0D;
                double to = (edge + 1) * Math.PI / 2.0D;
                for (int step = 0; step < 5; step++) {
                    double t = step / 5.0D;
                    double along = distance + size * (Math.cos(from) * (1.0D - t) + Math.cos(to) * t);
                    double side = size * (Math.sin(from) * (1.0D - t) + Math.sin(to) * t);
                    point(points, Math.cos(angle) * along - Math.sin(angle) * side,
                            Math.sin(angle) * along + Math.cos(angle) * side);
                }
            }
        }

        /** 十字に伸びる光を追加します。 */
        private void star(@NotNull List<Location> points, double angle, double distance, double arm) {
            cross(points, angle, distance, arm);
            cross(points, angle + Math.PI / 4.0D, distance, arm * 0.45D);
        }

        /** 放射方向と接線方向の短い線を追加します。 */
        private void cross(@NotNull List<Location> points, double angle, double distance, double arm) {
            double x = Math.cos(angle) * distance;
            double z = Math.sin(angle) * distance;
            for (int index = -4; index <= 4; index++) {
                double offset = arm * index / 4.0D;
                point(points, x + Math.cos(angle) * offset, z + Math.sin(angle) * offset);
                point(points, x - Math.sin(angle) * offset, z + Math.cos(angle) * offset);
            }
        }

        /** 四方の紋様に沿う短い弧を追加します。 */
        private void arc(@NotNull List<Location> points, double angle, double distance, double width, int count) {
            for (int index = 0; index < count; index++) {
                double t = (index / (double) (count - 1) - 0.5D) * Math.PI;
                double along = distance + width * Math.cos(t);
                double across = width * Math.sin(t);
                point(points, Math.cos(angle) * along - Math.sin(angle) * across,
                        Math.sin(angle) * along + Math.cos(angle) * across);
            }
        }

        /** 半径方向の点を追加します。 */
        private void dot(@NotNull List<Location> points, double angle, double radius) {
            point(points, Math.cos(angle) * radius, Math.sin(angle) * radius);
        }

        /** Y座標を変えずにX/Z平面へ点を追加します。 */
        private void point(@NotNull List<Location> points, double x, double z) {
            points.add(center.clone().add(x, 0.0D, z));
        }
    }
}
