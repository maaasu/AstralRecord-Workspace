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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerGuiRenderContextFactoryTest extends MockBukkitTestBase {

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
        when(statusService.getStatus(astPlayer)).thenReturn(snapshot);
        when(skillTreeService.availableClassPoints(astPlayer)).thenReturn(7);
        when(skillTreeService.availablePassivePoints(astPlayer)).thenReturn(8);

        var factory = new PlayerGuiRenderContextFactory(currencyService, statusService, skillTreeService);
        var context = factory.create(astPlayer);

        assertSame(astPlayer.getAccount(), context.account());
        assertSame(snapshot, context.statusSnapshot());
        assertEquals(7, context.availableClassPoints());
        assertEquals(8, context.availablePassivePoints());
        assertEquals(456L, context.goldAmount());
        assertEquals(100L, context.returnToBaseGoldCost());
        assertEquals("星頭巾", plain(context.equipment().helmet()));
        assertEquals("なし", plain(context.equipment().chestplate()));

        verify(currencyService).getGoldAmount(astPlayer.getAccount().getUuid());
        verify(statusService).getStatus(astPlayer);
        verify(skillTreeService).availableClassPoints(astPlayer);
        verify(skillTreeService).availablePassivePoints(astPlayer);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
