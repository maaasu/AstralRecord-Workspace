package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/** インスタンス作成待機中の title／subtitle 表示を共通化します。 */
public final class InstanceQueueTitleRenderer {
    private static final Title.Times QUEUE_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(100L),
            Duration.ofMillis(1_100L),
            Duration.ofMillis(300L)
    );

    private InstanceQueueTitleRenderer() {
    }

    /**
     * 待機列の順番と待機人数を subtitle だけへ表示します。
     *
     * @param player 表示対象プレイヤー
     * @param subtitleId subtitle メッセージ ID。{0} は順番、{1} は待機人数
     * @param position 待機位置
     */
    public static void show(
            @NotNull Player player,
            @NotNull PlayerMsgId subtitleId,
            @NotNull InstanceCreationQueue.QueuePosition position
    ) {
        player.showTitle(Title.title(
                Component.empty(),
                PlayerMsgResource.formatComponent(
                        subtitleId.getId(),
                        position.position(),
                        position.waitingParticipantCount()
                ),
                QUEUE_TITLE_TIMES
        ));
    }
}
