package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobServiceLifecycleTest extends MockBukkitTestBase {

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
}
