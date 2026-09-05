package io.github.maaasu.astralRecord.feature.trade.gui;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** アイテム・送金画面の配置。上5行を送信内容、最下行を操作に使用します。 */
public final class TradeGuiLayout {
    public static final int SIZE = 54;
    public static final int BACK_SLOT = 45;
    public static final int GOLD_SLOT = 48;
    public static final int SEND_SLOT = 50;
    public static final int CLOSE_SLOT = 53;
    public static final List<Integer> OWN_SLOT_LIST = IntStream.range(0, 45).boxed().toList();
    public static final Set<Integer> OWN_SLOTS = Set.copyOf(OWN_SLOT_LIST);

    private TradeGuiLayout() { }
}
