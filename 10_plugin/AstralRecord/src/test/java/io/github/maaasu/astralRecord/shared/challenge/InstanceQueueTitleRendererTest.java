package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InstanceQueueTitleRendererTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/26_0-概要.md
     * 章・見出し: # 26_0-概要 > ## 4. 実装構成 > ### インスタンス作成枠（Boss／Dungeon共通）
     * 検証契約: 作成待機列の title は空にし、subtitle だけへ現在の順番と待機人数を表示する。
     */
    @Test
    void rendersQueuePositionInSubtitleOnly() {
        Player player = mock(Player.class);

        InstanceQueueTitleRenderer.show(
                player,
                PlayerMsgId.P_6534,
                new InstanceCreationQueue.QueuePosition(2, 4, false)
        );

        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        assertEquals("", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
        assertEquals(
                "順番: 2 / 待機人数: 4",
                PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle())
        );
    }
}
