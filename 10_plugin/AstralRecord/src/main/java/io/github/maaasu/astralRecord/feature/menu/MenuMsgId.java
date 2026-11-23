package io.github.maaasu.astralRecord.feature.menu;

/** メニュー画面の表示に使うメッセージ ID です。 */
public enum MenuMsgId {
    P_5640(5640), P_5641(5641), P_5642(5642), P_5643(5643), P_5644(5644),
    P_5645(5645), P_5646(5646), P_5647(5647), P_5648(5648), P_5649(5649),
    P_5654(5654), P_5655(5655), P_5656(5656), P_5657(5657),
    P_5682(5682), P_5683(5683), P_5684(5684), P_5685(5685), P_5686(5686),
    P_5687(5687), P_5688(5688), P_5689(5689), P_5690(5690);

    private final String id;

    MenuMsgId(int number) {
        id = "P_" + number;
    }

    /** @return player.propertiesの対応キー */
    public String getId() {
        return id;
    }
}
