package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DodgeServiceSpellStepTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### しゃがみ解除ドッジ判定
     * 検証契約: 武器を選択中に無償化resolverが0を返すドッジはENG 0でも拒否されず、実消費にも同じ0が渡される。
     */
    @Test
    void resolvedFreeDodgeBypassesEnergyShortageAndConsumesZero() {
        PlayerMock bukkitPlayer = server().addPlayer();
        bukkitPlayer.teleport(new Location(bukkitPlayer.getWorld(), 0.0D, 64.0D, 0.0D));
        bukkitPlayer.getWorld().getBlockAt(0, 63, 0).setType(Material.STONE);
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);

        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(player)).thenReturn(
                DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 100.0D, 0.0D)
        );
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getItemModelInHand(eq(player), eq(EquipmentSlot.HAND)))
            .thenReturn(DesignTestFixtures.equipmentItem(
                "test_weapon",
                "test_weapon",
                ItemEquipmentStatType.FLAT
            ));
        DodgeService dodgeService = new DodgeService(
                plugin,
                statusService,
                inventoryService,
                mock(PlayerHudService.class),
                mock(ParticleDisplayService.class)
        );
        dodgeService.setEnergyCostResolver(ignored -> 0.0D);

        assertTrue(dodgeService.beginSneakWindow(player));
        dodgeService.tryTriggerOnSneakRelease(player);

        verify(statusService).consumeEnergy(player, 0.0D);
        verify(statusService).getStatus(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 地上ドッジ受付開始
     * 検証契約: 現在選択中のメインホットバー項目が武器でない場合、地上・生存・エネルギー十分でもドッジ受付を開始しない。
     */
    @Test
    void nonWeaponHotbarSelectionDoesNotOpenDodgeWindow() {
        PlayerMock bukkitPlayer = server().addPlayer();
        bukkitPlayer.teleport(new Location(bukkitPlayer.getWorld(), 0.0D, 64.0D, 0.0D));
        bukkitPlayer.getWorld().getBlockAt(0, 63, 0).setType(Material.STONE);
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);

        AstralRecord plugin = mock(AstralRecord.class);
        StatusService statusService = mock(StatusService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getItemModelInHand(eq(player), eq(EquipmentSlot.HAND)))
            .thenReturn(DesignTestFixtures.item("test_potion", ItemCategory.CONSUMABLE, 1));
        DodgeService dodgeService = new DodgeService(
            plugin,
            statusService,
            inventoryService,
            mock(PlayerHudService.class),
            mock(ParticleDisplayService.class)
        );

        assertFalse(dodgeService.beginSneakWindow(player));
        dodgeService.tryTriggerOnSneakRelease(player);

        verify(statusService, never()).getStatus(player);
        verify(statusService, never()).consumeEnergy(eq(player), anyDouble());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### しゃがみ解除ドッジ判定
     * 検証契約: 武器選択中に受付を開始しても、解除時点で武器以外へ持ち替えていればドッジを発動せずエネルギーを消費しない。
     */
    @Test
    void switchingAwayFromWeaponBeforeReleaseDoesNotTriggerDodge() {
        PlayerMock bukkitPlayer = server().addPlayer();
        bukkitPlayer.teleport(new Location(bukkitPlayer.getWorld(), 0.0D, 64.0D, 0.0D));
        bukkitPlayer.getWorld().getBlockAt(0, 63, 0).setType(Material.STONE);
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);

        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        StatusService statusService = mock(StatusService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.getItemModelInHand(eq(player), eq(EquipmentSlot.HAND)))
            .thenReturn(
                DesignTestFixtures.equipmentItem(
                    "test_weapon",
                    "test_weapon",
                    ItemEquipmentStatType.FLAT
                ),
                DesignTestFixtures.item("test_potion", ItemCategory.CONSUMABLE, 1)
            );
        DodgeService dodgeService = new DodgeService(
            plugin,
            statusService,
            inventoryService,
            mock(PlayerHudService.class),
            mock(ParticleDisplayService.class)
        );

        assertTrue(dodgeService.beginSneakWindow(player));
        dodgeService.tryTriggerOnSneakRelease(player);

        verify(statusService, never()).getStatus(player);
        verify(statusService, never()).consumeEnergy(eq(player), anyDouble());
    }
}
