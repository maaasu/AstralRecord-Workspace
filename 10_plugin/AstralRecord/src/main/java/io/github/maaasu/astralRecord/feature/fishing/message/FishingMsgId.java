package io.github.maaasu.astralRecord.feature.fishing.message;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;

/** 釣りの表示用途を共通メッセージ正本へ対応付けます。 */
public enum FishingMsgId {
    WAITING(PlayerMsgId.P_9700),
    BITE(PlayerMsgId.P_9701),
    BATTLE(PlayerMsgId.P_9702),
    REELING(PlayerMsgId.P_9703),
    LANDED(PlayerMsgId.P_9704),
    RELEASED(PlayerMsgId.P_9705),
    ESCAPED(PlayerMsgId.P_9706),
    DEFEATED(PlayerMsgId.P_9707),
    HIT(PlayerMsgId.P_9708),
    NO_WATER(PlayerMsgId.P_9709),
    HANGING(PlayerMsgId.P_9710);

    private final PlayerMsgId message;

    FishingMsgId(PlayerMsgId message) {
        this.message = message;
    }

    /** @return player.propertiesと対応するメッセージID */
    public PlayerMsgId message() {
        return message;
    }
}
