package io.github.maaasu.astralRecord.shared.challenge;

/** Boss と Dungeon が共有する、Mob 生成前の開始カウントダウン状態です。 */
public final class ChallengeStartCountdown {
    /** 共通の開始待機秒数です。 */
    public static final int DEFAULT_SECONDS = 10;

    private int remainingSeconds;
    private boolean started;

    /** 10秒の開始カウントダウンを生成します。 */
    public ChallengeStartCountdown() {
        this(DEFAULT_SECONDS);
    }

    /** @param seconds 表示するカウントダウン秒数 */
    public ChallengeStartCountdown(int seconds) {
        remainingSeconds = Math.max(0, seconds);
    }

    /**
     * 1秒 tick を進めます。1から0へ進む次の tick でだけ START を返します。
     *
     * @return 表示または開始指示
     */
    public Tick advance() {
        if (started) return new Tick(Phase.COMPLETED, 0);
        if (remainingSeconds > 0) return new Tick(Phase.COUNTDOWN, remainingSeconds--);
        started = true;
        return new Tick(Phase.START, 0);
    }

    /** tick の処理種別です。 */
    public enum Phase {
        COUNTDOWN,
        START,
        COMPLETED
    }

    /** @param phase 処理種別 @param remainingSeconds 表示秒数 */
    public record Tick(Phase phase, int remainingSeconds) {
    }
}
