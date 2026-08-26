package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** {@code mob_all_things_el_ice_sphere}: 人数の2倍の反射氷球を、各プレイヤー方向へ散らします。 */
public final class AllThingsElIceSphereMobSkillExecutor implements MobSkillExecutor {

    public static final String SKILL_ID = "mob_all_things_el_ice_sphere";
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "speed", "damageRatio", "hitRadius", "frozenDurationTicks", "spreadDegrees"
    );
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobProjectileService projectileService;

    /** ダメージ・状態異常・反射飛び道具の依存先を指定して構築します。 */
    public AllThingsElIceSphereMobSkillExecutor(
            @NotNull DamageService damageService,
            @NotNull ConditionService conditionService,
            @NotNull MobProjectileService projectileService
    ) {
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.projectileService = projectileService;
    }

    @Override public @NotNull String id() { return SKILL_ID; }
    @Override public @NotNull String displayName() { return "氷界の残響"; }
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(20.0D, 100L, 20L); }

    @Override
    public void validate(@NotNull MobSkillBinding binding) {
        if (!PARAMETER_KEYS.containsAll(binding.params().keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        positive(binding.params().getOrDefault("speed", 0.75D), "speed");
        positive(binding.params().getOrDefault("damageRatio", 0.70D), "damageRatio");
        nonNegative(binding.params().getOrDefault("hitRadius", 0.20D), "hitRadius");
        positive(binding.params().getOrDefault("frozenDurationTicks", 20.0D), "frozenDurationTicks");
        bounded(binding.params().getOrDefault("spreadDegrees", 24.0D), "spreadDegrees", 0.0D, 75.0D);
    }

    @Override
    public boolean cast(@NotNull MobSkillContext context) {
        Location origin = context.origin();
        if (origin.getWorld() == null) {
            return false;
        }
        List<Player> players = origin.getWorld().getPlayers().stream()
                .filter(player -> player.isOnline() && !player.isDead())
                .toList();
        if (players.isEmpty()) {
            return false;
        }
        Map<String, Double> params = context.binding().params();
        for (Player player : players) {
            for (int index = 0; index < 2; index++) {
                projectileService.launchBouncingIceSphere(
                        context.mob(), origin, spreadToward(origin, player, params.getOrDefault("spreadDegrees", 24.0D)),
                        params.getOrDefault("speed", 0.75D), params.getOrDefault("hitRadius", 0.20D),
                        params.getOrDefault("damageRatio", 0.70D),
                        Math.round(params.getOrDefault("frozenDurationTicks", 20.0D)), damageService, conditionService
                );
            }
        }
        origin.getWorld().playSound(origin, Sound.ENTITY_BREEZE_SHOOT, 1.0F, 0.75F);
        return true;
    }

    private @NotNull Vector spreadToward(@NotNull Location origin, @NotNull Player player, double spreadDegrees) {
        Vector direction = player.getEyeLocation().toVector().subtract(origin.toVector());
        if (direction.lengthSquared() <= 1.0E-6D) {
            direction = new Vector(1.0D, 0.0D, 0.0D);
        }
        double yaw = Math.toRadians(ThreadLocalRandom.current().nextDouble(-spreadDegrees, spreadDegrees));
        double x = direction.getX() * Math.cos(yaw) - direction.getZ() * Math.sin(yaw);
        double z = direction.getX() * Math.sin(yaw) + direction.getZ() * Math.cos(yaw);
        return new Vector(x, direction.getY() + ThreadLocalRandom.current().nextDouble(-0.08D, 0.08D), z);
    }

    private void positive(double value, @NotNull String key) { bounded(value, key, Double.MIN_VALUE, Double.MAX_VALUE); }
    private void nonNegative(double value, @NotNull String key) { bounded(value, key, 0.0D, Double.MAX_VALUE); }
    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
