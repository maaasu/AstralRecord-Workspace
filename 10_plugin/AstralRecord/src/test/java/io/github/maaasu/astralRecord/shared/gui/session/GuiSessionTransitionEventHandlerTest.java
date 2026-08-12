package io.github.maaasu.astralRecord.shared.gui.session;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuiSessionTransitionEventHandlerTest extends MockBukkitTestBase {
    private PluginMock registrationPlugin;
    private GuiSessionTransitionService transitionService;
    private GuiSessionTransitionEventHandler transitionEventHandler;
    private EndRecorder endRecorder;

    @BeforeEach
    void registerSharedLifecycle() {
        registrationPlugin = MockBukkit.createMockPlugin("GuiSessionTransitionTest");
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getServer()).thenReturn(server());
        transitionService = new GuiSessionTransitionService();
        endRecorder = new EndRecorder();
        transitionEventHandler = new GuiSessionTransitionEventHandler(plugin, transitionService);
        server().getPluginManager().registerEvents(transitionEventHandler, registrationPlugin);
        server().getPluginManager().registerEvents(endRecorder, registrationPlugin);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: Bukkit の close → open → 次 tick 順序では前画面を終了せず、最後の手動 close だけが中央管理の CLOSE 音と終了イベントを一度発生させる。
     */
    @Test
    void internalOpenSuppressesTheSourceCloseUntilTheFinalManualClose() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        Inventory target = managedInventory();

        player.openInventory(source);
        player.openInventory(target);
        server().getScheduler().performOneTick();

        assertTrue(endRecorder.inventories().isEmpty());
        assertEquals(0L, closeSoundCount(player));

        player.closeInventory();
        server().getScheduler().performOneTick();

        assertEquals(List.of(target), endRecorder.inventories());
        assertEquals(List.of(GuiSessionEndReason.MANUAL_CLOSE), endRecorder.reasons());
        assertEquals(1L, closeSoundCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 6. 自動保存中の close 再表示
     * 検証契約: 保存中 close の shared continuation は close handler と scheduler の順序に関係なく再表示を同一セッションの遷移として扱い、CLOSE 音・終了イベントを発生させない。
     */
    @Test
    void continuationReopenKeepsTheSessionAliveAcrossCloseAndSchedulerTicks() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        Inventory target = managedInventory();
        AtomicInteger targetOpened = new AtomicInteger();
        server().getPluginManager().registerEvents(
            new ContinuationRequestingCloseListener(player, source, target, targetOpened::incrementAndGet),
            registrationPlugin
        );

        player.openInventory(source);
        player.closeInventory();
        server().getScheduler().performOneTick();

        assertSame(target, player.getOpenInventory().getTopInventory());
        assertTrue(endRecorder.inventories().isEmpty());
        assertEquals(0L, closeSoundCount(player));
        assertEquals(1, targetOpened.get());

        player.closeInventory();
        server().getScheduler().performOneTick();

        assertEquals(List.of(target), endRecorder.inventories());
        assertEquals(1L, closeSoundCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 継続先の open が cancel された場合は source session だけを終了し、表示成功後 callback と stale な継続状態を残さない。
     */
    @Test
    void cancelledContinuationTargetEndsTheSourceWithoutRunningTheSuccessCallback() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        Inventory target = managedInventory();
        AtomicInteger targetOpened = new AtomicInteger();
        server().getPluginManager().registerEvents(
            new ContinuationRequestingCloseListener(player, source, target, targetOpened::incrementAndGet),
            registrationPlugin
        );
        server().getPluginManager().registerEvents(new CancelTargetOpenListener(target), registrationPlugin);

        player.openInventory(source);
        player.closeInventory();
        server().getScheduler().performOneTick();

        assertEquals(List.of(source), endRecorder.inventories());
        assertEquals(1L, closeSoundCount(player));
        assertEquals(0, targetOpened.get());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: close 後の継続予約より先に別の管理 GUI が開いた場合、古い継続 task はその GUI を上書きせず、後続 GUI の手動 close だけを終了する。
     */
    @Test
    void staleContinuationDoesNotOverwriteAnotherGuiOpenedBeforeTheNextTick() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        Inventory continuationTarget = managedInventory();
        Inventory otherGui = managedInventory();
        server().getPluginManager().registerEvents(
            new ContinuationRequestingCloseListener(player, source, continuationTarget),
            registrationPlugin
        );

        player.openInventory(source);
        player.closeInventory();
        player.openInventory(otherGui);
        server().getScheduler().performOneTick();

        assertSame(otherGui, player.getOpenInventory().getTopInventory());
        assertTrue(endRecorder.inventories().isEmpty());
        assertEquals(0L, closeSoundCount(player));

        player.closeInventory();
        server().getScheduler().performOneTick();

        assertEquals(List.of(otherGui), endRecorder.inventories());
        assertEquals(1L, closeSoundCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-イベント.md
     * 章・見出し: # 09_3-イベント > ## 4. 共通 GUI セッション終了
     * 検証契約: ログアウトは現在の管理 GUI の終了 event を一度だけ発行して cleanup へ委譲するが、CLOSE 音は再生しない。
     */
    @Test
    void quitEndsTheCurrentSessionSilentlyExactlyOnce() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        PlayerQuitEvent quitEvent = mock(PlayerQuitEvent.class);
        when(quitEvent.getPlayer()).thenReturn(player);

        player.openInventory(source);
        transitionEventHandler.onPlayerQuit(quitEvent);
        server().getScheduler().performOneTick();

        assertEquals(List.of(source), endRecorder.inventories());
        assertEquals(List.of(GuiSessionEndReason.PLAYER_QUIT), endRecorder.reasons());
        assertEquals(0L, closeSoundCount(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 遷移先 open が cancel された場合は source GUI の終了を次 tick に一度だけ確定し、成功遷移の抑制状態を残さない。
     */
    @Test
    void cancelledTargetOpenEndsTheSourceExactlyOnce() {
        PlayerMock player = server().addPlayer();
        Inventory source = managedInventory();
        Inventory target = managedInventory();
        server().getPluginManager().registerEvents(new CancelTargetOpenListener(target), registrationPlugin);

        player.openInventory(source);
        player.openInventory(target);
        server().getScheduler().performOneTick();

        assertEquals(List.of(source), endRecorder.inventories());
        assertEquals(1L, closeSoundCount(player));
    }

    private static Inventory managedInventory() {
        TestGuiHolder holder = new TestGuiHolder();
        Inventory inventory = Bukkit.createInventory(holder, 9);
        holder.setInventory(inventory);
        return inventory;
    }

    private static long closeSoundCount(@NotNull PlayerMock player) {
        return player.getHeardSounds().stream()
            .filter(sound -> Registry.SOUND_EVENT.getKeyOrThrow(Sound.BLOCK_CHEST_CLOSE).getKey().equals(sound.getSound()))
            .count();
    }

    private static final class EndRecorder implements Listener {
        private final List<Inventory> inventories = new ArrayList<>();
        private final List<GuiSessionEndReason> reasons = new ArrayList<>();

        @EventHandler
        public void onGuiSessionEnd(@NotNull GuiSessionEndEvent event) {
            inventories.add(event.getInventory());
            reasons.add(event.getReason());
        }

        private List<Inventory> inventories() {
            return List.copyOf(inventories);
        }

        private List<GuiSessionEndReason> reasons() {
            return List.copyOf(reasons);
        }
    }

    private static final class ContinuationRequestingCloseListener implements Listener {
        private final Player player;
        private final Inventory source;
        private final Inventory target;
        private final Runnable targetOpened;

        private ContinuationRequestingCloseListener(
            @NotNull Player player,
            @NotNull Inventory source,
            @NotNull Inventory target
        ) {
            this(player, source, target, () -> {
            });
        }

        private ContinuationRequestingCloseListener(
            @NotNull Player player,
            @NotNull Inventory source,
            @NotNull Inventory target,
            @NotNull Runnable targetOpened
        ) {
            this.player = player;
            this.source = source;
            this.target = target;
            this.targetOpened = targetOpened;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onInventoryClose(@NotNull InventoryCloseEvent event) {
            if (event.getPlayer() != player || event.getInventory() != source) {
                return;
            }
            Bukkit.getPluginManager().callEvent(new GuiSessionContinuationRequestEvent(
                player,
                source,
                () -> target,
                targetOpened
            ));
        }
    }

    private static final class CancelTargetOpenListener implements Listener {
        private final Inventory target;

        private CancelTargetOpenListener(@NotNull Inventory target) {
            this.target = target;
        }

        @EventHandler
        public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
            if (event.getInventory() == target) {
                event.setCancelled(true);
            }
        }
    }

    private static final class TestGuiHolder implements HotbarShortcutGuiHolder {
        private Inventory inventory;

        private void setInventory(@NotNull Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
