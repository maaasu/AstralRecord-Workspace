package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.OverworldTeleportGuiSetting;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionEventHandler;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverworldTeleportGuiEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: 転送 GUI を閉じた場合、GUI 表示中だけ付与した暗黒エフェクトを解除する。
     */
    @Test
    void closingTransferGuiClearsGuiDarkness() {
        Player player = server().addPlayer();
        PluginMock registrationPlugin = MockBukkit.createMockPlugin("OverworldTeleportGuiEventHandlerTest");
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        when(teleportService.listDestinations()).thenReturn(List.of(destination()));
        OverworldTeleportGuiEventHandler handler = new OverworldTeleportGuiEventHandler(
                new OverworldTeleportGui(),
                teleportService
        );
        server().getPluginManager().registerEvents(
                new GuiSessionTransitionEventHandler(plugin, new GuiSessionTransitionService()),
                registrationPlugin
        );
        server().getPluginManager().registerEvents(handler, registrationPlugin);

        try (var astPlayerCache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            astPlayerCache.when(() -> AstPlayerCache.get(player)).thenReturn(mock(AstPlayer.class));
            assertTrue(handler.open(player));
        }

        assertNotNull(player.getPotionEffect(PotionEffectType.DARKNESS));

        player.closeInventory();
        server().getScheduler().performOneTick();

        assertNull(player.getPotionEffect(PotionEffectType.DARKNESS));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: 遅延された転送 GUI の再表示が成功した場合、表示中の暗黒エフェクトを再付与する。
     */
    @Test
    void reopeningTransferGuiReappliesGuiDarknessAfterDelayedTransition() {
        Player player = server().addPlayer();
        PluginMock registrationPlugin = MockBukkit.createMockPlugin("OverworldTeleportGuiReopenTest");
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        when(teleportService.listDestinations()).thenReturn(List.of(destination()));
        OverworldTeleportGuiEventHandler handler = new OverworldTeleportGuiEventHandler(
                new OverworldTeleportGui(),
                teleportService
        );
        server().getPluginManager().registerEvents(
                new GuiSessionTransitionEventHandler(plugin, new GuiSessionTransitionService()),
                registrationPlugin
        );
        server().getPluginManager().registerEvents(handler, registrationPlugin);

        try (
                var astPlayerCache = org.mockito.Mockito.mockStatic(AstPlayerCache.class);
                var javaPlugin = org.mockito.Mockito.mockStatic(JavaPlugin.class)
        ) {
            astPlayerCache.when(() -> AstPlayerCache.get(player)).thenReturn(mock(AstPlayer.class));
            javaPlugin.when(() -> JavaPlugin.getPlugin(AstralRecord.class)).thenReturn(plugin);

            assertTrue(handler.open(player));
            assertNotNull(player.getPotionEffect(PotionEffectType.DARKNESS));

            assertTrue(handler.open(player));
            server().getScheduler().performOneTick();
            server().getScheduler().performOneTick();

            assertNotNull(player.getPotionEffect(PotionEffectType.DARKNESS));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 2. BASE から OVERWORLD へ移動 > ### 処理要点
     * 検証契約: 別のプラグイン GUI へ遷移した場合、転送 GUI 用の暗黒エフェクトを解除する。
     */
    @Test
    void openingAnotherPluginGuiClearsGuiDarkness() {
        Player player = server().addPlayer();
        PluginMock registrationPlugin = MockBukkit.createMockPlugin("OverworldTeleportGuiOtherGuiTest");
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        OverworldTeleportService teleportService = mock(OverworldTeleportService.class);
        when(teleportService.listDestinations()).thenReturn(List.of(destination()));
        OverworldTeleportGuiEventHandler handler = new OverworldTeleportGuiEventHandler(
                new OverworldTeleportGui(),
                teleportService
        );
        server().getPluginManager().registerEvents(
                new GuiSessionTransitionEventHandler(plugin, new GuiSessionTransitionService()),
                registrationPlugin
        );
        server().getPluginManager().registerEvents(handler, registrationPlugin);

        try (var astPlayerCache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            astPlayerCache.when(() -> AstPlayerCache.get(player)).thenReturn(mock(AstPlayer.class));
            assertTrue(handler.open(player));
        }

        assertNotNull(player.getPotionEffect(PotionEffectType.DARKNESS));

        player.openInventory(otherPluginGui());
        server().getScheduler().performOneTick();

        assertNull(player.getPotionEffect(PotionEffectType.DARKNESS));
    }

    private WorldMasterData destination() {
        return new WorldMasterData(
                1,
                "overworld",
                "Overworld",
                WorldType.OVERWORLD,
                "",
                "",
                false,
                false,
                0,
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "",
                "GRASS_BLOCK",
                null,
                new OverworldTeleportGuiSetting(10)
        );
    }

    private Inventory otherPluginGui() {
        OtherGuiHolder holder = new OtherGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, 9);
        holder.setInventory(inventory);
        return inventory;
    }

    private static final class OtherGuiHolder implements InventoryHolder {
        private Inventory inventory;

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
