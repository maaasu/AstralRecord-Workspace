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
    P_5325(5325),
    P_5326(5326),
    P_5327(5327),
    P_5328(5328),
    P_5329(5329),
    P_5395(5395),
    P_5396(5396),
    P_5397(5397),
    P_5398(5398),
    P_6810(6810),
    P_6811(6811),
    P_6812(6812),
    P_6813(6813),
    P_6814(6814),
    P_6815(6815),
    P_6816(6816),
    P_6817(6817),
    P_6818(6818),
    P_6819(6819),
    P_6820(6820),
    P_6821(6821),
    P_6822(6822),
    P_6823(6823),
    P_6824(6824),
    P_6825(6825),
    P_6826(6826),
    P_6827(6827),
    P_6828(6828),
    P_6829(6829),
    P_6830(6830),
    P_6831(6831),
    P_6832(6832),
    P_6833(6833),
    P_6834(6834);

    private final String id;

    PlayerSettingMsgId(int number) {
        this.id = "P_" + number;
    }

    public String getId() {
        return id;
    }
}
