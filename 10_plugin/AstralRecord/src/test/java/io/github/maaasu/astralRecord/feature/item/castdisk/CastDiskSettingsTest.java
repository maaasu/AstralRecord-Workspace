package io.github.maaasu.astralRecord.feature.item.castdisk;

import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CastDiskSettingsTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: スキルキャストディスクは他機能のmetadataを保持したまま、アクション枠と武器枠だけを保存して再読込する。
     */
    @Test
    void storesSlotNumbersWithoutDiscardingOtherMetadata() {
        CastDiskSettings expected = new CastDiskSettings(5, 8);

        String updated = CastDiskSettings.write("{\"other\":{\"value\":1}}", expected);

        assertEquals(expected, CastDiskSettings.read(updated));
        assertTrue(CastDiskSettings.read(updated).isComplete());
        assertTrue(JsonParser.parseString(updated).getAsJsonObject().has("other"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: 未設定または範囲外の設定値を持つディスクは発動設定が未完了として扱う。
     */
    @Test
    void treatsMissingAndOutOfRangeSlotsAsIncomplete() {
        assertFalse(CastDiskSettings.read(null).isComplete());
        assertFalse(CastDiskSettings.read("{\"castDisk\":{\"actionSlot\":6,\"weaponHotbarSlot\":9}}").isComplete());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: ドッジキャストディスクは右クリックから5tick後に自動発動し、スニーク解除を入力にしない。
     */
    @Test
    void schedulesDodgeFiveTicksAfterRightClick() {
        assertEquals(5L, CastDiskUseService.DODGE_DELAY_TICKS);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: 同一プレイヤーがドッジキャストディスクを再使用した場合、古い予約は実行せず最新の予約だけを実行する。
     */
    @Test
    void keepsOnlyTheLatestScheduledDodge() {
        DodgeCastDiskReservation reservation = new DodgeCastDiskReservation();
        UUID playerId = UUID.randomUUID();

        long first = reservation.replace(playerId);
        long second = reservation.replace(playerId);

        assertEquals(1L, first);
        assertEquals(2L, second);
        assertFalse(reservation.consumeIfCurrent(playerId, first));
        assertTrue(reservation.consumeIfCurrent(playerId, second));
        assertFalse(reservation.consumeIfCurrent(playerId, second));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: logoutなどで予約を破棄したプレイヤーのドッジcallbackは実行しない。
     */
    @Test
    void cancelsScheduledDodgeWhenPlayerStateIsCleared() {
        DodgeCastDiskReservation reservation = new DodgeCastDiskReservation();
        UUID playerId = UUID.randomUUID();
        long generation = reservation.replace(playerId);

        reservation.clear(playerId);

        assertFalse(reservation.consumeIfCurrent(playerId, generation));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: ドッジキャストディスクの3秒クールダウンはディスク個体ではなくプレイヤー単位で共有する。
     */
    @Test
    void sharesCooldownAcrossAllDisksForOnePlayer() {
        DodgeCastDiskCooldown cooldown = new DodgeCastDiskCooldown();
        UUID playerId = UUID.randomUUID();

        cooldown.start(playerId, 10_000L);

        assertTrue(cooldown.isActive(playerId, 12_999L));
        assertFalse(cooldown.isActive(playerId, 13_000L));
        assertFalse(cooldown.isActive(UUID.randomUUID(), 12_999L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: ドッジキャストディスクは右クリックから5tick後に、右クリック時の始点を使って通常ドッジを一度だけ実行し、成功時だけ耐久と共有クールダウンを確定する。
     */
    @Test
    void executesScheduledDodgeUsingTheRightClickLocation() {
        Fixture fixture = new Fixture();

        fixture.service.handleRightClick(fixture.player);

        assertEquals(CastDiskUseService.DODGE_DELAY_TICKS, fixture.scheduler.delayTicks());
        fixture.scheduler.runNext();

        verify(fixture.dodgeService).tryTriggerCastDiskDodge(fixture.player, fixture.startLocation);
        verify(fixture.itemService).updateEquipmentDurability(eq("disk-instance"), eq(19), anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: 右クリック後に主手を別アイテムへ持ち替えた場合、予約済みドッジは不発となり耐久を消費しない。
     */
    @Test
    void cancelsScheduledDodgeWhenTheDiskIsNoLongerInMainHand() {
        Fixture fixture = new Fixture();
        ItemReference other = new ItemReference("20a00067", "equipment", "other-instance");

        fixture.service.handleRightClick(fixture.player);
        when(fixture.inventoryService.getItemReferenceInHand(fixture.player, EquipmentSlot.HAND)).thenReturn(other);
        fixture.scheduler.runNext();

        verify(fixture.dodgeService, never()).tryTriggerCastDiskDodge(fixture.player, fixture.startLocation);
        verify(fixture.itemService, never()).updateEquipmentDurability(anyString(), anyInt(), anyString());
    }

    private static final class Fixture {
        private final TestTaskScheduler scheduler = new TestTaskScheduler();
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final DodgeService dodgeService = mock(DodgeService.class);
        private final AstPlayer player = mock(AstPlayer.class);
        private final Player bukkitPlayer = mock(Player.class);
        private final Location startLocation = mock(Location.class);
        private final CastDiskUseService service;

        private Fixture() {
            UUID playerId = UUID.randomUUID();
            AccountModel account = mock(AccountModel.class);
            InventoryEntryModel entry = mock(InventoryEntryModel.class);
            ItemReference disk = new ItemReference("20a00068", "equipment", "disk-instance");
            ItemModel diskModel = mock(ItemModel.class);
            ItemEquipment equipment = mock(ItemEquipment.class);
            EquipmentInstance instance = mock(EquipmentInstance.class);
            EquipmentInstance updated = mock(EquipmentInstance.class);
            when(player.getBukkit()).thenReturn(bukkitPlayer);
            when(player.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(playerId);
            when(bukkitPlayer.getUniqueId()).thenReturn(playerId);
            when(bukkitPlayer.getLocation()).thenReturn(startLocation);
            when(inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND)).thenReturn(entry);
            when(inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND)).thenReturn(disk);
            when(itemService.findLoadedById("20a00068")).thenReturn(diskModel);
            when(diskModel.getEquipment()).thenReturn(equipment);
            when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
            when(equipment.getTag()).thenReturn("DODGE_CAST_DISK");
            when(itemService.findEquipmentInstanceById("disk-instance")).thenReturn(instance);
            when(instance.getDurabilityValue()).thenReturn(20);
            when(instance.getDurabilityMax()).thenReturn(200);
            when(instance.getEquipmentInstanceId()).thenReturn("disk-instance");
            when(itemService.updateEquipmentDurability(eq("disk-instance"), eq(19), anyString())).thenReturn(updated);
            when(dodgeService.canBeginCastDiskDodge(player)).thenReturn(true);
            when(dodgeService.tryTriggerCastDiskDodge(player, startLocation)).thenReturn(true);
            service = new CastDiskUseService(
                scheduler,
                ignored -> player,
                inventoryService,
                itemService,
                mock(SkillActionRingService.class),
                dodgeService,
                mock(CastDiskGui.class)
            );
        }
    }

    private static final class TestTaskScheduler implements CastDiskTaskScheduler {
        private final List<Runnable> pending = new ArrayList<>();
        private long delayTicks;

        @Override
        public CastDiskTask schedule(Runnable action, long delayTicks) {
            this.delayTicks = delayTicks;
            pending.add(action);
            return () -> pending.remove(action);
        }

        private long delayTicks() {
            return delayTicks;
        }

        private void runNext() {
            Runnable action = pending.removeFirst();
            action.run();
        }
    }
}
