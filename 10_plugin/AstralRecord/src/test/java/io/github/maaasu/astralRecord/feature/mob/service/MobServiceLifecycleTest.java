package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobServiceLifecycleTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### NPC プレイヤースキン表示同期
     * 検証契約: 5 tick 側の viewer 更新は、疑似 Player の表示開始・解除を担う lifecycle 同期へ現在集合を渡す。
     */
    @Test
    void viewerUpdateInvokesPlayerSkinLifecycleSync() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        World world = server().addSimpleWorld("npc_player_view_lifecycle_world");
        server().addPlayer().teleport(new Location(world, 1.5D, 64.0D, 1.5D));

        try (MockedConstruction<NpcPlayerSkinPacketService> constructed =
                     mockConstruction(NpcPlayerSkinPacketService.class)) {
            MobService service = new MobService(plugin, mock(MobRepository.class));
            MobInstance instance = service.spawn(template(), new Location(world, 0.5D, 64.0D, 0.5D));
            assertNotNull(instance);
            NpcPlayerSkinPacketService packetService = constructed.constructed().getFirst();
            clearInvocations(packetService);

            service.updateViewers();

            verify(packetService).sync(
                    org.mockito.ArgumentMatchers.same(instance),
                    org.mockito.ArgumentMatchers.argThat(viewers -> viewers.size() == 1)
            );
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### Mob 発光状態更新
     * 検証契約: player-skin NPC の発光は実体 Entity と表示中の疑似 Player の両方へ委譲する。
     */
    @Test
    void playerSkinNpcGlowUpdatesRealEntityAndPacketView() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        World world = server().addSimpleWorld("npc_player_glow_world");
        Location location = new Location(world, 0.5D, 64.0D, 0.5D);
        UUID entityId = UUID.randomUUID();
        Entity realEntity = mock(Entity.class);
        when(realEntity.getUniqueId()).thenReturn(entityId);
        when(realEntity.getEntityId()).thenReturn(42);

        try (MockedConstruction<MobEntityController> controllers = mockConstruction(
                     MobEntityController.class,
                     (controller, context) -> {
                         doAnswer(invocation -> {
                             MobInstance spawned = invocation.getArgument(0);
                             spawned.bindEntity(entityId, 42, location);
                             return realEntity;
                         }).when(controller).spawn(any(MobInstance.class), any(Location.class));
                         when(controller.getEntity(any(MobInstance.class))).thenReturn(realEntity);
                     });
             MockedConstruction<NpcPlayerSkinPacketService> constructed =
                     mockConstruction(NpcPlayerSkinPacketService.class)) {
            MobService service = new MobService(plugin, mock(MobRepository.class));
            MobInstance instance = service.spawn(playerSkinTemplate(), location);
            assertNotNull(instance);
            NpcPlayerSkinPacketService packetService = constructed.constructed().getFirst();
            clearInvocations(packetService);

            service.setGlowing(instance, true);

            assertTrue(instance.glowing());
            verify(realEntity).setGlowing(true);
            verify(packetService).setGlowing(instance, true);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### NPC プレイヤースキン表示同期
     * 検証契約: 疑似 Player の Entity flags metadata は発光時だけ 0x40 を持つ。
     */
    @Test
    void playerSkinEntityFlagsReflectGlowState() {
        assertEquals((byte) 0x40, NpcPlayerSkinPacketService.entityFlags(true));
        assertEquals((byte) 0, NpcPlayerSkinPacketService.entityFlags(false));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### Mob 破棄
     * 検証契約: 個別破棄時に、対象 Mob の runtime 状態解放先へ UUID を一度通知する。
     */
    @Test
    void destroyNotifiesRuntimeStateCleanupOnce() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService service = new MobService(plugin, mock(MobRepository.class));
        List<UUID> destroyed = new ArrayList<>();
        service.setDestroyListener(destroyed::add);
        World world = server().addSimpleWorld("mob_lifecycle_world");

        MobInstance first = service.spawn(template(), new Location(world, 0.5D, 64.0D, 0.5D));
        assertNotNull(first);

        assertTrue(service.destroy(first.instanceId()));
        assertEquals(List.of(first.instanceId()), destroyed);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### Mob HP回復
     * 検証契約: Mob HP回復は上限適用後の実回復量だけを通知する。
     */
    @Test
    void recoverHealthNotifiesActualAmount() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService service = new MobService(plugin, mock(MobRepository.class));
        MobInstance instance = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        instance.currentHealth(35.0D);
        List<Double> notifiedAmounts = new ArrayList<>();
        service.setHealthRecoveryListener((notifiedInstance, amount) -> {
            assertSame(instance, notifiedInstance);
            notifiedAmounts.add(amount);
        });

        assertEquals(65.0D, service.recoverHealth(instance, 80.0D), 0.0001D);
        assertEquals(List.of(65.0D), notifiedAmounts);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### 全 Mob 破棄
     * 検証契約: 全破棄時に、各 Mob の runtime 状態解放先へ UUID を一度ずつ通知する。
     */
    @Test
    void destroyAllNotifiesRuntimeStateCleanupOncePerMob() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService service = new MobService(plugin, mock(MobRepository.class));
        List<UUID> destroyed = new ArrayList<>();
        service.setDestroyListener(destroyed::add);
        World world = server().addSimpleWorld("mob_lifecycle_all_world");

        MobInstance first = service.spawn(template(), new Location(world, 0.5D, 64.0D, 0.5D));
        MobInstance second = service.spawn(template(), new Location(world, 2.5D, 64.0D, 0.5D));
        assertNotNull(first);
        assertNotNull(second);

        assertEquals(2, service.destroyAll());
        assertEquals(List.of(first.instanceId(), second.instanceId()), destroyed);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### 視認距離外 enemy 破棄
     * 検証契約: 視認距離内のプレイヤーがいない場合でも、維持指定された enemy は自動破棄されず、通常 enemy だけが破棄される。
     */
    @Test
    void destroyEnemiesOutsideViewDistanceKeepsExplicitlyPreservedEnemy() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService service = new MobService(plugin, mock(MobRepository.class));
        World world = server().addSimpleWorld("mob_view_distance_world");

        MobInstance kept = service.spawn(template(), new Location(world, 0.5D, 64.0D, 0.5D));
        MobInstance removed = service.spawn(template(), new Location(world, 2.5D, 64.0D, 0.5D));
        assertNotNull(kept);
        assertNotNull(removed);
        kept.keepWhenUnobserved(true);

        assertEquals(1, service.destroyEnemiesOutsideViewDistance());
        assertNotNull(service.getInstance(kept.instanceId()));
        assertNull(service.getInstance(removed.instanceId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 1. MobService メソッド仕様 > ### 実体から Mob インスタンス取得
     * 検証契約: Ender Dragon 等の複合 Entity 部位を親 Entity の管理 Mob へ解決する。
     */
    @Test
    void complexEntityPartResolvesToManagedParentMob() {
        PluginMock plugin = PluginMock.builder().withPluginName("AstralRecordTest").build();
        MobService service = new MobService(plugin, mock(MobRepository.class));
        World world = server().addSimpleWorld("complex_mob_world");
        MobInstance instance = service.spawn(template(), new Location(world, 0.5D, 64.0D, 0.5D));
        assertNotNull(instance);

        ComplexLivingEntity parent = mock(ComplexLivingEntity.class);
        when(parent.getUniqueId()).thenReturn(instance.bukkitEntityId());
        ComplexEntityPart part = mock(ComplexEntityPart.class);
        when(part.getParent()).thenReturn(parent);

        assertEquals(instance, service.getInstanceByEntity(part));
    }

    private static MobTemplate template() {
        return new MobTemplate(
                1,
                "enemy:lifecycle_test",
                MobCategory.ENEMY,
                "Lifecycle Test",
                null,
                1,
                EntityType.ARMOR_STAND,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
    }

    private static MobTemplate playerSkinTemplate() {
        return new MobTemplate(
                1,
                "npc:player_skin_lifecycle_test",
                MobCategory.NPC,
                "Player Skin Lifecycle Test",
                null,
                1,
                EntityType.VILLAGER,
                "PLAYER",
                null,
                false,
                null,
                List.of(),
                List.of(),
                new MobSkin("texture", "signature"),
                MobVariantConfig.DEFAULT,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                true,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

}
