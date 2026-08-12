package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ボス固有のフェーズ、予兆攻撃、召喚、地形破壊を同期 tick で管理します。
 */
public final class BossMechanicService {

    private static final long TICK_PERIOD = 5L;
    private static final double TARGET_RANGE = 32.0D;

    private final JavaPlugin plugin;
    private final MobService mobService;
    private final DamageService damageService;
    private final DungeonService dungeonService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, BossRuntime> runtimes = new HashMap<>();
    private final List<PendingMechanic> pendingMechanics = new ArrayList<>();

    private BukkitTask tickTask;
    private long clockTicks;

    public BossMechanicService(
        @NotNull JavaPlugin plugin,
        @NotNull MobService mobService,
        @NotNull DamageService damageService,
        @NotNull DungeonService dungeonService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.damageService = damageService;
        this.dungeonService = dungeonService;
        this.particleDisplayService = particleDisplayService;
    }

    /** ボス固有ギミックの定期処理を開始します。 */
    public void start() {
        if (tickTask != null) {
            return;
        }
        clockTicks = 0L;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, TICK_PERIOD);
    }

    /** 定期処理、未発動の予兆、召喚個体を回収します。 */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        pendingMechanics.clear();
        for (BossRuntime runtime : runtimes.values()) {
            destroySummons(runtime);
        }
        runtimes.clear();
    }

    private void tick() {
        clockTicks += TICK_PERIOD;
        processPendingMechanics();

        Set<UUID> activeBosses = new HashSet<>();
        for (MobInstance boss : List.copyOf(mobService.getInstances())) {
            BossMechanicProfile profile = BossMechanicProfile.find(boss.template().id());
            if (profile == null || boss.currentHealth() <= 0.0D) {
                continue;
            }
            Entity entity = mobService.entityController().getEntity(boss);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                continue;
            }

            activeBosses.add(boss.instanceId());
            BossRuntime runtime = runtimes.computeIfAbsent(
                boss.instanceId(),
                ignored -> new BossRuntime(BossMechanicProfile.phaseFor(boss.currentHealth(), boss.maxHealth()), clockTicks + 40L)
            );
            int observedPhase = BossMechanicProfile.phaseFor(boss.currentHealth(), boss.maxHealth());
            if (observedPhase > runtime.phase) {
                runtime.phase = observedPhase;
                handlePhaseTransition(boss, entity, runtime);
            }
            if (clockTicks < runtime.nextActionTick) {
                continue;
            }

            BossMechanicProfile.Mechanic mechanic = profile.mechanic(runtime.phase, runtime.actionIndex);
            if (queueMechanic(boss, entity, mechanic)) {
                runtime.actionIndex++;
                runtime.nextActionTick = clockTicks + profile.intervalTicks(runtime.phase);
            } else {
                runtime.nextActionTick = clockTicks + 20L;
            }
        }

        Iterator<Map.Entry<UUID, BossRuntime>> iterator = runtimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossRuntime> entry = iterator.next();
            if (activeBosses.contains(entry.getKey())) {
                continue;
            }
            destroySummons(entry.getValue());
            pendingMechanics.removeIf(pending -> pending.bossInstanceId().equals(entry.getKey()));
            iterator.remove();
        }
    }

    private void processPendingMechanics() {
        Iterator<PendingMechanic> iterator = pendingMechanics.iterator();
        while (iterator.hasNext()) {
            PendingMechanic pending = iterator.next();
            MobInstance boss = mobService.getInstance(pending.bossInstanceId());
            Entity entity = boss == null ? null : mobService.entityController().getEntity(boss);
            if (boss == null || entity == null || !entity.isValid() || entity.isDead() || boss.currentHealth() <= 0.0D
                || entity.getWorld() != pending.anchor().getWorld()
                || nearbyManagedPlayers(entity.getLocation(), TARGET_RANGE).isEmpty()) {
                iterator.remove();
                continue;
            }
            if (clockTicks < pending.executeAtTick()) {
                renderTelegraph(pending);
                continue;
            }
            executeMechanic(boss, pending);
            iterator.remove();
        }
    }

    private void handlePhaseTransition(
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossRuntime runtime
    ) {
        if (boss.template().shield().active()) {
            boss.currentShield(boss.template().shield().max(), System.currentTimeMillis());
        }
        Location center = entity.getLocation().add(0.0D, 0.5D, 0.0D);
        renderCircle(center, 3.0D + runtime.phase, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 28);
        entity.getWorld().playSound(center, "entity.warden.sonic_boom", 1.2F, runtime.phase == 3 ? 0.65F : 0.85F);
        runtime.nextActionTick = Math.min(runtime.nextActionTick, clockTicks + 25L);

        if (runtime.phase < 2 || runtime.summonsTriggered) {
            return;
        }
        runtime.summonsTriggered = true;
        if (BossMechanicProfile.DARK_DRAGON.equals(boss.template().id())) {
            spawnAdds(entity.getLocation(), "midgard_twilight_gloom_wisp", 2, runtime);
        } else if (BossMechanicProfile.FENRIR_WORLDBREAKER.equals(boss.template().id())) {
            spawnAdds(entity.getLocation(), "midgard_worldroot_reaver", 2, runtime);
        }
    }

    private void spawnAdds(
        @NotNull Location center,
        @NotNull String templateId,
        int count,
        @NotNull BossRuntime runtime
    ) {
        for (int index = 0; index < count; index++) {
            double angle = (Math.PI * 2.0D * index) / count;
            Location spawn = center.clone().add(Math.cos(angle) * 3.5D, 0.0D, Math.sin(angle) * 3.5D);
            MobInstance summoned = mobService.spawn(templateId, spawn);
            if (summoned == null) {
                continue;
            }
            summoned.keepWhenUnobserved(true);
            runtime.summonedMobIds.add(summoned.instanceId());
        }
    }

    private void destroySummons(@NotNull BossRuntime runtime) {
        for (UUID summonId : runtime.summonedMobIds) {
            mobService.destroy(summonId);
        }
        runtime.summonedMobIds.clear();
    }

    private boolean queueMechanic(
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossMechanicProfile.Mechanic mechanic
    ) {
        Location bossLocation = entity.getLocation();
        List<Player> targets = nearbyManagedPlayers(bossLocation, TARGET_RANGE);
        if (targets.isEmpty()) {
            return false;
        }
        Player primaryTarget = nearestPlayer(bossLocation, targets);
        Location targetLocation = primaryTarget.getLocation();
        Vector direction = horizontalDirection(bossLocation, targetLocation, entity.getFacing().getDirection());
        long telegraphTicks = telegraphTicks(mechanic);

        if (mechanic == BossMechanicProfile.Mechanic.FENRIR_FALLING_RUIN && !targets.isEmpty()) {
            targets.stream().limit(3).forEach(target -> addPending(
                boss,
                mechanic,
                target.getLocation(),
                direction,
                telegraphTicks
            ));
            return true;
        }
        if (mechanic == BossMechanicProfile.Mechanic.FENRIR_LAST_HUNT) {
            addPending(boss, mechanic, bossLocation, direction.clone().rotateAroundY(Math.toRadians(-24.0D)), telegraphTicks);
            addPending(boss, mechanic, bossLocation, direction, telegraphTicks + 10L);
            addPending(boss, mechanic, bossLocation, direction.clone().rotateAroundY(Math.toRadians(24.0D)), telegraphTicks + 20L);
            return true;
        }

        Location anchor = switch (mechanic) {
            case DRAGON_METEOR, DRAGON_WORLD_BREAK, FENRIR_FALLING_RUIN -> targetLocation;
            default -> bossLocation;
        };
        addPending(boss, mechanic, anchor, direction, telegraphTicks);
        return true;
    }

    private void addPending(
        @NotNull MobInstance boss,
        @NotNull BossMechanicProfile.Mechanic mechanic,
        @NotNull Location anchor,
        @NotNull Vector direction,
        long delayTicks
    ) {
        PendingMechanic pending = new PendingMechanic(
            boss.instanceId(),
            mechanic,
            anchor,
            direction,
            clockTicks + delayTicks
        );
        pendingMechanics.add(pending);
        renderTelegraph(pending);
        World world = anchor.getWorld();
        if (world != null) {
            world.playSound(anchor, "block.note_block.bass", 0.9F, 0.65F);
        }
    }

    private long telegraphTicks(@NotNull BossMechanicProfile.Mechanic mechanic) {
        return switch (mechanic) {
            case COLOSSUS_QUAKE, DRAGON_WING_GUST, FENRIR_RIFT_HOWL -> 25L;
            case COLOSSUS_RUNE_LANES, DRAGON_SHADOW_BREATH, FENRIR_CHARGE, FENRIR_LAST_HUNT -> 30L;
            case COLOSSUS_COLLAPSE, DRAGON_METEOR, FENRIR_FALLING_RUIN -> 35L;
            case DRAGON_WORLD_BREAK -> 45L;
        };
    }

    private void renderTelegraph(@NotNull PendingMechanic pending) {
        switch (pending.mechanic()) {
            case COLOSSUS_QUAKE -> renderCircle(pending.anchor(), 4.5D, SharedParticleDefinitions.BOSS_MECHANIC_CRIT, 28);
            case COLOSSUS_RUNE_LANES -> renderCross(
                pending.anchor(), pending.direction(), 7.0D, SharedParticleDefinitions.BOSS_MECHANIC_SPARK
            );
            case COLOSSUS_COLLAPSE -> {
                renderCircle(pending.anchor(), 2.8D, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 20);
                renderCircle(pending.anchor(), 7.0D, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 36);
            }
            case DRAGON_SHADOW_BREATH -> renderCone(
                pending.anchor(), pending.direction(), 10.0D, SharedParticleDefinitions.BOSS_MECHANIC_FLAME
            );
            case DRAGON_METEOR -> renderCircle(
                pending.anchor(), 3.0D, SharedParticleDefinitions.BOSS_MECHANIC_FLAME, 28
            );
            case DRAGON_WING_GUST -> renderCircle(
                pending.anchor(), 7.0D, SharedParticleDefinitions.BOSS_MECHANIC_CLOUD, 36
            );
            case DRAGON_WORLD_BREAK -> {
                renderCircle(pending.anchor(), 3.0D, SharedParticleDefinitions.BOSS_MECHANIC_FLAME, 24);
                renderCircle(pending.anchor(), 6.0D, SharedParticleDefinitions.BOSS_MECHANIC_SMOKE, 36);
            }
            case FENRIR_CHARGE, FENRIR_LAST_HUNT -> renderLane(
                pending.anchor(), pending.direction(), 12.0D, 1.5D, SharedParticleDefinitions.BOSS_MECHANIC_CRIT
            );
            case FENRIR_RIFT_HOWL -> {
                renderCircle(pending.anchor(), 3.0D, SharedParticleDefinitions.BOSS_MECHANIC_PORTAL, 20);
                renderCircle(pending.anchor(), 8.0D, SharedParticleDefinitions.BOSS_MECHANIC_PORTAL, 40);
            }
            case FENRIR_FALLING_RUIN -> renderCircle(
                pending.anchor(), 2.6D, SharedParticleDefinitions.BOSS_MECHANIC_SPARK, 26
            );
        }
    }

    private void executeMechanic(@NotNull MobInstance boss, @NotNull PendingMechanic pending) {
        World world = pending.anchor().getWorld();
        if (world == null) {
            return;
        }
        switch (pending.mechanic()) {
            case COLOSSUS_QUAKE -> {
                damageCircle(boss, pending.anchor(), 0.0D, 4.5D, AttackType.MELEE, DamageElement.NONE, 0.78D, 0.55D);
                breakCrater(boss, pending.anchor(), 3.0D);
            }
            case COLOSSUS_RUNE_LANES -> damageCross(
                boss, pending.anchor(), pending.direction(), 7.0D, 1.0D,
                AttackType.MAGIC, DamageElement.LIGHTNING, 0.68D
            );
            case COLOSSUS_COLLAPSE -> {
                damageCircle(boss, pending.anchor(), 2.8D, 7.0D, AttackType.MAGIC, DamageElement.NONE, 0.95D, 0.7D);
                breakRing(boss, pending.anchor(), 3.0D, 6.0D);
            }
            case DRAGON_SHADOW_BREATH -> damageCone(
                boss, pending.anchor(), pending.direction(), 10.0D, 52.0D,
                AttackType.MAGIC, DamageElement.FIRE, 0.82D
            );
            case DRAGON_METEOR -> {
                damageCircle(boss, pending.anchor(), 0.0D, 3.0D, AttackType.MAGIC, DamageElement.FIRE, 1.05D, 0.65D);
                breakCrater(boss, pending.anchor(), 2.8D);
            }
            case DRAGON_WING_GUST -> damageCircle(
                boss, pending.anchor(), 0.0D, 7.0D, AttackType.MELEE, DamageElement.NONE, 0.58D, 1.1D
            );
            case DRAGON_WORLD_BREAK -> {
                damageCircle(boss, pending.anchor(), 0.0D, 6.0D, AttackType.MAGIC, DamageElement.FIRE, 1.22D, 1.0D);
                breakCrater(boss, pending.anchor(), 4.0D);
            }
            case FENRIR_CHARGE -> {
                damageLine(boss, pending.anchor(), pending.direction(), 12.0D, 1.5D, AttackType.MELEE, DamageElement.NONE, 0.92D, 0.85D);
                breakFissure(boss, pending.anchor(), pending.direction(), 12.0D);
            }
            case FENRIR_RIFT_HOWL -> damageCircle(
                boss, pending.anchor(), 3.0D, 8.0D, AttackType.MAGIC, DamageElement.NONE, 0.68D, 0.9D
            );
            case FENRIR_FALLING_RUIN -> {
                damageCircle(boss, pending.anchor(), 0.0D, 2.6D, AttackType.MAGIC, DamageElement.LIGHTNING, 0.88D, 0.6D);
                breakCrater(boss, pending.anchor(), 2.4D);
            }
            case FENRIR_LAST_HUNT -> {
                damageLine(boss, pending.anchor(), pending.direction(), 12.0D, 1.35D, AttackType.MELEE, DamageElement.NONE, 0.72D, 0.8D);
                breakFissure(boss, pending.anchor(), pending.direction(), 12.0D);
            }
        }
        world.playSound(pending.anchor(), "entity.generic.explode", 1.0F, 0.85F);
    }

    private void damageCircle(
        @NotNull MobInstance boss,
        @NotNull Location center,
        double innerRadius,
        double outerRadius,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double pushStrength
    ) {
        double innerSquared = innerRadius * innerRadius;
        double outerSquared = outerRadius * outerRadius;
        for (Player player : nearbyManagedPlayers(center, outerRadius + 1.0D)) {
            double distanceSquared = horizontalDistanceSquared(player.getLocation(), center);
            if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
                continue;
            }
            damagePlayer(boss, player, attackType, element, ratio);
            pushAway(player, center, pushStrength);
        }
    }

    private void damageLine(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double pushStrength
    ) {
        for (Player player : nearbyManagedPlayers(origin, length + width)) {
            if (!insideLine(player.getLocation(), origin, direction, length, width)) {
                continue;
            }
            damagePlayer(boss, player, attackType, element, ratio);
            pushAway(player, origin, pushStrength);
        }
    }

    private void damageCross(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
        for (Player player : nearbyManagedPlayers(origin, length + width)) {
            Location point = player.getLocation();
            boolean hit = insideLine(point, origin, direction, length, width)
                || insideLine(point, origin, side, length, width)
                || insideLine(point, origin, direction.clone().multiply(-1.0D), length, width)
                || insideLine(point, origin, side.clone().multiply(-1.0D), length, width);
            if (hit) {
                damagePlayer(boss, player, attackType, element, ratio);
            }
        }
    }

    private void damageCone(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double angleDegrees,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        double minimumDot = Math.cos(Math.toRadians(angleDegrees / 2.0D));
        for (Player player : nearbyManagedPlayers(origin, length)) {
            Vector offset = player.getLocation().toVector().subtract(origin.toVector()).setY(0.0D);
            double distance = offset.length();
            if (distance <= 0.001D || distance > length) {
                continue;
            }
            if (offset.normalize().dot(direction) >= minimumDot) {
                damagePlayer(boss, player, attackType, element, ratio);
            }
        }
    }

    private void damagePlayer(
        @NotNull MobInstance boss,
        @NotNull Player player,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        AstEntity victim = damageService.resolveEntity(player);
        if (!victim.isPlayer()) {
            return;
        }
        damageService.attack(
            AstEntity.mob(boss),
            victim,
            attackType,
            List.of(new DamageComponent(element, ratio)),
            DamageSource.SKILL
        );
    }

    private void pushAway(@NotNull Player player, @NotNull Location center, double strength) {
        if (strength <= 0.0D) {
            return;
        }
        Vector push = player.getLocation().toVector().subtract(center.toVector()).setY(0.0D);
        if (push.lengthSquared() <= 0.001D) {
            push = new Vector(1.0D, 0.0D, 0.0D);
        }
        push.normalize().multiply(strength).setY(Math.min(0.55D, 0.2D + strength * 0.25D));
        player.setVelocity(player.getVelocity().add(push));
    }

    static boolean insideLine(
        @NotNull Location point,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width
    ) {
        Vector normalized = direction.clone().setY(0.0D);
        if (normalized.lengthSquared() <= 0.001D) {
            return false;
        }
        normalized.normalize();
        Vector offset = point.toVector().subtract(origin.toVector()).setY(0.0D);
        double projection = offset.dot(normalized);
        if (projection < 0.0D || projection > length) {
            return false;
        }
        Vector nearest = normalized.multiply(projection);
        return offset.subtract(nearest).lengthSquared() <= width * width;
    }

    private double horizontalDistanceSquared(@NotNull Location left, @NotNull Location right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return x * x + z * z;
    }

    private @NotNull List<Player> nearbyManagedPlayers(@NotNull Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return world.getPlayers().stream()
            .filter(Player::isValid)
            .filter(player -> horizontalDistanceSquared(player.getLocation(), center) <= radiusSquared)
            .filter(player -> damageService.resolveEntity(player).isPlayer())
            .toList();
    }

    private @NotNull Player nearestPlayer(@NotNull Location center, @NotNull List<Player> players) {
        Player nearest = players.getFirst();
        double nearestDistance = horizontalDistanceSquared(nearest.getLocation(), center);
        for (Player player : players.subList(1, players.size())) {
            double distance = horizontalDistanceSquared(player.getLocation(), center);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private @NotNull Vector horizontalDirection(
        @NotNull Location origin,
        @NotNull Location target,
        @NotNull Vector fallback
    ) {
        Vector direction = target.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() <= 0.001D) {
            direction = fallback.clone().setY(0.0D);
        }
        if (direction.lengthSquared() <= 0.001D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        return direction.normalize();
    }

    private void renderCircle(
        @NotNull Location center,
        double radius,
        @NotNull SharedParticleDefinition particle,
        int points
    ) {
        for (int index = 0; index < points; index++) {
            double angle = (Math.PI * 2.0D * index) / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius);
            particleDisplayService.spawnForNearbyViewers(point, particle);
        }
    }

    private void renderLane(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double halfWidth,
        @NotNull SharedParticleDefinition particle
    ) {
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX()).normalize().multiply(halfWidth);
        renderLine(origin.clone().add(side), direction, length, particle);
        renderLine(origin.clone().subtract(side), direction, length, particle);
    }

    private void renderCross(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        @NotNull SharedParticleDefinition particle
    ) {
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX()).normalize();
        renderLine(origin, direction, length, particle);
        renderLine(origin, direction.clone().multiply(-1.0D), length, particle);
        renderLine(origin, side, length, particle);
        renderLine(origin, side.clone().multiply(-1.0D), length, particle);
    }

    private void renderCone(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        @NotNull SharedParticleDefinition particle
    ) {
        renderLine(origin, direction.clone().rotateAroundY(Math.toRadians(-26.0D)), length, particle);
        renderLine(origin, direction, length, particle);
        renderLine(origin, direction.clone().rotateAroundY(Math.toRadians(26.0D)), length, particle);
    }

    private void renderLine(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        @NotNull SharedParticleDefinition particle
    ) {
        Vector step = direction.clone().setY(0.0D).normalize();
        for (double distance = 0.5D; distance <= length; distance += 0.7D) {
            Location point = origin.clone().add(step.clone().multiply(distance)).add(0.0D, 0.15D, 0.0D);
            particleDisplayService.spawnForNearbyViewers(point, particle);
        }
    }

    private void breakCrater(@NotNull MobInstance boss, @NotNull Location center, double radius) {
        if (!mayBreakTerrain(boss, center)) {
            return;
        }
        int removed = 0;
        int blockRadius = (int) Math.ceil(radius);
        int y = Math.max(center.getWorld().getMinHeight(), center.getBlockY() - 1);
        for (int x = -blockRadius; x <= blockRadius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; x++) {
            for (int z = -blockRadius; z <= blockRadius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; z++) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                removed += breakBlock(center.getWorld().getBlockAt(center.getBlockX() + x, y, center.getBlockZ() + z));
            }
        }
    }

    private void breakRing(
        @NotNull MobInstance boss,
        @NotNull Location center,
        double innerRadius,
        double outerRadius
    ) {
        if (!mayBreakTerrain(boss, center)) {
            return;
        }
        int removed = 0;
        int radius = (int) Math.ceil(outerRadius);
        int y = Math.max(center.getWorld().getMinHeight(), center.getBlockY() - 1);
        double innerSquared = innerRadius * innerRadius;
        double outerSquared = outerRadius * outerRadius;
        for (int x = -radius; x <= radius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; x++) {
            for (int z = -radius; z <= radius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; z++) {
                double distanceSquared = x * x + z * z;
                if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
                    continue;
                }
                removed += breakBlock(center.getWorld().getBlockAt(center.getBlockX() + x, y, center.getBlockZ() + z));
            }
        }
    }

    private void breakFissure(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length
    ) {
        if (!mayBreakTerrain(boss, origin)) {
            return;
        }
        Vector forward = direction.clone().setY(0.0D).normalize();
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX());
        int removed = 0;
        int y = Math.max(origin.getWorld().getMinHeight(), origin.getBlockY() - 1);
        for (double distance = 1.0D; distance <= length && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; distance += 1.0D) {
            for (int width = -1; width <= 1 && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; width++) {
                Vector offset = forward.clone().multiply(distance).add(side.clone().multiply(width));
                Block block = origin.getWorld().getBlockAt(
                    (int) Math.floor(origin.getX() + offset.getX()),
                    y,
                    (int) Math.floor(origin.getZ() + offset.getZ())
                );
                removed += breakBlock(block);
            }
        }
    }

    private boolean mayBreakTerrain(@NotNull MobInstance boss, @NotNull Location location) {
        World world = location.getWorld();
        return world != null && BossTerrainPolicy.mayBreak(
            boss.template().id(),
            dungeonService.isDungeonWorld(world)
        );
    }

    private int breakBlock(@NotNull Block block) {
        Material material = block.getType();
        if (!BossTerrainPolicy.isBreakable(material)) {
            return 0;
        }
        block.setType(Material.AIR, false);
        return 1;
    }

    private static final class BossRuntime {
        private int phase;
        private int actionIndex;
        private long nextActionTick;
        private boolean summonsTriggered;
        private final Set<UUID> summonedMobIds = new LinkedHashSet<>();

        private BossRuntime(int phase, long nextActionTick) {
            this.phase = phase;
            this.nextActionTick = nextActionTick;
        }
    }

    private record PendingMechanic(
        @NotNull UUID bossInstanceId,
        @NotNull BossMechanicProfile.Mechanic mechanic,
        @NotNull Location anchor,
        @NotNull Vector direction,
        long executeAtTick
    ) {
        private PendingMechanic {
            anchor = anchor.clone();
            direction = direction.clone().setY(0.0D).normalize();
        }
    }
}
