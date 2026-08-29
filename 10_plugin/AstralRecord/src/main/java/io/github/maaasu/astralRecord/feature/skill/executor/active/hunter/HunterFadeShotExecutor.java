package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 前方へ散弾状の矢を放ち、安全な位置へ素早く後退するハンタースキルです。 */
public final class HunterFadeShotExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_fade_shot";
    private static final int MIN_PELLET_COUNT = 3;
    private static final int MAX_PELLET_COUNT = 9;
    private static final double MAX_SPREAD_ANGLE = 60.0D;
    private static final double DEFAULT_BACKSTEP_VELOCITY = 0.35D;

    /** 共有発動スキルサービスで初期化します。 */
    public HunterFadeShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        requirePositive(params, "backstepVelocity");
        int pelletCount = params.getInt("pelletCount", 0);
        if (pelletCount < MIN_PELLET_COUNT || pelletCount > MAX_PELLET_COUNT || pelletCount % 2 == 0) {
            throw new SkillParameterException(
                    "pelletCount",
                    "フェイドショットの params[pelletCount] は3以上9以下の奇数が必要です"
            );
        }
        double spreadAngle = params.getDouble("spreadAngle", 0.0D);
        if (!(spreadAngle > 0.0D) || spreadAngle > MAX_SPREAD_ANGLE) {
            throw new SkillParameterException(
                    "spreadAngle",
                    "フェイドショットの params[spreadAngle] は0より大きく60以下が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 9.0D);
        double damageRatio = params.getDouble("damageRatio", 0.32D);
        int pelletCount = params.getInt("pelletCount", 5);
        double spreadAngle = params.getDouble("spreadAngle", 30.0D);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.8D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.30D);
        double backstepVelocity = params.getDouble("backstepVelocity", DEFAULT_BACKSTEP_VELOCITY);
        AstEntity attacker = context.attacker();
        Location origin = context.eyeLocation();
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range, projectileSpeed, projectileHitRadius, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );

        pelletDirections(context.direction(), pelletCount, spreadAngle).forEach(direction ->
                context.services().projectiles().launch(
                        context.player(), origin, direction, projectile,
                        (target, ignored) -> context.services().combat().hit(
                                attacker, target, AttackType.RANGED, DamageElement.NONE, damageRatio
                        ),
                        ignored -> { }
                )
        );

        Vector movementVelocity = context.services().movement()
                .backstepVelocity(context.player(), attacker, backstepVelocity);
        if (movementVelocity != null) {
            context.services().effects().line(
                    origin.clone().add(0.0D, -1.47D, 0.0D),
                    origin.clone().add(movementVelocity).add(0.0D, -1.47D, 0.0D),
                    0.35D,
                    SharedParticleDefinitions.HUNTER_FADE_SHOT_STEP
            );
        }
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.85F);
        return context.success();
    }

    /**
     * 視線方向を中心に、視線へ直交する横軸上へ等間隔の散弾方向を生成します。
     *
     * @param forward 発動時の視線方向
     * @param pelletCount 散弾数
     * @param spreadAngleDegrees 両端を含む全角
     * @return 中央を含み左右対称に並ぶ単位方向ベクトル
     */
    static @NotNull List<Vector> pelletDirections(
            @NotNull Vector forward,
            int pelletCount,
            double spreadAngleDegrees
    ) {
        Vector normalizedForward = forward.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : forward.clone().normalize();
        Vector right = normalizedForward.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() <= 1.0E-8D) {
            right.setX(1.0D);
        } else {
            right.normalize();
        }
        int safeCount = Math.max(1, pelletCount);
        double safeSpread = Math.max(0.0D, spreadAngleDegrees);
        List<Vector> directions = new ArrayList<>(safeCount);
        for (int index = 0; index < safeCount; index++) {
            double fraction = safeCount == 1 ? 0.5D : (double) index / (safeCount - 1);
            double angle = Math.toRadians((-safeSpread / 2.0D) + (safeSpread * fraction));
            directions.add(normalizedForward.clone().multiply(Math.cos(angle))
                    .add(right.clone().multiply(Math.sin(angle)))
                    .normalize());
        }
        return List.copyOf(directions);
    }

    /**
     * 指定した数値paramが正数であることを検証します。
     *
     * @param params 検証対象のスキルparam reader
     * @param key 検証するparam key
     * @throws SkillParameterException 値が0以下または数値として解決できない場合
     */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "フェイドショットの params[" + key + "] は正数が必要です");
        }
    }
}
