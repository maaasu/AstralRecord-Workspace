package io.github.maaasu.astralrecordgeyser;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 独立TIPの表示成功を接続個体に結び付け、正常な消去まで保持する。 */
final class LatchedTipDelivery {
    private final Set<Object> displayed = Collections.newSetFromMap(new IdentityHashMap<>());

    synchronized boolean send(Object connection, boolean clear, boolean verified, BooleanSupplier sender) {
        // 未確認接続へ新しい専用ペイロードを送らない。既に送った同じ接続の消去だけを例外にする。
        if (!verified && !(clear && displayed.contains(connection))) return false;
        if (!sender.getAsBoolean()) return false;
        if (clear) displayed.remove(connection);
        else displayed.add(connection);
        return true;
    }

    synchronized void forget(Object connection) {
        displayed.remove(connection);
    }

    synchronized void clear() {
        displayed.clear();
    }
}
