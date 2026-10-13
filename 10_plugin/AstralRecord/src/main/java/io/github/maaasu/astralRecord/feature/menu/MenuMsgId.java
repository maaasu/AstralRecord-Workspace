package io.github.maaasu.astralRecord.feature.menu;

/** メインメニューの取得済み状態表示に使うメッセージIDです。 */
public enum MenuMsgId {
    P_5640(5640), P_5641(5641), P_5642(5642), P_5643(5643), P_5644(5644),
    P_5645(5645), P_5646(5646), P_5647(5647), P_5648(5648), P_5649(5649);

    private final String id;

    MenuMsgId(int number) {
        id = "P_" + number;
    }

    /** @return player.propertiesの対応キー */
    public String getId() {
        return id;
    }
}
