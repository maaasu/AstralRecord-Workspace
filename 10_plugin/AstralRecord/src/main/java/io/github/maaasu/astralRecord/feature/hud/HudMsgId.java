package io.github.maaasu.astralRecord.feature.hud;

/** HUDのプレイヤー向けメッセージIDです。 */
public enum HudMsgId {
    P_5660,
    P_5661,
    P_5662;

    /** @return player.propertiesの対応キー */
    public String getId() {
        return name();
    }
}
