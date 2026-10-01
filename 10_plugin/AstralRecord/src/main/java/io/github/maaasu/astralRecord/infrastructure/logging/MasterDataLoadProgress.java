package io.github.maaasu.astralRecord.infrastructure.logging;

/**
 * 一括ロードごとの取得件数を INFO ログへ集約します。
 * 同じインスタンスは一つのロード処理のスレッド内だけで使用します。
 * 途中経過は処理件数が10%の境界を越え、前回出力から1秒以上経過した場合に出力します。
 * 表示する割合は取得成功件数 / 取得対象件数であり、キャッシュ公開の完了率ではありません。
 */
public final class MasterDataLoadProgress {
    private static final long MIN_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private final LogId logId;
    private final int total;
    private int processed;
    private int loaded;
    private int lastBucket;
    private long lastLogNanos;

    /**
     * 総件数が確定した一括ロードの集計を開始します。
     *
     * @param logId 成功件数・総件数・整数の割合の順に三つの引数を取る INFO ログID
     * @param total 取得対象件数。組み込み定義など対象外のデータは含めません
     * @throws IllegalArgumentException 総件数が負の場合
     */
    public MasterDataLoadProgress(LogId logId, int total) {
        if (total < 0) throw new IllegalArgumentException("total must not be negative");
        this.logId = logId;
        this.total = total;
        lastLogNanos = System.nanoTime();
        if (total > 0) logProgress();
    }

    /**
     * 対象一件の処理結果を記録します。失敗・スキップも一件として呼び出します。
     *
     * @param successful 取得に成功した場合は true。失敗分は成功件数へ加算しません
     */
    public void record(boolean successful) {
        processed++;
        if (successful) loaded++;
        int bucket = total == 0 ? 10 : (int) ((long) processed * 10 / total);
        long now = System.nanoTime();
        if (processed < total && bucket > lastBucket
                && now - lastLogNanos >= MIN_LOG_INTERVAL_NANOS) {
            logProgress();
            lastBucket = bucket;
            lastLogNanos = now;
        }
    }

    /**
     * 一括処理が正常に終了した時点の取得件数を必ず出力します。
     * 失敗・スキップがあれば100%にはなりません。対象0件の場合は0/0件・100%です。
     * 例外で処理を中断した場合は呼び出さず、呼び出し元の既存エラーログを使用します。
     */
    public void finish() {
        logProgress();
    }

    /** 成功件数を基に整数の割合を計算して出力します。 */
    private void logProgress() {
        int percent = total == 0 ? 100 : (int) ((long) loaded * 100 / total);
        Logger.log(logId, loaded, total, percent);
    }
}
