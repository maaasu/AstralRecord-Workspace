package io.github.maaasu.astralRecord.feature.playersetting;

/**
 * player-setting feature のプレイヤー向けメッセージ ID を定義します。
 */
public enum PlayerSettingMsgId {
    P_5320(5320),
    P_5321(5321),
    P_5322(5322),
    P_5323(5323),
    P_5324(5324),
    P_5325(5325);

    private final String id;

    PlayerSettingMsgId(int number) {
        this.id = "P_" + number;
    }

    public String getId() {
        return id;
    }
}
