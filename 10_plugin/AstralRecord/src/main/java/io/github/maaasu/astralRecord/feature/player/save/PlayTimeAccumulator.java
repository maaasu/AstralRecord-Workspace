package io.github.maaasu.astralRecord.feature.player.save;

import java.util.Objects;
import java.util.function.LongSupplier;

/** 通常プレイの経過時間と、snapshot ACK 待ちの秒数を保持するサーバー非依存の状態です。 */
public final class PlayTimeAccumulator {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private final LongSupplier clock;
    private Object session;
    private boolean playing;
    private long sampledAt;
    private long fractionalNanos;
    private long earnedSeconds;
    private long acknowledgedSeconds;

    /**
     * 単調増加時計を使う累計状態を生成します。
     * @param clock nanoTime と同じ差分契約の時計。呼出し間隔は符号付き long の範囲内とします
     */
    public PlayTimeAccumulator(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 確定した新セッションを開始します。旧セッションの未保存秒数と秒未満の端数を引き継ぎます。
     * @param token セッションを参照同一性で区別する非 null の識別子
     * @param normalPlay 通常プレイモードの場合 true
     */
    public synchronized void start(Object token, boolean normalPlay) {
        long now = clock.getAsLong();
        sample(now);
        session = Objects.requireNonNull(token);
        playing = normalPlay;
        sampledAt = now;
    }

    /**
     * 現セッションのモードを確定します。旧モードの最終経過分を先に捕捉します。
     * @param token 対象セッション。旧セッションからの通知は無視します
     * @param normalPlay 通常プレイモードの場合 true
     */
    public synchronized void changeMode(Object token, boolean normalPlay) {
        if (session != token) return;
        sample(clock.getAsLong());
        playing = normalPlay;
    }

    /**
     * 対象セッションを終了し、最終経過分を捕捉します。再接続後に届いた旧通知は無視します。
     * @param token 終了するセッション識別子
     */
    public synchronized void stop(Object token) {
        if (session != token) return;
        sample(clock.getAsLong());
        session = null;
        playing = false;
    }

    /**
     * 定期保存境界で経過分を秒単位の未保存差分へ移します。
     * @return 保存待ちの整数秒がある場合 true
     */
    public synchronized boolean flush() {
        sample(clock.getAsLong());
        return earnedSeconds > acknowledgedSeconds;
    }

    /**
     * 時計を進めず、現在の未保存差分を不変の捕捉値にします。
     * @return 未保存秒数がある場合その捕捉値。ない場合 null
     */
    public synchronized Capture capture() {
        if (earnedSeconds == acknowledgedSeconds) return null;
        return new Capture(earnedSeconds, earnedSeconds - acknowledgedSeconds);
    }

    /**
     * 完全な API ACK が確認された捕捉分だけを保存済みにします。重複 ACK は冪等です。
     * @param captured この状態が生成した、account lane で順に保存する捕捉値
     * @throws IllegalArgumentException 捕捉値がこの状態の累計範囲外の場合
     */
    public synchronized void acknowledge(Capture captured) {
        if (captured.earnedSeconds() < 0L || captured.earnedSeconds() > earnedSeconds
            || captured.deltaSeconds() <= 0L || captured.deltaSeconds() > captured.earnedSeconds()) {
            throw new IllegalArgumentException("Invalid play-time capture");
        }
        acknowledgedSeconds = Math.max(acknowledgedSeconds, captured.earnedSeconds());
    }

    /** 旧 sample からの PLAY 時間だけを加算し、秒未満の端数を次の境界へ持ち越します。 */
    private void sample(long now) {
        if (session != null && playing) {
            long elapsed = now - sampledAt;
            if (elapsed > 0L) {
                long remainder = fractionalNanos + elapsed % NANOS_PER_SECOND;
                long seconds = Math.addExact(elapsed / NANOS_PER_SECOND, remainder / NANOS_PER_SECOND);
                earnedSeconds = Math.addExact(earnedSeconds, seconds);
                fractionalNanos = remainder % NANOS_PER_SECOND;
            }
        }
        sampledAt = now;
    }

    /**
     * 不変の保存境界です。再送では同じ snapshot 内の delta を使用します。
     * @param earnedSeconds 捕捉時点までにこのプロセスで積算した秒数
     * @param deltaSeconds 未保存の捕捉秒数
     */
    public record Capture(long earnedSeconds, long deltaSeconds) { }
}
