package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GuiNavigationStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: GUI履歴をLIFOで直前画面から順に返す。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: 同一screen再描画はhistory追加でなくcurrent entry置換にする。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 4. 共通 GUI navigation session
     * 検証契約: session終了でcurrentとprevious履歴を全消去する。
     */
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
