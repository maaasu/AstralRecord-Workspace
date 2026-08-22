package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.core.CommandRegister;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.hud.service.AdminMessageBossBarService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreativeFlySpeedCommandTest extends MockBukkitTestBase {

    @AfterEach
    void clearAstPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: 100%を実行者自身へ設定した場合、Bukkitの標準クリエイティブ飛行速度0.1を設定する。
     */
    @Test
    void mapsOneHundredPercentToCreativeDefaultSpeed() {
        CreativeFlySpeedCommand command = new CreativeFlySpeedCommand();
        Player sender = mock(Player.class);
        when(sender.getName()).thenReturn("Admin");

        command.executeCommand(sender, new String[] {"100"});

        verify(sender).setFlySpeed(0.1F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: 数値でない値または0〜1000%外の値では、対象プレイヤーの飛行速度を変更しない。
     */
    @Test
    void rejectsNonNumericAndOutOfRangePercentages() {
        CreativeFlySpeedCommand command = new CreativeFlySpeedCommand();
        Player sender = mock(Player.class);

        command.executeCommand(sender, new String[] {"not-a-number"});
        command.executeCommand(sender, new String[] {"1000.1"});

        verify(sender, never()).setFlySpeed(anyFloat());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: AstCommandの実行入口ではpermission 99未満を拒否し、permission 99以上だけが速度を変更できる。
     */
    @Test
    void enforcesAdminPermissionAtOnCommandBoundary() {
        CreativeFlySpeedCommand command = new CreativeFlySpeedCommand();
        Player regularPlayer = mock(Player.class);
        when(regularPlayer.getName()).thenReturn("Regular");
        when(regularPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        cachePlayer(regularPlayer, false);

        assertFalse(command.canUse(regularPlayer));
        command.onCommand(regularPlayer, null, "flyspeed", new String[] {"100"});
        verify(regularPlayer, never()).setFlySpeed(anyFloat());

        Player adminPlayer = mock(Player.class);
        when(adminPlayer.getName()).thenReturn("Admin");
        when(adminPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
        cachePlayer(adminPlayer, true);

        assertTrue(command.canUse(adminPlayer));
        command.onCommand(adminPlayer, null, "flyspeed", new String[] {"100"});
        verify(adminPlayer).setFlySpeed(0.1F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: コンソールで対象プレイヤーを省略した実行はP_5305を返し、速度変更を行わない。
     */
    @Test
    void rejectsConsoleWhenTargetIsOmitted() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        CreativeFlySpeedCommand command = new CreativeFlySpeedCommand();

        command.onCommand(console, null, "flyspeed", new String[] {"100"});

        verify(console).sendMessage(contains("コンソールから実行する場合は対象プレイヤーを指定してください。"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: CommandRegisterの実登録経路で、flyspeedにCreativeFlySpeedCommandが登録される。
     */
    @Test
    void registersFlySpeedCommandWithCommandManager() {
        CommandManager commandManager = mock(CommandManager.class);
        doThrow(new IllegalStateException("stop after flyspeed registration"))
            .when(commandManager)
            .registerCommand(eq("am"), any(AstCommand.class), any());

        try (MockedStatic<CommandManager> managers = org.mockito.Mockito.mockStatic(CommandManager.class)) {
            managers.when(CommandManager::getInstance).thenReturn(commandManager);

            assertThrows(IllegalStateException.class, () -> new CommandRegister(
                mock(ItemService.class),
                mock(ItemStackFactory.class),
                mock(MobService.class),
                mock(MobSpawnerService.class),
                mock(NpcPlacementService.class),
                mock(WorldService.class),
                mock(SkillTreeService.class),
                mock(GatheringService.class),
                mock(GatheringSpawnerService.class),
                mock(TextDisplayPlacementService.class),
                mock(TeleporterService.class),
                mock(TrainingDummyService.class),
                mock(TrainingDummyGui.class),
                mock(AdminMessageBossBarService.class),
                () -> mock(ParticleDisplayService.class)
            ));

            verify(commandManager).registerCommand(eq("flyspeed"), isA(CreativeFlySpeedCommand.class));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 3. クリエイティブ飛行速度設定
     * 検証契約: コンソールから対象プレイヤーを指定した場合、指定対象だけへ200%の速度（0.2）を設定する。
     */
    @Test
    void appliesPercentageToExplicitOnlineTarget() {
        Player target = server().addPlayer("Target");

        CreativeFlySpeedCommand command = new CreativeFlySpeedCommand();
        command.executeCommand(server().getConsoleSender(), new String[] {"200", "Target"});

        assertEquals(0.2F, target.getFlySpeed(), 0.000001F);
    }

    private void cachePlayer(Player player, boolean hasAdminPermission) {
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.hasPermissionLevel(99)).thenReturn(hasAdminPermission);
        AstPlayerCache.put(astPlayer);
    }
}
