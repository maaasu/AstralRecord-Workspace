package io.github.maaasu.astralRecord.feature.mob.service;

import com.destroystokyo.paper.entity.Pathfinder;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEntityControllerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: Vex の world 座標 AABB を原点以外の block collision shape と同じローカル座標へ変換する。
     */
    @Test
    void vexBoundsAreConvertedToNonOriginBlockLocalCoordinates() {
        BoundingBox worldBounds = new BoundingBox(120.2D, 64.1D, -31.8D, 120.8D, 64.9D, -31.2D);

        BoundingBox localBounds = MobEntityController.toBlockLocalBounds(worldBounds, 120, 64, -32);

        assertEquals(0.2D, localBounds.getMinX(), 1.0E-12D);
        assertEquals(0.1D, localBounds.getMinY(), 1.0E-12D);
        assertEquals(0.2D, localBounds.getMinZ(), 1.0E-12D);
        assertEquals(0.8D, localBounds.getMaxX(), 1.0E-12D);
        assertEquals(0.9D, localBounds.getMaxY(), 1.0E-12D);
        assertEquals(0.8D, localBounds.getMaxZ(), 1.0E-12D);
        assertEquals(new BoundingBox(120.2D, 64.1D, -31.8D, 120.8D, 64.9D, -31.2D), worldBounds);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求
     * 検証契約: 地上 Mob は X/Z だけ、Vex は X/Y/Z の目標移動量で再経路探索を判定する。
     */
    @Test
    void groundDriftIgnoresVerticalMovementWhileVexDriftIncludesIt() {
        assertFalse(MobEntityController.hasGroundTargetDrifted(0.0D, 0.0D));
        assertTrue(MobEntityController.hasVexTargetDrifted(0.0D, 2.0D, 0.0D));
        assertTrue(MobEntityController.hasGroundTargetDrifted(2.0D, 0.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: Vex の strafe / retreat 用直接速度は経路状態を解除し、直後の経路 tick でも保持する。
     */
    @Test
    void vexDirectMovementSurvivesNavigationTick() {
        VexFixture fixture = vexFixture("vex_direct_movement_world");
        fixture.instance().navPath(List.of(fixture.location().clone().add(5.0D, 0.0D, 0.0D)));
        fixture.instance().navFlightSpeed(0.2D);
        fixture.instance().navRecomputeTick(10L);
        Vector directVelocity = new Vector(0.0D, 0.0D, 0.12D);

        fixture.controller().addVelocity(fixture.instance(), directVelocity);
        fixture.controller().tickVexNavigation(fixture.instance());
        fixture.controller().moveTo(
                fixture.instance(), fixture.location().clone().add(5.0D, 0.0D, 0.0D), 1.0D, 11L
        );
        fixture.controller().tickVexNavigation(fixture.instance());

        assertEquals(directVelocity, fixture.vex().getVelocity());
        assertEquals(null, fixture.instance().navPath());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 2. MobKnockbackService メソッド仕様 > ### ノックバック適用
     * 検証契約: addVelocity で Vex に加算したノックバックは直後の経路 tick で上書きしない。
     */
    @Test
    void vexKnockbackVelocitySurvivesNavigationTick() {
        VexFixture fixture = vexFixture("vex_knockback_world");
        fixture.vex().setVelocity(new Vector(0.1D, 0.0D, 0.0D));
        fixture.instance().navPath(List.of(fixture.location().clone().add(0.0D, 0.0D, 5.0D)));
        fixture.instance().navFlightSpeed(0.2D);
        Vector knockback = new Vector(0.3D, 0.2D, 0.0D);

        fixture.controller().addVelocity(fixture.instance(), knockback);
        fixture.controller().moveTo(
                fixture.instance(), fixture.location().clone().add(0.0D, 0.0D, 5.0D), 1.0D, 100L
        );
        fixture.controller().tickVexNavigation(fixture.instance());

        assertEquals(new Vector(0.4D, 0.2D, 0.0D), fixture.vex().getVelocity());
        assertEquals(null, fixture.instance().navPath());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: 明示的な停止は直接速度保護中でも velocity と経路状態を確実に消去する。
     */
    @Test
    void explicitVexStopClearsDirectVelocityAndOverride() {
        VexFixture fixture = vexFixture("vex_explicit_stop_world");
        fixture.controller().addVelocity(fixture.instance(), new Vector(0.2D, 0.1D, 0.0D));

        fixture.controller().stopPathfinding(fixture.instance());
        fixture.controller().tickVexNavigation(fixture.instance());

        assertEquals(new Vector(), fixture.vex().getVelocity());
        assertFalse(fixture.instance().navDirectVelocityOverride());
        assertEquals(null, fixture.instance().navPath());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: Vex の直接速度も次 tick の移動線分を検査し、壁へ入る場合だけ速度をゼロにする。
     */
    @Test
    void vexDirectVelocityStopsBeforeWall() {
        VexFixture fixture = vexFixture("vex_direct_collision_world");
        when(fixture.collisionShape().overlaps(any(BoundingBox.class))).thenReturn(true);

        fixture.controller().addVelocity(fixture.instance(), new Vector(1.0D, 0.0D, 0.0D));
        fixture.controller().tickVexNavigation(fixture.instance());

        assertEquals(new Vector(), fixture.vex().getVelocity());
        assertFalse(fixture.instance().navDirectVelocityOverride());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 4. Pathfinder 移動要求 > ### Vex 三次元経路
     * 検証契約: Vex の部分経路を使い切っても WANDER NPC の詰まり観測状態を維持する。
     */
    @Test
    void vexPathCompletionPreservesWanderStuckObservation() {
        VexFixture fixture = vexFixture("vex_wander_observation_world");
        Location observed = fixture.location().clone().subtract(0.1D, 0.0D, 0.0D);
        fixture.instance().navBlockedSinceTick(40L);
        fixture.instance().navLastObservedLocation(observed);
        fixture.instance().navPath(List.of(fixture.location().clone()));
        fixture.instance().navFlightSpeed(0.2D);

        fixture.controller().tickVexNavigation(fixture.instance());

        assertEquals(null, fixture.instance().navPath());
        assertEquals(40L, fixture.instance().navBlockedSinceTick());
        assertEquals(observed, fixture.instance().navLastObservedLocation());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 取得
     * 検証契約: spawn直後の非dead ArmorStandをPaper isValid反映前でも管理対象として利用可能にする。
     */
    @Test
    void freshArmorStandRemainsUsableBeforePaperMarksItValid() {
        ArmorStand armorStand = mock(ArmorStand.class);
        when(armorStand.isDead()).thenReturn(false);
        when(armorStand.isValid()).thenReturn(false);

        assertTrue(MobEntityController.isManagedEntityUsable(armorStand));

        verify(armorStand, never()).isValid();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 設定
     * 検証契約: 管理対象 Piglin はワールド環境による Zombified Piglin への変換を抑止する。
     */
    @Test
    void managedPiglinIsImmuneToZombification() {
        Piglin piglin = mock(Piglin.class);

        MobEntityController.disablePiglinZombification(piglin);

        verify(piglin).setImmuneToZombification(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 生成
     * 検証契約: CHEST/TRAPPED_CHEST/ENDER_CHESTをItemDisplay描画経路の対象Materialと判定する。
     */
    @Test
    void chestBlockNpcMaterialsSelectItemDisplayRoute() {
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.CHEST));
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.TRAPPED_CHEST));
        assertTrue(MobEntityController.usesItemDisplayBlockMaterial(Material.ENDER_CHEST));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 生成
     * 検証契約: BARREL/ANVIL等の通常block NPCはItemDisplayへ切り替えずBlockDisplayで描画する。
     */
    @Test
    void regularBlockNpcMaterialsKeepBlockDisplay() {
        assertFalse(MobEntityController.usesItemDisplayBlockMaterial(Material.BARREL));
        assertFalse(MobEntityController.usesItemDisplayBlockMaterial(Material.ANVIL));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 生成
     * 検証契約: chest ItemDisplayをtransform NONE、Transformation.translation (0,+0.375,0)、scale (0.75,0.75,0.75)で描画する。
     */
    @Test
    void chestItemDisplayUsesFullModelTransformAboveGround() {
        Transformation transformation = MobEntityController.itemDisplayTransformation();

        assertEquals(ItemDisplay.ItemDisplayTransform.NONE, MobEntityController.itemDisplayTransform());
        assertEquals(0.0F, transformation.getTranslation().x);
        assertEquals(0.375F, transformation.getTranslation().y);
        assertEquals(0.0F, transformation.getTranslation().z);
        assertEquals(0.75F, transformation.getScale().x);
        assertEquals(0.75F, transformation.getScale().y);
        assertEquals(0.75F, transformation.getScale().z);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 6. Mob 装備設定
     * 検証契約: 標準Material名をmain/off hand・各armor slotへ適用する。
     */
    @Test
    void standardMaterialsAreAppliedToConfiguredEquipmentSlots() {
        EntityEquipment equipment = mock(EntityEquipment.class);

        MobEntityController.applyEquipment(
                equipment,
                new MobEquipmentConfig(
                        "IRON_SWORD",
                        "minecraft:SHIELD",
                        "LEATHER_HELMET",
                        "IRON_CHESTPLATE",
                        "IRON_LEGGINGS",
                        "IRON_BOOTS"
                )
        );

        verify(equipment).setItemInMainHand(argThat(item -> item.getType() == Material.IRON_SWORD));
        verify(equipment).setItemInOffHand(argThat(item -> item.getType() == Material.SHIELD));
        verify(equipment).setHelmet(argThat(item -> item.getType() == Material.LEATHER_HELMET));
        verify(equipment).setChestplate(argThat(item -> item.getType() == Material.IRON_CHESTPLATE));
        verify(equipment).setLeggings(argThat(item -> item.getType() == Material.IRON_LEGGINGS));
        verify(equipment).setBoots(argThat(item -> item.getType() == Material.IRON_BOOTS));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 6. Mob 装備設定
     * 検証契約: item master参照等Materialでない値を実体装備へ反映しない。
     */
    @Test
    void itemReferencesAreIgnoredWhenTheyAreNotMaterials() {
        EntityEquipment equipment = mock(EntityEquipment.class);

        MobEntityController.applyEquipment(
                equipment,
                new MobEquipmentConfig("traveler_sword", null, null, null, null, null)
        );

        verify(equipment, never()).setItemInMainHand(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 2. 実体 Mob 初期化
     * 検証契約: Paper 1.21.11でOldEnumとなったVillager Profession/Typeを名前解決してsetterへ反映する。
     */
    @Test
    void paperOldEnumVillagerAppearanceIsApplied() {
        Villager villager = mock(Villager.class);
        MobVariantConfig variant = new MobVariantConfig(
                MobVariantConfig.Age.ADULT,
                null,
                null,
                null,
                "farmer",
                "taiga",
                4,
                null,
                null,
                null,
                null,
                null
        );

        new MobEntityController(PluginMock.builder().withPluginName("AstralRecordTest").build())
                .applyVariant(templateWithVariant(variant), villager);

        verify(villager).setProfession(Villager.Profession.FARMER);
        verify(villager).setVillagerType(Villager.Type.TAIGA);
        verify(villager).setVillagerLevel(4);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 2. 実体 Mob 初期化
     * 検証契約: ZombieVillagerのPaper OldEnum職業を名前解決して固有setterへ反映する。
     */
    @Test
    void paperOldEnumZombieVillagerProfessionIsApplied() {
        ZombieVillager zombieVillager = mock(ZombieVillager.class);
        MobVariantConfig variant = new MobVariantConfig(
                MobVariantConfig.Age.ADULT,
                null,
                null,
                null,
                "farmer",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        new MobEntityController(PluginMock.builder().withPluginName("AstralRecordTest").build())
                .applyVariant(templateWithVariant(variant), zombieVillager);

        verify(zombieVillager).setVillagerProfession(Villager.Profession.FARMER);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 2. 実体 Mob 初期化
     * 検証契約: 従来のJava enum型を使う外見差分も引き続き名前解決してsetterへ反映する。
     */
    @Test
    void javaEnumAppearanceRemainsSupported() {
        Sheep sheep = mock(Sheep.class);
        MobVariantConfig variant = new MobVariantConfig(
                MobVariantConfig.Age.ADULT,
                null,
                "blue",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        new MobEntityController(PluginMock.builder().withPluginName("AstralRecordTest").build())
                .applyVariant(templateWithVariant(variant), sheep);

        verify(sheep).setColor(DyeColor.BLUE);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 2. 実体 Mob 初期化
     * 検証契約: OldEnumの未知値はsetterへ反映せず、対象Mobの既定外見を維持する。
     */
    @Test
    void unknownPaperOldEnumAppearanceIsIgnored() {
        Villager villager = mock(Villager.class);
        MobVariantConfig variant = new MobVariantConfig(
                MobVariantConfig.Age.ADULT,
                null,
                null,
                null,
                "unknown_profession",
                "unknown_type",
                null,
                null,
                null,
                null,
                null,
                null
        );

        new MobEntityController(PluginMock.builder().withPluginName("AstralRecordTest").build())
                .applyVariant(templateWithVariant(variant), villager);

        verify(villager, never()).setProfession(any());
        verify(villager, never()).setVillagerType(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 2. MobEntityController メソッド仕様 > ### 実体 Mob 生成
     * 検証契約: ARMOR_STAND templateをvisible/arms/baseplateあり、gravity/collisionなし、全slot操作無効の装備carrierとしてspawn/bindする。
     */
    @Test
    void armorStandTemplateSpawnsFixedVisibleEquipmentCarrier() {
        World world = server().addSimpleWorld("training_dummy_world");
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobTemplate template = new MobTemplate(
                1, "training_dummy:test", MobCategory.ENEMY, "Training Dummy", null,
                1, EntityType.ARMOR_STAND, true, "ARMOR_STAND", List.of(), List.of(), null,
                new MobEquipmentConfig(null, null, "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"),
                List.of(), MobShieldConfig.EMPTY, MobIdleConfig.defaults(), false,
                MobInteractionsConfig.EMPTY, null, null, null
        );
        MobInstance instance = new MobInstance(UUID.randomUUID(), template, new Location(world, 1.5D, 64.0D, 2.5D));

        Entity entity = new MobEntityController(plugin).spawn(instance, instance.currentLocation());

        assertTrue(entity instanceof ArmorStand);
        ArmorStand armorStand = (ArmorStand) entity;
        assertFalse(armorStand.hasGravity());
        assertFalse(armorStand.isCollidable());
        assertTrue(armorStand.isVisible());
        assertTrue(armorStand.hasArms());
        assertTrue(armorStand.hasBasePlate());
        assertFalse(armorStand.isMarker());
        assertEquals(Set.of(EquipmentSlot.values()), armorStand.getDisabledSlots());
        assertEquals(Material.LEATHER_HELMET, armorStand.getEquipment().getHelmet().getType());
        assertEquals(entity.getUniqueId(), instance.bukkitEntityId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 5. 配置アンカーへの位置リセット
     * 検証契約: NPCを配置アンカーへ戻し、現在の視線方向を維持しながらMobInstanceの位置も同期する。
     */
    @Test
    void resetPositionReturnsNpcToAnchorAndKeepsCurrentRotation() {
        World world = server().addSimpleWorld("npc_reset_world");
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        Location anchor = new Location(world, 1.5D, 64.0D, 2.5D, 30.0F, 5.0F);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                templateWithVariant(MobVariantConfig.DEFAULT, EntityType.ARMOR_STAND),
                anchor
        );
        MobEntityController controller = new MobEntityController(plugin);

        Entity entity = controller.spawn(instance, anchor);

        assertTrue(entity instanceof ArmorStand);
        Location moved = new Location(world, 10.5D, 64.0D, 12.5D, 120.0F, 15.0F);
        assertTrue(entity.teleport(moved));

        controller.resetPosition(instance, anchor);

        Location reset = entity.getLocation();
        assertEquals(anchor.getX(), reset.getX(), 1.0E-6D);
        assertEquals(anchor.getY(), reset.getY(), 1.0E-6D);
        assertEquals(anchor.getZ(), reset.getZ(), 1.0E-6D);
        assertEquals(moved.getYaw(), reset.getYaw());
        assertEquals(moved.getPitch(), reset.getPitch());
        assertEquals(anchor.getX(), instance.currentLocation().getX(), 1.0E-6D);
        assertEquals(anchor.getZ(), instance.currentLocation().getZ(), 1.0E-6D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-実体Mob制御.md
     * 章・見出し: # 12_3-実体Mob制御 > ## 3. 実体 Mob 取得・同期
     * 検証契約: 実体の現在位置を同期すると、疑似 Player 表示用の頭部 yaw / pitch も現在角度へ更新する。
     */
    @Test
    void syncLocationUpdatesPlayerViewHeadRotation() {
        World world = server().addSimpleWorld("npc_head_rotation_world");
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        Location initial = new Location(world, 1.5D, 64.0D, 2.5D, 10.0F, 0.0F);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                templateWithVariant(MobVariantConfig.DEFAULT, EntityType.ARMOR_STAND),
                initial
        );
        MobEntityController controller = new MobEntityController(plugin);
        Entity entity = controller.spawn(instance, initial);
        Location rotated = new Location(world, 2.5D, 64.0D, 3.5D, 135.0F, 22.5F);
        assertTrue(entity.teleport(rotated));

        assertTrue(controller.syncLocation(instance));

        assertEquals(rotated.getYaw(), instance.headYaw());
        assertEquals(rotated.getPitch(), instance.headPitch());
    }

    /**
     * 外見差分テスト用の NPC テンプレートを生成します。
     *
     * @param variant 検証対象の外見差分
     * @return 指定された外見差分を持つ NPC テンプレート
     */
    private static MobTemplate templateWithVariant(MobVariantConfig variant) {
        return templateWithVariant(variant, EntityType.VILLAGER);
    }

    private static MobTemplate templateWithVariant(MobVariantConfig variant, EntityType entityType) {
        return new MobTemplate(
                1,
                "npc:test_villager",
                MobCategory.NPC,
                "Test Villager",
                null,
                1,
                entityType,
                false,
                null,
                List.of(),
                List.of(),
                null,
                variant,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                true,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
    }

    private VexFixture vexFixture(String worldName) {
        World world = mock(World.class);
        Location location = new Location(world, 0.5D, 64.0D, 0.5D);
        Vex vex = mock(Vex.class);
        Block block = mock(Block.class);
        VoxelShape collisionShape = mock(VoxelShape.class);
        AtomicReference<Vector> velocity = new AtomicReference<>(new Vector());
        when(world.getName()).thenReturn(worldName);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(block.getCollisionShape()).thenReturn(collisionShape);
        when(collisionShape.overlaps(any(BoundingBox.class))).thenReturn(false);
        when(vex.getWorld()).thenReturn(world);
        when(vex.getLocation()).thenAnswer(ignored -> location.clone());
        when(vex.getBoundingBox()).thenReturn(new BoundingBox(0.2D, 64.0D, 0.2D, 0.8D, 64.8D, 0.8D));
        when(vex.getVelocity()).thenAnswer(ignored -> velocity.get().clone());
        when(vex.getPathfinder()).thenReturn(mock(Pathfinder.class));
        doAnswer(invocation -> {
            velocity.set(invocation.getArgument(0, Vector.class).clone());
            return null;
        }).when(vex).setVelocity(any(Vector.class));
        MobTemplate template = new MobTemplate(
                1, "enemy:test_vex", MobCategory.ENEMY, "Test Vex", null,
                1, EntityType.VEX, false, null, List.of(), List.of(), null,
                MobEquipmentConfig.EMPTY, List.of(), MobShieldConfig.EMPTY, MobIdleConfig.defaults(), false,
                MobInteractionsConfig.EMPTY, null, null, null
        );
        MobInstance instance = new MobInstance(UUID.randomUUID(), template, location);
        MobEntityController controller = spy(new MobEntityController(
                PluginMock.builder().withPluginName("AstralRecordTest").build()
        ));
        doReturn(vex).when(controller).getMob(instance);
        return new VexFixture(controller, instance, vex, location, collisionShape);
    }

    private record VexFixture(
            MobEntityController controller,
            MobInstance instance,
            Vex vex,
            Location location,
            VoxelShape collisionShape) {
    }
}
