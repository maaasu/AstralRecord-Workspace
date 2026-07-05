package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * AstralRecord Mob と Bukkit 実体 Mob の橋渡しを担当します。
 *
 * <p>バニラ goal を全削除し、実体の同期・当たり判定・Paper Pathfinder だけを利用します。
 * AI の意思決定、攻撃判定、HP は {@link MobInstance} 側で管理します。</p>
 */
public class MobEntityController {

    private static final double STANDARD_MOVEMENT_SPEED = 100.0D;
    private static final double PATHFINDER_SPEED_MULTIPLIER = 0.9D;
    private static final double MIN_PATHFINDER_SPEED = 0.05D;
    private static final double MAX_PATHFINDER_SPEED = 2.5D;
    private static final double PATH_TARGET_DRIFT_DISTANCE_SQ = 2.25D;
    private static final double PATH_STOP_DISTANCE_SQ = 0.36D;
    private static final long PATH_RECOMPUTE_INTERVAL_TICKS = 10L;
    private static final float BLOCK_DISPLAY_VIEW_RANGE = 64.0F;
    private static final float BLOCK_DISPLAY_RENDER_SCALE = 0.75F;
    private static final float BLOCK_DISPLAY_RENDER_XZ_OFFSET = -BLOCK_DISPLAY_RENDER_SCALE / 2.0F;
    private static final float BLOCK_DISPLAY_RENDER_Y_OFFSET = 0.0F;
    private static final float BLOCK_INTERACTION_WIDTH = 1.0F;
    private static final float BLOCK_INTERACTION_HEIGHT = 1.0F;

    private final NamespacedKey instanceIdKey;
    private final NamespacedKey templateIdKey;

    /**
     * コントローラを初期化します。
     *
     * @param plugin PDC キー生成とワールド操作に使用するプラグイン
     */
    public MobEntityController(@NotNull Plugin plugin) {
        this.instanceIdKey = new NamespacedKey(plugin, "mob_instance_id");
        this.templateIdKey = new NamespacedKey(plugin, "mob_template_id");
    }

    /**
     * 指定テンプレートの実体 Mob を生成し、AstralRecord 管理用の初期化を行います。
     *
     * @param instance 紐付ける Mob インスタンス
     * @param location スポーン位置
     * @return 生成した Bukkit Entity。生成できない場合は {@code null}
     */
    @Nullable
    public Entity spawn(@NotNull MobInstance instance, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        if (instance.template().blockMaterial() != null) {
            return spawnBlockDisplay(instance, location);
        }

        Class<? extends Entity> entityClass = instance.template().entityType().getEntityClass();
        if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) {
            return null;
        }

        Class<? extends Mob> mobClass = entityClass.asSubclass(Mob.class);
        Mob mob;
        try {
            mob = world.spawn(
                    location,
                    mobClass,
                    CreatureSpawnEvent.SpawnReason.CUSTOM,
                    false,
                    spawned -> configure(instance, spawned)
            );
        } catch (RuntimeException ex) {
            return null;
        }

        if (mob.isDead() || !mob.isValid()) {
            return null;
        }

