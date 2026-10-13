package io.github.maaasu.astralRecord.feature.skilltree.service;

/** master公開・停止境界を跨いだ未送信要求と遅延ACKを採用しないための同期世代。 */
final class RuntimeHeartbeatEpoch {
    private final java.util.concurrent.atomic.AtomicLong epoch = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean stopped;

    /** 捕捉する要求とACKへ紐付ける現在の同期世代を返す。 */
    long capture() { return epoch.get(); }
    /** 停止中でなく、捕捉後に公開・停止境界を跨いでいない場合だけtrueを返す。 */
    boolean isCurrent(long captured) { return !stopped && epoch.get() == captured; }
    /** 公開開始時に旧要求と旧ACKを失効させる。 */
    void invalidate() { epoch.incrementAndGet(); }
    /** 停止状態へ遷移し、それ以前の要求とACKを失効させる。 */
    void stop() { stopped = true; invalidate(); }
    /** 停止中の捕捉も失効させ、新しい同期だけを受け付ける。 */
    void start() { invalidate(); stopped = false; }
}
