package io.github.maaasu.astralRecord.feature.mob.service;

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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobEntityControllerTest extends MockBukkitTestBase {

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
     * 外見差分テスト用の NPC テンプレートを生成します。
     *
     * @param variant 検証対象の外見差分
     * @return 指定された外見差分を持つ NPC テンプレート
     */
    private static MobTemplate templateWithVariant(MobVariantConfig variant) {
        return new MobTemplate(
                1,
                "npc:test_villager",
                MobCategory.NPC,
                "Test Villager",
                null,
                1,
                EntityType.VILLAGER,
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
}
