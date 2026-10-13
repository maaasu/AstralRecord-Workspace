package io.github.maaasu.astralRecord.feature.skilltree.service;

import java.util.ArrayList;
import java.util.List;

/** メインスレッドで捕捉した同期要求を32件まで束ねる。排出済み要求はbufferに残さない。 */
final class RuntimeHeartbeatBatchBuffer<T> {
    private final List<T> items = new ArrayList<>();

    /** 1件を保持し、32件へ到達した場合だけ順序を保ったbatchを排出する。 */
    List<T> offer(T item) {
        items.add(item);
        return items.size() == 32 ? drain() : List.of();
    }

    /** 周期末は送信、停止時はinflightの解除に同じ排出境界を使う。 */
    List<T> drain() {
        List<T> batch = List.copyOf(items);
        items.clear();
        return batch;
    }
}
