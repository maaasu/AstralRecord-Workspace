package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.Nullable;

/** ボス／ダンジョンの準備中サイドバーへ表示する待機状態です。 */
public enum ChallengeWaitingStatus {
    /** 待機状態を表示しません。 */
    NONE(null),
    /** パーティーメンバーのハブ到着を待っています。 */
    PARTY_MEMBERS_WAITING(PlayerMsgId.P_6713),
    /** インスタンス作成の順番を待っています。 */
    QUEUE_WAITING(PlayerMsgId.P_6714);

    private final PlayerMsgId messageId;

    ChallengeWaitingStatus(@Nullable PlayerMsgId messageId) {
        this.messageId = messageId;
    }

    /**
     * サイドバーへ状態行を表示する状態か返します。
     *
     * @return 表示対象なら {@code true}
     */
    public boolean isVisible() {
        return messageId != null;
    }

    /**
     * 状態表示に使うプレイヤーメッセージ ID を返します。
     *
     * @return 表示メッセージ ID。非表示状態では {@code null}
     */
    public @Nullable PlayerMsgId messageId() {
        return messageId;
    }
}
