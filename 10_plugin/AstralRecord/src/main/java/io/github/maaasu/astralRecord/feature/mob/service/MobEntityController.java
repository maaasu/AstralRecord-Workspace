package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Breedable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Vex;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AstralRecord Mob と Bukkit 実体 Mob の橋渡しを担当します。
 *
 * <p>バニラ goal を全削除し、実体の同期・当たり判定・Paper Pathfinder・Vex 用三次元経路を利用します。
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
    private static final double VEX_FLIGHT_SPEED_MULTIPLIER = 0.18D;
    private static final double MIN_VEX_FLIGHT_SPEED = 0.05D;
    private static final double MAX_VEX_FLIGHT_SPEED = 0.35D;
    private static final int VEX_PATH_HORIZONTAL_RADIUS = 20;
    private static final int VEX_PATH_VERTICAL_RADIUS = 10;
    private static final int VEX_PATH_MAX_EXPANDED_NODES = 768;
    private static final long VEX_NO_PATH_RECOMPUTE_INTERVAL_TICKS = 20L;
    private static final double VEX_PATH_NODE_Y_OFFSET = 0.1D;
    private static final double VEX_PATH_WAYPOINT_DISTANCE_SQ = 0.16D;
    private static final double VEX_COLLISION_MARGIN = 0.04D;
    private static final double VEX_SWEEP_STEP = 0.15D;
    private static final float BLOCK_DISPLAY_VIEW_RANGE = 64.0F;
    private static final float BLOCK_DISPLAY_RENDER_SCALE = 0.75F;
    private static final float BLOCK_DISPLAY_RENDER_XZ_OFFSET = -BLOCK_DISPLAY_RENDER_SCALE / 2.0F;
    private static final float BLOCK_DISPLAY_RENDER_Y_OFFSET = 0.0F;
    private static final float ITEM_DISPLAY_RENDER_SCALE = BLOCK_DISPLAY_RENDER_SCALE;
    private static final float ITEM_DISPLAY_RENDER_Y_OFFSET = ITEM_DISPLAY_RENDER_SCALE / 2.0F;
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
     * 指定テンプレートの実体 Mob または固定表示用 ArmorStand を生成し、AstralRecord 管理用の初期化を行います。
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
        if (instance.template().entityType() == org.bukkit.entity.EntityType.ARMOR_STAND) {
            return spawnArmorStand(instance, location);
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

    /**
     * 固定表示用 ArmorStand を生成し、Mob インスタンスへ紐付けます。
     *
     * @param instance 紐付ける Mob インスタンス
     * @param location スポーン位置
     * @return 生成した ArmorStand。生成できない場合は {@code null}
     */
    @Nullable
    private Entity spawnArmorStand(@NotNull MobInstance instance, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        ArmorStand armorStand;
        try {
            armorStand = world.spawn(location, ArmorStand.class, spawned -> configureArmorStand(instance, spawned));
        } catch (RuntimeException ex) {
            Logger.error(
                    LogId.E_5707,
                    ex,
                    instance.template().entityType().name(),
                    instance.template().id()
            );
            return null;
        }
        if (!isManagedEntityUsable(armorStand)) {
            armorStand.remove();
            return null;
        }

        try {
            instance.bindEntity(armorStand.getUniqueId(), armorStand.getEntityId(), armorStand.getLocation());
            return armorStand;
        } catch (RuntimeException ex) {
            Logger.error(
                    LogId.E_5707,
                    ex,
                    instance.template().entityType().name(),
                    instance.template().id()
            );
            armorStand.remove();
            return null;
        }
    }

    /**
     * 固定表示用 ArmorStand をカカシとして構成します。
     *
     * @param instance   紐付ける Mob インスタンス
     * @param armorStand 初期化対象の ArmorStand
     */
    private void configureArmorStand(@NotNull MobInstance instance, @NotNull ArmorStand armorStand) {
        MobTemplate template = instance.template();
        armorStand.setPersistent(false);
        armorStand.setGravity(false);
        armorStand.setInvulnerable(template.damageImmune());
        armorStand.setCollidable(false);
        armorStand.setSilent(true);
        armorStand.customName(null);
        armorStand.setCustomNameVisible(false);
        armorStand.setVisible(true);
        armorStand.setArms(true);
        armorStand.setBasePlate(true);
        armorStand.setSmall(false);
        armorStand.setMarker(false);
        clearEquipment(armorStand.getEquipment());
        applyEquipment(armorStand.getEquipment(), template.equipment());
        armorStand.addDisabledSlots(EquipmentSlot.values());
        armorStand.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        armorStand.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
    }

    /**
     * Bukkit Entity を AstralRecord の管理対象として利用可能か判定します。
     *
     * <p>Paper/Purpur はワールド初期化中に生成した Entity について、生成呼び出しが成功していても
     * 同じ tick 内では {@link Entity#isValid()} が一時的に {@code false} を返すことがあります。
     * UUID と Entity ID はこの時点で確定しているため、ArmorStand は死亡済みでなければ
     * 生成直後の同期と追跡を続行します。その他の Entity は従来どおり有効性も確認します。</p>
     *
     * @param entity 判定対象 Entity
     * @return 管理対象として利用可能なら {@code true}
     */
    static boolean isManagedEntityUsable(@NotNull Entity entity) {
        return !entity.isDead() && (entity instanceof ArmorStand || entity.isValid());
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
        Entity display;
        try {
            display = spawnBlockDisplayEntity(world, instance, blockLocation);
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

    private @NotNull Entity spawnBlockDisplayEntity(
            @NotNull World world,
            @NotNull MobInstance instance,
            @NotNull Location location
    ) {
        Material material = instance.template().blockMaterial();
        if (material != null && usesItemDisplayBlockMaterial(material)) {
            return world.spawn(location, ItemDisplay.class, spawned -> configureItemDisplay(instance, spawned));
        }
        return world.spawn(location, BlockDisplay.class, spawned -> configureBlockDisplay(instance, spawned));
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
        Material material = java.util.Objects.requireNonNull(template.blockMaterial(), "blockMaterial");
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
        display.setBlock(material.createBlockData());
        display.setTransformation(blockDisplayTransformation());
        display.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        display.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
    }

    private void configureItemDisplay(@NotNull MobInstance instance, @NotNull ItemDisplay display) {
        MobTemplate template = instance.template();
        Material material = java.util.Objects.requireNonNull(template.blockMaterial(), "blockMaterial");
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
        display.setItemStack(new ItemStack(material));
        display.setItemDisplayTransform(itemDisplayTransform());
        display.setTransformation(itemDisplayTransformation());
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
        applyEquipment(mob.getEquipment(), template.equipment());

        mob.getPersistentDataContainer().set(instanceIdKey, PersistentDataType.STRING, instance.instanceId().toString());
        mob.getPersistentDataContainer().set(templateIdKey, PersistentDataType.STRING, template.id());
        mob.getPathfinder().setCanOpenDoors(false);
        mob.getPathfinder().setCanPassDoors(true);
        mob.getPathfinder().setCanFloat(true);
        applyStationaryNpcAttributes(template, mob);
    }

    /**
     * マスターデータで指定された外見差分を対象 Mob の対応 setter へ反映します。
     *
     * @param template 外見差分を保持する Mob テンプレート
     * @param mob      外見差分を反映する Bukkit Mob
     */
    void applyVariant(@NotNull MobTemplate template, @NotNull Mob mob) {
        if (mob instanceof Ageable ageable) {
            switch (template.variant().age()) {
                case BABY -> ageable.setBaby();
                case ADULT -> ageable.setAdult();
            }
            if (ageable instanceof Breedable breedable) {
                breedable.setAgeLock(true);
            }
        }

        var variant = template.variant();
        applyNamedVariant(mob, "setVariant", variant.kind());
        applyNamedVariant(mob, "setCatType", variant.kind());
        applyNamedVariant(mob, "setRabbitType", variant.kind());
        applyNamedVariant(mob, "setFoxType", variant.kind());
        applyNamedVariant(mob, "setColor", variant.color());
        applyNamedVariant(mob, "setStyle", variant.style());
        applyNamedVariant(mob, "setProfession", variant.profession());
        applyNamedVariant(mob, "setVillagerProfession", variant.profession());
        applyNamedVariant(mob, "setVillagerType", variant.villagerType());
        applyIntVariant(mob, "setVillagerLevel", variant.villagerLevel(), 1, 5);
        applyNamedVariant(mob, "setPattern", variant.pattern());
        applyNamedVariant(mob, "setBodyColor", variant.bodyColor());
        applyNamedVariant(mob, "setPatternColor", variant.patternColor());
        applyNamedVariant(mob, "setMainGene", variant.mainGene());
        applyNamedVariant(mob, "setHiddenGene", variant.hiddenGene());
    }

    /**
     * Java enum または Paper の registry-backed 互換型を引数に取る setter へ名前付き外見差分を反映します。
     *
     * @param entity     外見差分を反映する Entity
     * @param methodName 呼び出す setter 名
     * @param rawValue   マスターデータ由来の名前。未指定・未知値は反映しません
     */
    private void applyNamedVariant(@NotNull Entity entity, @NotNull String methodName, @Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return;
        }

        for (Method method : entity.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            Object namedValue = resolveNamedValue(parameterType, rawValue);
            if (namedValue == null) {
                continue;
            }
            try {
                method.invoke(entity, namedValue);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // EntityType と setter の組み合わせが合わない場合は外見差分を無視する。
            }
            return;
        }
    }

    /**
     * Java enum または Paper の registry-backed 互換型が公開する候補から名前に一致する値を解決します。
     *
     * @param valueType setter の引数型
     * @param rawValue  マスターデータ由来の名前
     * @return 大小文字を区別せず一致した値。対象外の型または未知値の場合は {@code null}
     */
    @Nullable
    private Object resolveNamedValue(@NotNull Class<?> valueType, @NotNull String rawValue) {
        if (valueType.isEnum()) {
            for (Object candidate : valueType.getEnumConstants()) {
                if (candidate instanceof Enum<?> enumCandidate
                        && enumCandidate.name().equalsIgnoreCase(rawValue)) {
                    return candidate;
                }
            }
            return null;
        }
        try {
            Method valuesMethod = valueType.getMethod("values");
            Method nameMethod = valueType.getMethod("name");
            if (!Modifier.isStatic(valuesMethod.getModifiers()) || !valuesMethod.getReturnType().isArray()) {
                return null;
            }
            Object values = valuesMethod.invoke(null);
            if (!(values instanceof Object[] candidates)) {
                return null;
            }
            for (Object candidate : candidates) {
                if (valueType.isInstance(candidate)
                        && rawValue.equalsIgnoreCase(String.valueOf(nameMethod.invoke(candidate)))) {
                    return candidate;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Paper の互換 values()/name() を利用できない型は外見差分の対象外とする。
        }
        return null;
    }

    private void applyIntVariant(
            @NotNull Entity entity,
            @NotNull String methodName,
            @Nullable Integer rawValue,
            int min,
            int max
    ) {
        if (rawValue == null) {
            return;
        }

        int value = Math.clamp(rawValue, min, max);
        for (Method method : entity.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType != int.class && parameterType != Integer.class) {
                continue;
            }
            try {
                method.invoke(entity, value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // EntityType と setter の組み合わせが合わない場合は外見差分を無視する。
            }
            return;
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
        return entity != null && isManagedEntityUsable(entity) ? entity : null;
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

        if (mob instanceof Vex vex) {
            return moveVexTo(instance, vex, target, aiSpeedModifier, currentTick);
        }

        Location current = mob.getLocation();
        if (current.distanceSquared(target) <= PATH_STOP_DISTANCE_SQ) {
            mob.getPathfinder().stopPathfinding();
            instance.currentLocation(current);
            return false;
        }

        boolean targetDrifted = hasGroundTargetDrifted(instance, target);
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
            if (mob instanceof Vex) {
                mob.setVelocity(new Vector());
            }
            instance.currentLocation(mob.getLocation());
        }
        instance.clearNavPath();
    }

    /**
     * Vex が保持している三次元経路を 1 tick 分追従させます。
     *
     * <p>AI の目標再評価間隔とは独立して毎 tick 呼び出し、継続中の速度が
     * 壁・床・天井を横切らないよう移動線分を検査します。</p>
     *
     * @param instance 追従対象の Mob インスタンス
     */
    public void tickVexNavigation(@NotNull MobInstance instance) {
        Mob mob = getMob(instance);
        if (!(mob instanceof Vex vex)) {
            return;
        }
        Location current = vex.getLocation();
        if (instance.navDirectVelocityOverride()) {
            instance.navDirectVelocityOverride(false);
            guardVexDirectVelocity(vex, current);
            instance.currentLocation(current);
            return;
        }

        List<Location> path = instance.navPath();
        double speed = instance.navFlightSpeed();
        if (path == null || path.isEmpty() || speed <= 0.0D) {
            guardVexDirectVelocity(vex, current);
            instance.currentLocation(current);
            return;
        }

        int index = instance.navPathIndex();
        while (index < path.size() && current.distanceSquared(path.get(index)) <= VEX_PATH_WAYPOINT_DISTANCE_SQ) {
            index++;
        }
        if (index >= path.size()) {
            vex.setVelocity(new Vector());
            instance.clearNavPath();
            instance.currentLocation(current);
            return;
        }

        Location waypoint = path.get(index);
        Vector direction = waypoint.toVector().subtract(current.toVector());
        double distance = direction.length();
        if (distance <= 0.0D) {
            vex.setVelocity(new Vector());
            return;
        }

        Vector velocity = direction.multiply(Math.min(speed, distance) / distance);
        Location next = current.clone().add(velocity);
        if (!isVexSweepClear(vex, current, next)) {
            vex.setVelocity(new Vector());
            instance.clearNavPath();
            instance.navRecomputeTick(-1000L);
            instance.currentLocation(current);
            return;
        }

        instance.navPathIndex(index);
        vex.setVelocity(velocity);
        instance.currentLocation(current);
    }

    private void guardVexDirectVelocity(@NotNull Vex vex, @NotNull Location current) {
        Vector velocity = vex.getVelocity();
        if (velocity.lengthSquared() <= 0.0D) {
            return;
        }
        Location next = current.clone().add(velocity);
        if (!isVexSweepClear(vex, current, next)) {
            vex.setVelocity(new Vector());
        }
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
     * 管理 Entity の移動を止め、指定した固定位置へ戻します。
     *
     * @param instance 対象インスタンス
     * @param anchor   固定位置
     */
    public void holdPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        if (instance.template().blockMaterial() != null) {
            holdBlockNpcPosition(instance, anchor);
            return;
        }
        Entity entity = getEntity(instance);
        if (entity == null || entity.getWorld() != anchor.getWorld()) {
            return;
        }

        Location current = entity.getLocation();
        Vector currentVelocity = entity.getVelocity();
        boolean drifted = current.distanceSquared(anchor) > 1.0E-4D;
        boolean moving = currentVelocity.lengthSquared() > 1.0E-4D;
        if (!drifted && !moving) {
            instance.currentLocation(current);
            return;
        }

        entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        if (drifted) {
            Location anchored = anchor.clone();
            anchored.setYaw(current.getYaw());
            anchored.setPitch(current.getPitch());
            entity.teleport(anchored);
            instance.currentLocation(anchored);
            return;
        }

        instance.currentLocation(entity.getLocation());
    }

    /**
     * 管理 Entity を指定された配置アンカーへテレポートし、移動状態をリセットします。
     *
     * <p>配置アンカーの座標だけを採用し、通常 Entity の現在の yaw / pitch は維持します。
     * block NPC は既存の配置ルールに従って pitch を 0 とし、当たり判定 Entity と表示 Entity を同時に移動します。</p>
     *
     * @param instance リセット対象インスタンス
     * @param anchor   戻し先の配置アンカー
     */
    public void resetPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        if (instance.template().blockMaterial() != null) {
            Entity interaction = getEntity(instance);
            if (interaction == null || interaction.getWorld() != anchor.getWorld()) {
                return;
            }

            Location reset = blockInteractionLocation(anchor);
            reset.setYaw(interaction.getLocation().getYaw());
            teleportBlockNpc(instance, reset);
            instance.clearNavPath();
            return;
        }

        Entity entity = getEntity(instance);
        if (entity == null || entity.getWorld() != anchor.getWorld()) {
            return;
        }

        if (entity instanceof Mob mob) {
            mob.getPathfinder().stopPathfinding();
        }
        entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));

        Location reset = anchor.clone();
        Location current = entity.getLocation();
        reset.setYaw(current.getYaw());
        reset.setPitch(current.getPitch());
        if (entity.teleport(reset)) {
            instance.currentLocation(reset);
        } else {
            instance.currentLocation(entity.getLocation());
        }
        instance.clearNavPath();
    }

    /**
     * 実体 Mob に速度を加算します。ArmorStand など Mob 以外の管理 Entity には適用しません。
     *
     * @param instance 対象インスタンス
     * @param velocity 加算する速度
     */
    public void addVelocity(@NotNull MobInstance instance, @NotNull Vector velocity) {
        Mob mob = getMob(instance);
        if (mob == null) {
            return;
        }
        if (mob instanceof Vex) {
            instance.clearNavPath();
            instance.navDirectVelocityOverride(true);
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

    static boolean usesItemDisplayBlockMaterial(@NotNull Material material) {
        return switch (material) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST -> true;
            default -> false;
        };
    }

    static @NotNull ItemDisplay.ItemDisplayTransform itemDisplayTransform() {
        return ItemDisplay.ItemDisplayTransform.NONE;
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

    static @NotNull Transformation itemDisplayTransformation() {
        return new Transformation(
                new Vector3f(0.0F, ITEM_DISPLAY_RENDER_Y_OFFSET, 0.0F),
                new Quaternionf(),
                new Vector3f(ITEM_DISPLAY_RENDER_SCALE, ITEM_DISPLAY_RENDER_SCALE, ITEM_DISPLAY_RENDER_SCALE),
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
        if (equipment.getHolder() instanceof Mob) {
            equipment.setItemInMainHandDropChance(0.0F);
            equipment.setItemInOffHandDropChance(0.0F);
            equipment.setHelmetDropChance(0.0F);
            equipment.setChestplateDropChance(0.0F);
            equipment.setLeggingsDropChance(0.0F);
            equipment.setBootsDropChance(0.0F);
        }
    }

    static void applyEquipment(
            @Nullable EntityEquipment equipment,
            @NotNull MobEquipmentConfig config
    ) {
        if (equipment == null) {
            return;
        }

        setEquipmentItem(equipment::setItemInMainHand, config.mainHand());
        setEquipmentItem(equipment::setItemInOffHand, config.offHand());
        setEquipmentItem(equipment::setHelmet, config.helmet());
        setEquipmentItem(equipment::setChestplate, config.chestplate());
        setEquipmentItem(equipment::setLeggings, config.leggings());
        setEquipmentItem(equipment::setBoots, config.boots());
    }

    private static void setEquipmentItem(
            @NotNull Consumer<ItemStack> setter,
            @Nullable String rawMaterial
    ) {
        Material material = resolveEquipmentMaterial(rawMaterial);
        if (material != null) {
            setter.accept(new ItemStack(material));
        }
    }

    @Nullable
    static Material resolveEquipmentMaterial(@Nullable String rawMaterial) {
        if (rawMaterial == null || rawMaterial.isBlank()) {
            return null;
        }

        String materialName = rawMaterial.trim();
        int separator = materialName.indexOf(':');
        if (separator >= 0) {
            materialName = materialName.substring(separator + 1);
        }
        return MaterialNameResolver.match(materialName);
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

    /**
     * 地上 Mob の前回目標からの水平移動量を判定します。
     *
     * @param instance 前回目標座標を保持する Mob インスタンス
     * @param target   今回の目標座標
     * @return 再経路探索が必要な距離を超えた場合は {@code true}
     */
    private boolean hasGroundTargetDrifted(@NotNull MobInstance instance, @NotNull Location target) {
        double dx = target.getX() - instance.navTargetX();
        double dz = target.getZ() - instance.navTargetZ();
        return hasGroundTargetDrifted(dx, dz);
    }

    /**
     * 地上 Mob の目標移動量を水平二軸だけで判定します。
     *
     * @param dx 前回目標からの X 方向差分
     * @param dz 前回目標からの Z 方向差分
     * @return 再経路探索が必要な距離を超えた場合は {@code true}
     */
    static boolean hasGroundTargetDrifted(double dx, double dz) {
        return dx * dx + dz * dz > PATH_TARGET_DRIFT_DISTANCE_SQ;
    }

    /**
     * Vex の前回目標からの三次元移動量を判定します。
     *
     * @param instance 前回目標座標を保持する Mob インスタンス
     * @param target   今回の目標座標
     * @return 再経路探索が必要な距離を超えた場合は {@code true}
     */
    private boolean hasVexTargetDrifted(@NotNull MobInstance instance, @NotNull Location target) {
        double dx = target.getX() - instance.navTargetX();
        double dy = target.getY() - instance.navTargetY();
        double dz = target.getZ() - instance.navTargetZ();
        return hasVexTargetDrifted(dx, dy, dz);
    }

    /**
     * Vex の目標移動量を三軸で判定します。
     *
     * @param dx 前回目標からの X 方向差分
     * @param dy 前回目標からの Y 方向差分
     * @param dz 前回目標からの Z 方向差分
     * @return 再経路探索が必要な距離を超えた場合は {@code true}
     */
    static boolean hasVexTargetDrifted(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz > PATH_TARGET_DRIFT_DISTANCE_SQ;
    }

    /**
     * Vex を目標地点へ三次元で飛行させます。
     *
     * <p>Vex は地上 Mob 用の Paper Pathfinder では経路を生成できないため、
     * 有界三次元 A* で安全なウェイポイントを計算します。実際の速度設定は
     * {@link #tickVexNavigation(MobInstance)} が毎 tick 行います。</p>
     *
     * @param instance        移動対象の Mob インスタンス
     * @param vex             移動させる Vex
     * @param target          追従する目標地点
     * @param aiSpeedModifier AI 設定の速度倍率
     * @param currentTick     Mob AI 内部 tick
     * @return 経路を更新した場合は {@code true}
     */
    private boolean moveVexTo(
            @NotNull MobInstance instance,
            @NotNull Vex vex,
            @NotNull Location target,
            double aiSpeedModifier,
            long currentTick) {
        Location current = vex.getLocation();
        if (current.distanceSquared(target) <= PATH_STOP_DISTANCE_SQ || aiSpeedModifier <= 0.0D) {
            stopPathfinding(instance);
            return false;
        }
        if (instance.navDirectVelocityOverride()) {
            instance.currentLocation(current);
            return false;
        }

        boolean targetDrifted = hasVexTargetDrifted(instance, target);
        List<Location> currentPath = instance.navPath();
        boolean hasPath = currentPath != null && instance.navPathIndex() < currentPath.size();
        long requiredInterval = currentPath != null && currentPath.isEmpty()
                ? VEX_NO_PATH_RECOMPUTE_INTERVAL_TICKS
                : PATH_RECOMPUTE_INTERVAL_TICKS;
        boolean intervalPassed = currentTick - instance.navRecomputeTick() >= requiredInterval;
        if (hasPath && !targetDrifted) {
            instance.navFlightSpeed(resolveVexFlightSpeed(instance, aiSpeedModifier));
            instance.currentLocation(current);
            return false;
        }
        if (!intervalPassed) {
            return false;
        }

        List<Location> path = calculateVexPath(vex, current, target);
        instance.navPath(path);
        instance.navPathIndex(0);
        instance.navFlightSpeed(resolveVexFlightSpeed(instance, aiSpeedModifier));
        instance.navTargetX(target.getX());
        instance.navTargetY(target.getY());
        instance.navTargetZ(target.getZ());
        instance.navRecomputeTick(currentTick);
        instance.currentLocation(current);
        if (path.isEmpty()) {
            vex.setVelocity(new Vector());
            instance.navFlightSpeed(0.0D);
            return false;
        }
        return true;
    }

    private @NotNull List<Location> calculateVexPath(
            @NotNull Vex vex,
            @NotNull Location current,
            @NotNull Location target) {
        double horizontalDistance = Math.hypot(target.getX() - current.getX(), target.getZ() - current.getZ());
        double verticalDistance = Math.abs(target.getY() - current.getY());
        if (horizontalDistance <= VEX_PATH_HORIZONTAL_RADIUS
                && verticalDistance <= VEX_PATH_VERTICAL_RADIUS
                && isVexSweepClear(vex, current, target)) {
            return List.of(target.clone());
        }

        VexFlightPathfinder.GridPoint start = gridPoint(current);
        VexFlightPathfinder.GridPoint goal = gridPoint(target);
        List<VexFlightPathfinder.GridPoint> gridPath = VexFlightPathfinder.findPath(
                start,
                goal,
                point -> isVexPositionClear(vex, gridLocation(current.getWorld(), point)),
                VEX_PATH_HORIZONTAL_RADIUS,
                VEX_PATH_VERTICAL_RADIUS,
                VEX_PATH_MAX_EXPANDED_NODES
        );
        if (gridPath.isEmpty()) {
            return List.of();
        }

        List<Location> path = new ArrayList<>(gridPath.size() + 1);
        for (VexFlightPathfinder.GridPoint point : gridPath) {
            path.add(gridLocation(current.getWorld(), point));
        }
        if (gridPath.getLast().equals(goal) && isVexSweepClear(vex, path.getLast(), target)) {
            path.add(target.clone());
        }
        return List.copyOf(path);
    }

    private boolean isVexSweepClear(@NotNull Vex vex, @NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) {
            return false;
        }
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        int steps = Math.max(1, (int) Math.ceil(distance / VEX_SWEEP_STEP));
        for (int step = 1; step <= steps; step++) {
            double ratio = (double) step / steps;
            Location sample = from.clone().add(delta.clone().multiply(ratio));
            if (!isVexPositionClear(vex, sample)) {
                return false;
            }
        }
        return true;
    }

    private boolean isVexPositionClear(@NotNull Vex vex, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null || world != vex.getWorld()) {
            return false;
        }

        Location current = vex.getLocation();
        Vector shift = location.toVector().subtract(current.toVector());
        BoundingBox bounds = vex.getBoundingBox().clone().shift(shift).expand(VEX_COLLISION_MARGIN);
        int minX = (int) Math.floor(bounds.getMinX());
        int maxX = (int) Math.floor(Math.nextDown(bounds.getMaxX()));
        int minY = (int) Math.floor(bounds.getMinY());
        int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()));
        int minZ = (int) Math.floor(bounds.getMinZ());
        int maxZ = (int) Math.floor(Math.nextDown(bounds.getMaxZ()));
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) {
            return false;
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    BoundingBox blockLocalBounds = toBlockLocalBounds(bounds, x, y, z);
                    if (block.getCollisionShape().overlaps(blockLocalBounds)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * ワールド座標の AABB を指定 block のローカル座標へ変換します。
     *
     * @param worldBounds ワールド座標の AABB
     * @param blockX      block のワールド X 座標
     * @param blockY      block のワールド Y 座標
     * @param blockZ      block のワールド Z 座標
     * @return block 原点を 0 とする新しい AABB
     */
    static @NotNull BoundingBox toBlockLocalBounds(
            @NotNull BoundingBox worldBounds,
            int blockX,
            int blockY,
            int blockZ) {
        return worldBounds.clone().shift(-blockX, -blockY, -blockZ);
    }

    private static @NotNull VexFlightPathfinder.GridPoint gridPoint(@NotNull Location location) {
        return new VexFlightPathfinder.GridPoint(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
    }

    private static @NotNull Location gridLocation(
            @NotNull World world,
            @NotNull VexFlightPathfinder.GridPoint point) {
        return new Location(
                world,
                point.x() + 0.5D,
                point.y() + VEX_PATH_NODE_Y_OFFSET,
                point.z() + 0.5D
        );
    }

    private double resolvePathfinderSpeed(@NotNull MobInstance instance, double aiSpeedModifier) {
        double statusSpeed = instance.template().statValue("MOVEMENT_SPEED", STANDARD_MOVEMENT_SPEED);
        double statusMultiplier = Math.max(0.0D, statusSpeed) / STANDARD_MOVEMENT_SPEED;
        double speed = Math.max(0.0D, aiSpeedModifier) * statusMultiplier * PATHFINDER_SPEED_MULTIPLIER;
        return Math.max(MIN_PATHFINDER_SPEED, Math.min(speed, MAX_PATHFINDER_SPEED));
    }

    private double resolveVexFlightSpeed(@NotNull MobInstance instance, double aiSpeedModifier) {
        double speed = resolvePathfinderSpeed(instance, aiSpeedModifier) * VEX_FLIGHT_SPEED_MULTIPLIER;
        return Math.max(MIN_VEX_FLIGHT_SPEED, Math.min(speed, MAX_VEX_FLIGHT_SPEED));
    }
}
