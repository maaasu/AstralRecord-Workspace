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
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 視点先の地面に水平な魔法陣を描き、最初に触れたプレイヤーを回復します。 */
public final class ArchmageHealCircleExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "archmage_heal_circle";
    private static final int DISPLAY_INTERVAL_TICKS = 5;
    private static final double CONTACT_HEIGHT = 0.45D;
    private static final double GROUND_SEARCH_DEPTH = 8.0D;
    private static final double TWO_PI = Math.PI * 2.0D;

    private final SkillService skillService;

    /**
     * 共有発動サービスとクールタイム管理で初期化します。
     * @param services 対象判定、回復、演出、反復タスク
     * @param skillService 回復成立時に発動者のクールタイムを解除するサービス
     */
    public ArchmageHealCircleExecutor(@NotNull ActiveSkillServices services, @NotNull SkillService skillService) {
        super(ID, services);
        this.skillService = skillService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "radius");
        double healPercent = params.getDouble("healPercent", Double.NaN);
        if (!Double.isFinite(healPercent) || healPercent <= 0.0D || healPercent > 100.0D) {
            throw new SkillParameterException("healPercent", "ヒールサークルの回復率は0より大きく100以下が必要です");
        }
        if (params.getInt("durationTicks", 0) < 1) {
            throw new SkillParameterException("durationTicks", "ヒールサークルの維持時間は1tick以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        Location center = groundAtSight(context, params.getDouble("range", 16.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        double radius = params.getDouble("radius", 3.0D);
        double healPercent = params.getDouble("healPercent", 14.0D);
        int durationTicks = params.getInt("durationTicks", 20);
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
                        double maxHp = target.getStatusSnapshot().getMaxValue(StatusType.MAX_HEALTH);
                        HealthRecoveryContext recoveryContext = HealthRecoveryContext.by(
                                context.caster().player(),
                                SkillPresentationUtil.plainName(context.source().skill(), "スキル")
                        );
                        double recovered = context.services().combat().recoverHp(
                                target, maxHp * healPercent / 100.0D, recoveryContext
                        );
                        if (recovered <= 0.0D) {
                            continue;
                        }
                        context.services().effects().point(
                                target.getBukkit().getLocation().add(0.0D, 1.0D, 0.0D),
                                SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL
                        );
                        context.services().tasks().cancel(casterId, scope);
                        skillService.clearCooldown(casterId, ID);
                        return;
                    }
                }
        );
        return context.success();
    }

    /**
     * 視点先の上面またはその下にある地面の位置を返します。
     * @param context 発動者の視線と対象判定
     * @param range 最大設置距離
     * @return 水平に描く地面の位置。地面が見つからなければnull
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
            Location above = sight.clone().add(0.0D, 0.5D, 0.0D);
            SkillTargetingService.BlockHit floor = context.services().targeting().blockHit(
                    above, new Vector(0.0D, -1.0D, 0.0D), GROUND_SEARCH_DEPTH
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
     * @param key 検証するキー
     * @throws SkillParameterException 値が正数でない場合
     */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "ヒールサークルの params[" + key + "] は正数が必要です");
        }
    }

    /** 添付図の同心円、四葉、外側の葉、菱形と十字を一枚の水平面に保持します。 */
    private static final class Sigil {
        private final Location center;
        private final List<Location> mint = new ArrayList<>();
        private final List<Location> cyan = new ArrayList<>();
        private final List<Location> gold = new ArrayList<>();

        private Sigil(@NotNull Location center, double radius) {
            this.center = center;
            ring(mint, radius * 0.52D, 36);
            ring(cyan, radius * 0.60D, 40);
            ring(mint, radius * 0.96D, 64);
            for (int axis = 0; axis < 4; axis++) {
                double angle = axis * Math.PI / 2.0D;
                diamond(mint, angle, radius * 0.87D, radius * 0.10D);
                cross(gold, angle, radius * 0.73D, radius * 0.065D);
                leaf(mint, angle, radius * 0.30D, radius * 0.42D, radius * 0.14D);
            }
            for (int index = 0; index < 8; index++) {
                double angle = (index + 0.5D) * Math.PI / 4.0D;
                leaf(mint, angle, radius * 0.64D, radius * 0.27D, radius * 0.09D);
                dot(gold, angle, radius * 0.60D);
            }
            cross(gold, 0.0D, 0.0D, radius * 0.16D);
        }

        /** 魔法陣の三色を一色ごとに一括送信します。 */
        private void draw(@NotNull ActiveSkillServices services) {
            services.effects().points(center, mint, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_MINT);
            services.effects().points(center, cyan, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_CYAN);
            services.effects().points(center, gold, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_GOLD);
        }

        /** 水平な同心円の点を追加します。 */
        private void ring(List<Location> points, double radius, int count) {
            for (int index = 0; index < count; index++) {
                dot(points, TWO_PI * index / count, radius);
            }
        }

        /** 放射方向の葉の両側の輪郭を追加します。 */
        private void leaf(List<Location> points, double angle, double distance, double length, double width) {
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            for (int index = 0; index <= 8; index++) {
                double t = index / 8.0D;
                double along = distance + length * t;
                double side = width * Math.sin(Math.PI * t);
                point(points, ux * along - uz * side, uz * along + ux * side);
                point(points, ux * along + uz * side, uz * along - ux * side);
            }
        }

        /** 放射方向の菱形を追加します。 */
        private void diamond(List<Location> points, double angle, double distance, double size) {
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            for (int edge = 0; edge < 4; edge++) {
                double from = edge * Math.PI / 2.0D;
                double to = (edge + 1) * Math.PI / 2.0D;
                for (int step = 0; step < 3; step++) {
                    double t = step / 3.0D;
                    double along = distance + size * ((1.0D - t) * Math.cos(from) + t * Math.cos(to));
                    double side = size * ((1.0D - t) * Math.sin(from) + t * Math.sin(to));
                    point(points, ux * along - uz * side, uz * along + ux * side);
                }
            }
        }

        /** 放射方向と接線方向に伸びる十字を追加します。 */
        private void cross(List<Location> points, double angle, double distance, double arm) {
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            for (int index = -3; index <= 3; index++) {
                double offset = arm * index / 3.0D;
                point(points, ux * (distance + offset), uz * (distance + offset));
                point(points, ux * distance - uz * offset, uz * distance + ux * offset);
            }
        }

        /** ミント色の放射点を追加します。 */
        private void dot(double angle, double radius) {
            dot(mint, angle, radius);
        }

        /** 任意の色の放射点を追加します。 */
        private void dot(List<Location> points, double angle, double radius) {
            point(points, Math.cos(angle) * radius, Math.sin(angle) * radius);
        }

        /** 高さを変えずに局所X/Z座標の粒子を追加します。 */
        private void point(List<Location> points, double x, double z) {
            points.add(center.clone().add(x, 0.0D, z));
        }
    }
}
