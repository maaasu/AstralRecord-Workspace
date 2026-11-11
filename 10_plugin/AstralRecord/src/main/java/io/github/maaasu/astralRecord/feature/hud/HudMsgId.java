package io.github.maaasu.astralRecord.feature.hud;

/** HUDのプレイヤー向けメッセージIDです。 */
public enum HudMsgId {
    P_5660,
    P_5661,
    P_5662,
    P_5665,
    P_5666,
    P_5667,
    P_5668,
    P_5669,
    P_5670,
    P_5671,
    P_5672,
    P_5673,
    P_5674,
    P_5675,
    P_5676,
    P_5677,
    P_5678,
    P_5679;

    /** @return player.propertiesの対応キー */
    public String getId() {
        return name();
    }
}
