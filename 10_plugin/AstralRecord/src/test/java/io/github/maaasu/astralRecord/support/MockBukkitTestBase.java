package io.github.maaasu.astralRecord.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * MockBukkit を使うテストの共通起動基盤です。
 */
public abstract class MockBukkitTestBase {

    private ServerMock server;

    @BeforeEach
    void setUpMockBukkit() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDownMockBukkit() {
        MockBukkit.unmock();
    }

    /**
     * 起動済み MockBukkit サーバーを返します。
     *
     * @return 起動済みサーバー
     */
    protected final ServerMock server() {
        return server;
    }
}
