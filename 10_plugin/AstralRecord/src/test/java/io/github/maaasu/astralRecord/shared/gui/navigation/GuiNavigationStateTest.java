package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GuiNavigationStateTest {

    @Test
    void returnsToTheImmediatelyPreviousGuiInOrder() {
        GuiNavigationState state = new GuiNavigationState();
        Inventory menu = mock(Inventory.class);
        Inventory playerList = mock(Inventory.class);
        Inventory playerDetail = mock(Inventory.class);

        state.recordOpen(menu, false);
        state.recordOpen(playerList, false);
        state.recordOpen(playerDetail, false);

        assertSame(playerList, state.beginBack());
        assertTrue(state.completeBack(playerList));
        assertSame(menu, state.beginBack());
        assertTrue(state.completeBack(menu));
        assertNull(state.getPreviousGui());
    }

    @Test
    void replacesCurrentGuiWhenTheSameScreenIsRedrawn() {
        GuiNavigationState state = new GuiNavigationState();
        Inventory menu = mock(Inventory.class);
        Inventory firstPage = mock(Inventory.class);
        Inventory nextPage = mock(Inventory.class);

        state.recordOpen(menu, false);
        state.recordOpen(firstPage, false);
        state.recordOpen(nextPage, true);

        assertSame(nextPage, state.getCurrentGui());
        assertSame(menu, state.getPreviousGui());
    }

    @Test
    void clearsCurrentAndPreviousGuisWhenTheSessionEnds() {
        GuiNavigationState state = new GuiNavigationState();
        state.recordOpen(mock(Inventory.class), false);
        state.recordOpen(mock(Inventory.class), false);

        state.clear();

        assertNull(state.getCurrentGui());
        assertNull(state.getPreviousGui());
        assertNull(state.beginBack());
    }
}
