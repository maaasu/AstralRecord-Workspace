package io.github.maaasu.astralRecord.shared.gui.session;

import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class GuiSessionTransitionServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 別 GUI の open が成功した場合、遷移元の close 候補はセッション終了にならない。
     */
    @Test
    void successfulTransitionDoesNotEndTheSourceSession() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);
        Inventory target = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);
        service.registerOpened(playerId, target);

        assertNull(service.finishClose(playerId, closeToken));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 遷移先の open が cancel または失敗して新しいセッションが登録されない場合、遷移元を一度だけ終了する。
     */
    @Test
    void failedTransitionEndsTheSourceSessionExactlyOnce() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);

        assertSame(source, service.finishClose(playerId, closeToken));
        assertNull(service.finishClose(playerId, closeToken));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: close 後の共有継続遷移が予約されている間は、再表示先が開くまで source session を終了しない。
     */
    @Test
    void continuationKeepsTheSourceSessionAliveUntilTheTargetOpens() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);
        Inventory target = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.ContinuationToken continuation = service.beginContinuation(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);

        assertNull(service.finishClose(playerId, closeToken));
        service.registerOpened(playerId, target);

        assertNull(service.finishClose(playerId, closeToken));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 継続遷移の target open が失敗した場合は source session だけを一度終了し、後続の close token は再終了しない。
     */
    @Test
    void failedContinuationEndsTheSourceSessionExactlyOnce() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.ContinuationToken continuation = service.beginContinuation(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);

        assertNull(service.finishClose(playerId, closeToken));
        assertSame(source, service.failContinuation(playerId, continuation));
        assertNull(service.finishClose(playerId, closeToken));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 連続遷移で古い close トークンを後から評価しても、後続 GUI セッションを終了しない。
     */
    @Test
    void consecutiveTransitionsDoNotLetOlderCloseTokensEndTheLatestSession() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory first = mock(Inventory.class);
        Inventory second = mock(Inventory.class);
        Inventory third = mock(Inventory.class);

        service.registerOpened(playerId, first);
        GuiSessionTransitionService.CloseToken firstClose = service.beginClose(playerId, first);
        service.registerOpened(playerId, second);
        GuiSessionTransitionService.CloseToken secondClose = service.beginClose(playerId, second);
        service.registerOpened(playerId, third);

        assertNull(service.finishClose(playerId, firstClose));
        assertNull(service.finishClose(playerId, secondClose));
        GuiSessionTransitionService.CloseToken thirdClose = service.beginClose(playerId, third);
        assertSame(third, service.finishClose(playerId, thirdClose));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 手動 close は現在のセッションを一度だけ終了する。
     */
    @Test
    void manualCloseEndsTheCurrentSessionExactlyOnce() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);

        assertSame(source, service.finishClose(playerId, closeToken));
        assertNull(service.finishClose(playerId, closeToken));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: ログアウトは現在の session を音なし終了対象として一度だけ返し、予約済み close・継続 token による再終了を防ぐ。
     */
    @Test
    void silentLogoutEndsTheCurrentSessionAndConsumesPendingTokens() {
        GuiSessionTransitionService service = new GuiSessionTransitionService();
        UUID playerId = UUID.randomUUID();
        Inventory source = mock(Inventory.class);

        service.registerOpened(playerId, source);
        GuiSessionTransitionService.ContinuationToken continuation = service.beginContinuation(playerId, source);
        GuiSessionTransitionService.CloseToken closeToken = service.beginClose(playerId, source);

        assertSame(source, service.endSilently(playerId));
        assertNull(service.endSilently(playerId));
        assertNull(service.finishClose(playerId, closeToken));
        assertNull(service.failContinuation(playerId, continuation));
    }
}