        try {
            instance.bindEntity(mob.getUniqueId(), mob.getEntityId(), mob.getLocation());
            return mob;
        } catch (RuntimeException ex) {
            mob.remove();
            return null;
        }
    }

    @Nullable
    private Entity spawnBlockDisplay(@NotNull MobInstance instance, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null || instance.template().blockMaterial() == null) {
            return null;
        }

        Location interactionLocation = blockInteractionLocation(location);
        Interaction interaction;
        try {
            interaction = world.spawn(interactionLocation, Interaction.class, spawned -> configureBlockInteraction(instance, spawned));
        } catch (RuntimeException ex) {
            return null;
        }

        if (interaction.isDead() || !interaction.isValid()) {
            return null;
        }

        Location blockLocation = blockDisplayLocation(interactionLocation);
        BlockDisplay display;
        try {
            display = world.spawn(blockLocation, BlockDisplay.class, spawned -> configureBlockDisplay(instance, spawned));
        } catch (RuntimeException ex) {
            interaction.remove();
            return null;
        }

        if (display.isDead() || !display.isValid()) {
            interaction.remove();
            return null;
        }

        try {
            instance.bindEntity(interaction.getUniqueId(), interaction.getEntityId(), interaction.getLocation());
            instance.bindDisplayEntity(display.getUniqueId(), display.getEntityId());
            instance.headYaw(interactionLocation.getYaw());
            instance.headPitch(0.0F);
            return interaction;
        } catch (RuntimeException ex) {
            display.remove();
            interaction.remove();
            return null;
        }
    }

    private void configureBlockInteraction(@NotNull MobInstance instance, @NotNull Interaction interaction) {
        MobTemplate template = instance.template();
        interaction.setPersistent(false);
        interaction.setGravity(false);
        interaction.setInvulnerable(template.damageImmune());
        interaction.setSilent(true);
        interaction.customName(null);
        interaction.setCustomNameVisible(false);
        interaction.setResponsive(true);
        interaction.setInteractionWidth(BLOCK_INTERACTION_WIDTH);
        interaction.setInteractionHeight(BLOCK_INTERACTION_HEIGHT);
        interaction.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        interaction.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
    }

    private void configureBlockDisplay(@NotNull MobInstance instance, @NotNull BlockDisplay display) {
        MobTemplate template = instance.template();
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(template.damageImmune());
        display.setSilent(true);
        display.customName(null);
        display.setCustomNameVisible(false);
        display.setBillboard(Display.Billboard.FIXED);
        display.setViewRange(BLOCK_DISPLAY_VIEW_RANGE);
        display.setDisplayWidth(1.0F);
        display.setDisplayHeight(1.0F);
        display.setTeleportDuration(1);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setBlock(displayBlockMaterial(template.blockMaterial()).createBlockData());
        display.setTransformation(blockDisplayTransformation());
        display.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        display.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
    }

    /**
     * 指定 Mob のバニラ goal と不要な実体挙動を抑止します。
     *
     * @param instance AstralRecord 側インスタンス
     * @param mob      Bukkit 実体 Mob
     */
    public void configure(@NotNull MobInstance instance, @NotNull Mob mob) {
        MobTemplate template = instance.template();
        Bukkit.getMobGoals().removeAllGoals(mob);

        mob.setAI(true);
        mob.setAware(true);
        mob.setInvulnerable(template.damageImmune());
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.setCollidable(false);
        mob.setSilent(true);
        mob.customName(null);
        mob.setCustomNameVisible(false);
        applyVariant(template, mob);
        clearEquipment(mob.getEquipment());

        mob.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        mob.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
        mob.getPathfinder().setCanOpenDoors(false);
        mob.getPathfinder().setCanPassDoors(true);
        mob.getPathfinder().setCanFloat(true);
        applyStationaryNpcAttributes(template, mob);
    }

    void applyVariant(@NotNull MobTemplate template, @NotNull Mob mob) {
        if (!(mob instanceof Ageable ageable)) {
            return;
        }

        switch (template.variant().age()) {
            case BABY -> ageable.setBaby();
            case ADULT -> ageable.setAdult();
        }
        if (ageable instanceof Breedable breedable) {
            breedable.setAgeLock(true);
        }
    }

    /**
     * 実体 Mob を取得します。
     *
     * @param instance 取得対象インスタンス
     * @return 紐付く Bukkit Mob。補助表示 Entity しか持たない場合や解決できない場合は {@code null}
     */
    @Nullable
    public Mob getMob(@NotNull MobInstance instance) {
        Entity entity = getEntity(instance);
        return entity instanceof Mob mob ? mob : null;
    }

    /**
     * 管理対象 Entity を取得します。
     *
     * @param instance 取得対象インスタンス
     * @return 有効な Bukkit Entity。存在しない場合は {@code null}
     */
    @Nullable
    public Entity getEntity(@NotNull MobInstance instance) {
        UUID entityUuid = instance.bukkitEntityId();
        if (entityUuid == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(entityUuid);
        return entity != null && !entity.isDead() && entity.isValid() ? entity : null;
    }

    /**
     * 実体 Mob の現在位置を {@link MobInstance} へ反映します。
     *
     * @param instance 同期対象インスタンス
     * @return 実体が有効なら {@code true}
     */
    public boolean syncLocation(@NotNull MobInstance instance) {
        Entity entity = getEntity(instance);
        if (entity == null) {
            return false;
        }
        instance.currentLocation(entity.getLocation());
        return true;
    }

    /**
     * Paper Pathfinder へ目標位置を渡します。
     *
     * <p>100体規模で毎 tick 経路再計算が走らないよう、目標地点の移動量と再計算間隔で制限します。</p>
     *
     * @param instance        移動対象インスタンス
     * @param target          目標位置
     * @param aiSpeedModifier AI 定義側の速度倍率
     * @param currentTick     Mob AI 内部 tick
     * @return 経路設定を実行した場合は {@code true}
     */
    public boolean moveTo(
            @NotNull MobInstance instance,
            @NotNull Location target,
            double aiSpeedModifier,
            long currentTick) {
        Mob mob = getMob(instance);
        if (mob == null || mob.getWorld() != target.getWorld()) {
            return false;
        }

        Location current = mob.getLocation();
        if (current.distanceSquared(target) <= PATH_STOP_DISTANCE_SQ) {
            mob.getPathfinder().stopPathfinding();
            instance.currentLocation(current);
            return false;
        }

        boolean targetDrifted = hasTargetDrifted(instance, target);
        boolean intervalPassed = currentTick - instance.navRecomputeTick() >= PATH_RECOMPUTE_INTERVAL_TICKS;
        boolean hasPath = mob.getPathfinder().hasPath();
        if (hasPath && !targetDrifted && !intervalPassed) {
            instance.currentLocation(current);
            return false;
        }

        boolean moved = mob.getPathfinder().moveTo(target, resolvePathfinderSpeed(instance, aiSpeedModifier));
        instance.navTargetX(target.getX());
        instance.navTargetZ(target.getZ());
        instance.navRecomputeTick(currentTick);
        instance.currentLocation(current);
        return moved;
    }

    /**
     * 現在の経路探索を停止します。
     *
     * @param instance 対象インスタンス
     */
    public void stopPathfinding(@NotNull MobInstance instance) {
        Mob mob = getMob(instance);
        if (mob != null) {
            mob.getPathfinder().stopPathfinding();
            instance.currentLocation(mob.getLocation());
        }
        instance.clearNavPath();
    }

    /**
     * Mob の水平速度だけを打ち消し、落下や視線追従に必要な縦方向の挙動は維持します。
     *
     * @param instance 対象 Mob インスタンス
     */
    /**
     * Mob の水平速度だけを打ち消し、落下や視線追従に必要な縦方向の挙動は維持します。
     *
     * @param instance 対象 Mob インスタンス
     */
    public void stopHorizontalMovement(@NotNull MobInstance instance) {
        Mob mob = getMob(instance);
        if (mob == null) {
            return;
        }

        Vector velocity = mob.getVelocity();
        if (Math.abs(velocity.getX()) < 1.0E-4D && Math.abs(velocity.getZ()) < 1.0E-4D) {
            return;
        }

        mob.setVelocity(new Vector(0.0D, velocity.getY(), 0.0D));
        instance.currentLocation(mob.getLocation());
    }

    /**
     * 実体 Mob にノックバック速度を加算します。
     *
     * @param instance 対象インスタンス
     * @param velocity 加算する速度
     */
    public void holdPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        if (instance.template().blockMaterial() != null) {
            holdBlockNpcPosition(instance, anchor);
            return;
        }
        Mob mob = getMob(instance);
        if (mob == null || mob.getWorld() != anchor.getWorld()) {
            return;
        }

        Location current = mob.getLocation();
        Vector currentVelocity = mob.getVelocity();
        boolean drifted = current.distanceSquared(anchor) > 1.0E-4D;
        boolean moving = currentVelocity.lengthSquared() > 1.0E-4D;
        if (!drifted && !moving) {
            instance.currentLocation(current);
            return;
        }

        mob.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        if (drifted) {
            Location anchored = anchor.clone();
            anchored.setYaw(current.getYaw());
            anchored.setPitch(current.getPitch());
            mob.teleport(anchored);
            instance.currentLocation(anchored);
            return;
        }

        instance.currentLocation(mob.getLocation());
    }

    public void addVelocity(@NotNull MobInstance instance, @NotNull Vector velocity) {
        Mob mob = getMob(instance);
        if (mob == null) {
            return;
        }
        mob.setVelocity(mob.getVelocity().add(velocity));
        instance.currentLocation(mob.getLocation());
    }

    /**
     * 実体 Mob の視線を指定位置に向けます。
     *
     * @param instance 対象インスタンス
     * @param target   視線を向ける位置
     */
    public void lookAt(@NotNull MobInstance instance, @NotNull Location target) {
        if (instance.template().blockMaterial() != null) {
            return;
        }
        Mob mob = getMob(instance);
        if (mob == null || mob.getWorld() != target.getWorld()) {
            return;
        }
        mob.lookAt(target.getX(), target.getY(), target.getZ(), LookAnchor.EYES);
    }

    /**
     * 実体 Mob をワールドから削除します。
     *
     * @param instance 対象インスタンス
     */
    public void remove(@NotNull MobInstance instance) {
        Entity entity = getEntity(instance);
        if (entity != null) {
            entity.remove();
        }
        Entity displayEntity = getDisplayEntity(instance);
        if (displayEntity != null) {
            displayEntity.remove();
        }
    }

    @NotNull
    Location blockDisplayLocation(@NotNull Location location) {
        Location displayLocation = location.clone();
        displayLocation.setPitch(0.0F);
        return displayLocation;
    }

    @NotNull
    Location blockInteractionLocation(@NotNull Location location) {
        Location interactionLocation = location.clone();
        interactionLocation.setPitch(0.0F);
        return interactionLocation;
    }

    @NotNull
    Material displayBlockMaterial(@NotNull Material material) {
        return switch (material) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST -> Material.BARREL;
            default -> material;
        };
    }

    @NotNull
    Transformation blockDisplayTransformation() {
        return new Transformation(
                new Vector3f(BLOCK_DISPLAY_RENDER_XZ_OFFSET, BLOCK_DISPLAY_RENDER_Y_OFFSET, BLOCK_DISPLAY_RENDER_XZ_OFFSET),
                new Quaternionf(),
                new Vector3f(BLOCK_DISPLAY_RENDER_SCALE, BLOCK_DISPLAY_RENDER_SCALE, BLOCK_DISPLAY_RENDER_SCALE),
                new Quaternionf()
        );
    }

    @Nullable
    private Entity getDisplayEntity(@NotNull MobInstance instance) {
        UUID entityUuid = instance.displayEntityId();
        if (entityUuid == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(entityUuid);
        return entity != null && !entity.isDead() && entity.isValid() ? entity : null;
    }

    private void holdBlockNpcPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        Entity interaction = getEntity(instance);
        if (interaction == null || interaction.getWorld() != anchor.getWorld()) {
            return;
        }

        Location current = interaction.getLocation();
        if (current.distanceSquared(anchor) <= 1.0E-4D) {
            instance.currentLocation(current);
            return;
        }

        Location anchored = blockInteractionLocation(anchor);
        anchored.setYaw(current.getYaw());
        teleportBlockNpc(instance, anchored);
    }

    private void teleportBlockNpc(@NotNull MobInstance instance, @NotNull Location interactionLocation) {
        Location pose = blockInteractionLocation(interactionLocation);
        Entity interaction = getEntity(instance);
        if (interaction != null) {
            interaction.teleport(pose);
        }
        Entity displayEntity = getDisplayEntity(instance);
        if (displayEntity != null) {
            displayEntity.teleport(blockDisplayLocation(pose));
        }
        instance.currentLocation(pose);
        instance.headYaw(pose.getYaw());
        instance.headPitch(0.0F);
    }

    /**
     * PDC から AstralRecord Mob インスタンス ID を読み取ります。
     *
     * @param entity 対象 Bukkit Entity
     * @return インスタンス ID。未設定または不正な場合は {@code null}
     */
    @Nullable
    public UUID readInstanceId(@NotNull Entity entity) {
        String raw = entity.getPersistentDataContainer().get(instanceIdKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void clearEquipment(@Nullable EntityEquipment equipment) {
        if (equipment == null) {
            return;
        }

        ItemStack air = new ItemStack(Material.AIR);
        equipment.setItemInMainHand(air);
        equipment.setItemInOffHand(air);
        equipment.setHelmet(air);
        equipment.setChestplate(air);
        equipment.setLeggings(air);
        equipment.setBoots(air);
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
        equipment.setHelmetDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
    }

    void applyStationaryNpcAttributes(@NotNull MobTemplate template, @NotNull Mob mob) {
        if (template.category() != MobCategory.NPC || template.idle().behavior() != IdleBehavior.STATIONARY) {
            return;
        }

        zeroAttribute(mob.getAttribute(Attribute.MOVEMENT_SPEED));
        zeroAttribute(mob.getAttribute(Attribute.JUMP_STRENGTH));
    }

    private void zeroAttribute(@Nullable AttributeInstance attribute) {
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(0.0D);
    }

    private boolean hasTargetDrifted(@NotNull MobInstance instance, @NotNull Location target) {
        double dx = target.getX() - instance.navTargetX();
        double dz = target.getZ() - instance.navTargetZ();
        return dx * dx + dz * dz > PATH_TARGET_DRIFT_DISTANCE_SQ;
    }

    private double resolvePathfinderSpeed(@NotNull MobInstance instance, double aiSpeedModifier) {
        double statusSpeed = instance.template().statValue("MOVEMENT_SPEED", STANDARD_MOVEMENT_SPEED);
        double statusMultiplier = Math.max(0.0D, statusSpeed) / STANDARD_MOVEMENT_SPEED;
        double speed = Math.max(0.0D, aiSpeedModifier) * statusMultiplier * PATHFINDER_SPEED_MULTIPLIER;
        return Math.max(MIN_PATHFINDER_SPEED, Math.min(speed, MAX_PATHFINDER_SPEED));
    }
}
