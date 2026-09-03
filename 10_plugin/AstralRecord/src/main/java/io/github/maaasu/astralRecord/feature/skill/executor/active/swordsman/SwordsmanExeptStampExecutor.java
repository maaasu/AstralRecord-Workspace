package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;

/** 前方へ飛び上がり、空中でのスニーク入力を合図に急降下するソードマンの範囲攻撃です。 */
public final class SwordsmanExeptStampExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_exept_stamp";
    private static final String FLIGHT_SCOPE = ID + ":flight";
    private static final double DEFAULT_RADIUS = 1.0D;
    private static final double DEFAULT_DAMAGE_RATIO = 1.10D;
    private static final double DEFAULT_MOVEMENT_SPEED_REDUCTION = 10.0D;
    private static final int DEFAULT_MOVEMENT_SPEED_DEBUFF_DURATION_TICKS = 100;
    private static final double DEFAULT_LAUNCH_HORIZONTAL_VELOCITY = 0.65D;
    private static final double DEFAULT_LAUNCH_VERTICAL_VELOCITY = 1.05D;
    private static final double DEFAULT_DIVE_VELOCITY = 2.40D;
    private static final int DEFAULT_MAX_FLIGHT_TICKS = 100;
    private static final int LANDING_BLOCK_DUST_POINTS = 8;
    private static final double LANDING_BLOCK_DUST_RADIUS = 0.8D;
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ZERO,
            Duration.ofSeconds(5L),
            Duration.ofMillis(200L)
    );

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanExeptStampExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositive(params, "damageRatio");
        requirePositive(params, "movementSpeedReduction");
        requirePositive(params, "launchHorizontalVelocity");
        requirePositive(params, "launchVerticalVelocity");
        requirePositive(params, "diveVelocity");
        if (params.getInt("movementSpeedDebuffDurationTicks", 0) < 1) {
            throw new SkillParameterException(
                    "movementSpeedDebuffDurationTicks",
                    "エクゼプトスタンプの移動速度減少時間は1 tick以上が必要です"
            );
        }
        if (params.getInt("maxFlightTicks", 0) < 1) {
            throw new SkillParameterException(
                    "maxFlightTicks",
                    "エクゼプトスタンプの飛翔待機時間は1 tick以上が必要です"
            );
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        AstEntity attacker = context.attacker();
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", DEFAULT_RADIUS);
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double movementSpeedReduction = params.getDouble(
                "movementSpeedReduction", DEFAULT_MOVEMENT_SPEED_REDUCTION
        );
        int movementSpeedDebuffDurationTicks = params.getInt(
                "movementSpeedDebuffDurationTicks",
                DEFAULT_MOVEMENT_SPEED_DEBUFF_DURATION_TICKS
        );
        double launchHorizontalVelocity = params.getDouble(
                "launchHorizontalVelocity", DEFAULT_LAUNCH_HORIZONTAL_VELOCITY
        );
        double launchVerticalVelocity = params.getDouble(
                "launchVerticalVelocity", DEFAULT_LAUNCH_VERTICAL_VELOCITY
        );
        double diveVelocity = params.getDouble("diveVelocity", DEFAULT_DIVE_VELOCITY);
        int maxFlightTicks = params.getInt("maxFlightTicks", DEFAULT_MAX_FLIGHT_TICKS);
        World castWorld = player.getWorld();

        Vector forward = horizontal(context.direction(), player.getLocation().getYaw());
        Vector launchVelocity = forward.multiply(launchHorizontalVelocity)
                .setY(launchVerticalVelocity);
        if (context.services().movement().velocity(player, attacker, launchVelocity) == null) {
            return context.success();
        }

        player.showTitle(Title.title(
                Component.empty(),
                PlayerMsgResource.getComponent(PlayerMsgId.P_7130.getId()),
                TITLE_TIMES
        ));
        FlightState state = new FlightState(player.isSneaking());
        context.services().tasks().repeat(
                player.getUniqueId(),
                FLIGHT_SCOPE,
                1L,
                1L,
                maxFlightTicks,
                tick -> tickFlight(
                        context,
                        attacker,
                        castWorld,
                        state,
                        radius,
                        damageRatio,
                        movementSpeedReduction,
                        movementSpeedDebuffDurationTicks,
                        diveVelocity
                ),
                player::clearTitle
        );
        return context.success();
    }

    /**
     * 飛翔中のプレイヤーを監視し、空中スニーク開始時の急降下と着地を処理します。
     * 着地までに急降下へ遷移しなかった場合は攻撃せず、飛翔用 task だけを終了します。
     */
    @SuppressWarnings("deprecation")
    private void tickFlight(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull World castWorld,
            @NotNull FlightState state,
            double radius,
            double damageRatio,
            double movementSpeedReduction,
            int movementSpeedDebuffDurationTicks,
            double diveVelocity
    ) {
        Player player = context.player();
        if (!player.isOnline() || player.isDead() || player.getWorld() != castWorld) {
            context.services().tasks().cancel(player.getUniqueId(), FLIGHT_SCOPE);
            return;
        }

        boolean onGround = player.isOnGround();
        boolean sneaking = player.isSneaking();
        if (!state.leftGround()) {
            if (!onGround) {
                state.leftGround(true);
            } else {
                state.previousSneaking(sneaking);
                return;
            }
        }

        if (!onGround && !state.diving() && sneaking && !state.previousSneaking()) {
            Vector diveVelocityVector = new Vector(0.0D, -diveVelocity, 0.0D);
            if (context.services().movement().velocity(player, attacker, diveVelocityVector) != null) {
                state.diving(true);
                context.services().effects().sound(
                        player.getLocation(),
                        Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                        1.0F,
                        0.55F
                );
            }
        }
        state.previousSneaking(sneaking);

        if (onGround) {
            if (state.diving()) {
                land(
                        context,
                        attacker,
                        radius,
                        damageRatio,
                        movementSpeedReduction,
                        movementSpeedDebuffDurationTicks
                );
            }
            context.services().tasks().cancel(player.getUniqueId(), FLIGHT_SCOPE);
        }
    }

    /** 着地点を中心に範囲攻撃を行い、実ダメージが通ったMobへ移動速度減少を付与します。 */
    private void land(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            double radius,
            double damageRatio,
            double movementSpeedReduction,
            int movementSpeedDebuffDurationTicks
    ) {
        Player player = context.player();
        Location impact = context.services().targeting().groundAt(player.getLocation(), 2, 2);
        List<AstEntity> targets = context.services().targeting().inRadius(
                player,
                impact,
                radius,
                radius,
                Integer.MAX_VALUE,
                true
        );
        for (AstEntity target : targets) {
            DamageResult result = context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    damageRatio
            );
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                context.services().combat().applyTemporaryMovementSpeedReduction(
                        target,
                        movementSpeedReduction,
                        movementSpeedDebuffDurationTicks
                );
            }
        }
        renderLandingEffects(context, impact, radius);
    }

    /** 地面のブロック材質を使った破片、crit、茶色dust、着地音を表示します。 */
    private void renderLandingEffects(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location impact,
            double radius
    ) {
        World world = impact.getWorld();
        if (world == null) {
            return;
        }
        Location floor = impact.clone().subtract(0.0D, 0.15D, 0.0D);
        BlockData blockData = floor.getBlock().getBlockData();
        for (int index = 0; index < LANDING_BLOCK_DUST_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / LANDING_BLOCK_DUST_POINTS;
            context.services().effects().blockDust(
                    floor.clone().add(
                            Math.cos(angle) * LANDING_BLOCK_DUST_RADIUS,
                            0.0D,
                            Math.sin(angle) * LANDING_BLOCK_DUST_RADIUS
                    ),
                    blockData
            );
        }
        context.services().effects().point(
                impact.clone().add(0.0D, 0.18D, 0.0D),
                SharedParticleDefinitions.SWORDSMAN_EXEPT_STAMP_CRIT
        );
        context.services().effects().ring(
                impact,
                radius,
                24,
                SharedParticleDefinitions.SWORDSMAN_EXEPT_STAMP_DUST
        );
        context.services().effects().sound(
                impact,
                Sound.BLOCK_ANVIL_LAND,
                1.1F,
                0.75F
        );
    }

    /** 上下成分を除いた視線方向を正規化し、視線が不正ならyawから方向を復元します。 */
    private static @NotNull Vector horizontal(@NotNull Vector direction, float yaw) {
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() > 1.0E-8D) {
            return horizontal.normalize();
        }
        double yawRadians = Math.toRadians(yaw);
        return new Vector(-Math.sin(yawRadians), 0.0D, Math.cos(yawRadians));
    }

    /** 指定パラメータが正数であることを検証し、不正ならスキル定義例外を送出します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "エクゼプトスタンプの params[" + key + "] は正数が必要です");
        }
    }

    private static final class FlightState {
        private boolean leftGround;
        private boolean previousSneaking;
        private boolean diving;

        private FlightState(boolean previousSneaking) {
            this.previousSneaking = previousSneaking;
        }

        private boolean leftGround() {
            return leftGround;
        }

        private void leftGround(boolean value) {
            leftGround = value;
        }

        private boolean previousSneaking() {
            return previousSneaking;
        }

        private void previousSneaking(boolean value) {
            previousSneaking = value;
        }

        private boolean diving() {
            return diving;
        }

        private void diving(boolean value) {
            diving = value;
        }
    }
}
