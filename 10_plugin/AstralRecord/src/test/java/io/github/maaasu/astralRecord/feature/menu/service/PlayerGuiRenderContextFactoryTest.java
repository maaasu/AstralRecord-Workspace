package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerGuiRenderContextFactoryTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 1. 描画コンテキスト生成
     * 検証契約: player/sessionと画面固有値を描画開始時に1回だけ取得してimmutable contextへ集約する。
     */
    @Test
    void capturesPlayerSessionAndScreenSpecificValuesOnce() {
        var player = server().addPlayer();
        var helmet = new ItemStack(Material.DIAMOND_HELMET);
        var helmetMeta = helmet.getItemMeta();
        helmetMeta.displayName(Component.text("星頭巾"));
        helmet.setItemMeta(helmetMeta);
        player.getInventory().setHelmet(helmet);
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        StatusSnapshot snapshot = StatusSnapshot.empty();

        CurrencyService currencyService = mock(CurrencyService.class);
        StatusService statusService = mock(StatusService.class);
        SkillTreeService skillTreeService = mock(SkillTreeService.class);
        when(currencyService.getGoldAmount(astPlayer.getAccount().getUuid())).thenReturn(456L);
        ItemStack astrald = new ItemStack(Material.DIAMOND);
        var astraldMeta = astrald.getItemMeta();
        astraldMeta.displayName(Component.text("アストラルド"));
        astrald.setItemMeta(astraldMeta);
        when(currencyService.getCurrencyItemStacks(astPlayer.getAccount().getUuid())).thenReturn(List.of(astrald));
        when(currencyService.getCurrencyItemId(astrald)).thenReturn("astrald");
        when(currencyService.getDisplayCurrencyAmount(astPlayer.getAccount().getUuid(), "astrald")).thenReturn(12L);
        when(statusService.getStatus(astPlayer)).thenReturn(snapshot);
        when(skillTreeService.currentClassPointLabel(astPlayer)).thenReturn("CP[冒険者]");
        when(skillTreeService.availableClassPoints(astPlayer)).thenReturn(7);
        when(skillTreeService.availablePassivePoints(astPlayer)).thenReturn(8);

        var factory = new PlayerGuiRenderContextFactory(currencyService, statusService, skillTreeService);
        var context = factory.create(astPlayer);

        assertSame(astPlayer.getAccount(), context.account());
        assertSame(snapshot, context.statusSnapshot());
        assertEquals("CP[冒険者]", context.classPointLabel());
        assertEquals(7, context.availableClassPoints());
        assertEquals(8, context.availablePassivePoints());
        assertEquals(456L, context.goldAmount());
        assertEquals(1, context.currencyBalances().size());
        assertEquals("アストラルド", plain(context.currencyBalances().get(0).displayName()));
        assertEquals(12L, context.currencyBalances().get(0).amount());
        assertEquals(100L, context.returnToBaseGoldCost());
        assertEquals("星頭巾", plain(context.equipment().helmet()));
        assertEquals("なし", plain(context.equipment().chestplate()));

        verify(currencyService).getGoldAmount(astPlayer.getAccount().getUuid());
        verify(currencyService).getCurrencyItemStacks(astPlayer.getAccount().getUuid());
        verify(currencyService).getCurrencyItemId(astrald);
        verify(currencyService).getDisplayCurrencyAmount(astPlayer.getAccount().getUuid(), "astrald");
        verify(statusService).getStatus(astPlayer);
        verify(skillTreeService).currentClassPointLabel(astPlayer);
        verify(skillTreeService).availableClassPoints(astPlayer);
        verify(skillTreeService).availablePassivePoints(astPlayer);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
