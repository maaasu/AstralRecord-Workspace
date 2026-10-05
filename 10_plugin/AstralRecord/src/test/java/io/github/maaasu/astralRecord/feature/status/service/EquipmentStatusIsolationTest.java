package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EquipmentStatusIsolationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 装備単体のステータス取得
     * 検証契約: 装備単体の取得はプレイヤー合計や外部APIを参照せず、指定したインスタンスの値だけを返す。
     */
    @Test
    void readsInstanceWithoutPlayerTotalOrRepositoryAccess() {
        Fixture fixture = fixture();
        when(fixture.instance.getStatRolls()).thenReturn(List.of(
            new EquipmentStatRoll("roll", StatusType.ATTACK.getId(), "7", "11", 0)));
        var result = fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance, StatusType.ATTACK);
        assertEquals(7, result.getMinValue());
        assertEquals(11, result.getMaxValue());
        verifyNoInteractions(fixture.items);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 装備単体のステータス取得
     * 検証契約: 新しい道具レベルを持たない既存インスタンスへだけマスタ値を補完し、既存ロールへ二重加算しない。
     */
    @Test
    void supplementsMissingToolLevelWithoutDoubleCountingRoll() {
        Fixture fixture = fixture();
        var definition = new ItemEquipmentStat(StatusType.FISHING_LEVEL.getId(), ItemEquipmentStatType.FLAT, 3, 3);
        when(fixture.equipment.getStats()).thenReturn(List.of(definition));
        assertEquals(3, fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance,
            StatusType.FISHING_LEVEL).getMaxValue());
        var roll = new EquipmentStatRoll("roll", StatusType.FISHING_LEVEL.getId(), "5", "5", 0);
        when(fixture.instance.getStatRolls()).thenReturn(List.of(roll));
        when(fixture.equipment.findStatDefinition(roll)).thenReturn(definition);
        assertEquals(5, fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance,
            StatusType.FISHING_LEVEL).getMaxValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 装備単体のステータス取得
     * 検証契約: 不一致のインスタンスと破損・装備条件未達の装備は性能を返さない。
     */
    @Test
    void rejectsMismatchedBrokenAndUnusableEquipment() {
        Fixture fixture = fixture();
        when(fixture.equipment.getStats()).thenReturn(List.of(
            new ItemEquipmentStat(StatusType.FISHING_LEVEL.getId(), ItemEquipmentStatType.FLAT, 3, 3)));
        when(fixture.instance.getItemId()).thenReturn("different");
        assertEquals(0, fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance,
            StatusType.FISHING_LEVEL).getMaxValue());
        when(fixture.instance.getItemId()).thenReturn("tool");
        when(fixture.instance.getDurabilityMax()).thenReturn(10);
        when(fixture.instance.getDurabilityValue()).thenReturn(0);
        assertEquals(0, fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance,
            StatusType.FISHING_LEVEL).getMaxValue());
        when(fixture.instance.getDurabilityValue()).thenReturn(10);
        when(fixture.equipment.getRequiredLevel()).thenReturn(2);
        assertEquals(0, fixture.service.getEquipmentStatus(fixture.player, fixture.model, fixture.instance,
            StatusType.FISHING_LEVEL).getMaxValue());
    }

    private Fixture fixture() {
        AstPlayer player = mock(AstPlayer.class, RETURNS_DEEP_STUBS);
        when(player.getAccount().getLevel()).thenReturn(1);
        ItemService items = mock(ItemService.class);
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        when(model.getId()).thenReturn("tool");
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        when(equipment.getStats()).thenReturn(List.of());
        when(instance.getItemId()).thenReturn("tool");
        when(instance.getStatRolls()).thenReturn(List.of());
        when(instance.getEnchants()).thenReturn(List.of());
        when(instance.getRunes()).thenReturn(List.of());
        return new Fixture(player, items, model, equipment, instance, new StatusService(items, null));
    }

    private record Fixture(AstPlayer player, ItemService items, ItemModel model, ItemEquipment equipment,
                           EquipmentInstance instance, StatusService service) { }
}
