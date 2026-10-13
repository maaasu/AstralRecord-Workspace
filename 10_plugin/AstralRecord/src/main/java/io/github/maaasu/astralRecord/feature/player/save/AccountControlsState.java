package io.github.maaasu.astralRecord.feature.player.save;

/** アカウント操作状態の捕捉世代とDB世代を分離し、古いACKで新しい操作を消しません。 */
public final class AccountControlsState {
    private Values values;
    private int version;
    private long revision;
    private long savedRevision;

    /** DBから読んだ操作状態を保存済みとして構築します。
     * @param values 読込済み操作値
     * @param version 専用DB世代
     * @throws IllegalArgumentException DB世代が負の場合
     */
    public AccountControlsState(Values values, int version) {
        if (version < 0) throw new IllegalArgumentException("Invalid controls version");
        this.values = java.util.Objects.requireNonNull(values);
        this.version = version;
    }

    /** メインスレッドで捕捉した操作が変わった場合だけ未保存世代を進めます。
     * @param next 正規化した現在操作値
     */
    public synchronized void update(Values next) {
        if (!values.equals(next)) { values = next; revision++; }
    }

    /** 最新の操作値を返します。Bukkitを参照しません。
     * @return 最新の不変操作値
     */
    public synchronized Values values() { return values; }

    /** 未保存操作を不変スナップショットにします。変更がなければnullです。
     * @return 捕捉世代、保存済みならnull
     */
    public synchronized Capture capture() {
        return revision == savedRevision ? null : new Capture(values, version, revision);
    }

    /** 捕捉した世代だけACK済みにし、通信中の新しい操作を残します。重複ACKは冪等です。
     * @param capture 通信へ渡した捕捉世代
     * @param acknowledgedVersion API確定世代
     * @throws IllegalStateException 期待版+1または現在版と一致しない場合
     */
    public synchronized void acknowledge(Capture capture, int acknowledgedVersion) {
        if ((long) acknowledgedVersion != (long) capture.expectedVersion() + 1)
            throw new IllegalStateException("Invalid controls acknowledgement version");
        if (capture.revision() <= savedRevision && version >= acknowledgedVersion) return;
        if (version != capture.expectedVersion())
            throw new IllegalStateException("Stale controls acknowledgement version");
        version = acknowledgedVersion;
        savedRevision = Math.max(savedRevision, capture.revision());
    }

    /** 正規化済みのslotと視線。XYZやアイテムを保持しません。
     * @param selectedHotbarSlot 選択slot（0～8）
     * @param viewYaw 有限yaw。[-180,180)へ正規化します
     * @param viewPitch 有限pitch（-90～90）
     */
    public record Values(int selectedHotbarSlot, float viewYaw, float viewPitch) {
        public Values {
            if (selectedHotbarSlot < 0 || selectedHotbarSlot > 8 || !Float.isFinite(viewYaw)
                || !Float.isFinite(viewPitch) || viewPitch < -90 || viewPitch > 90)
                throw new IllegalArgumentException("Invalid account controls");
            viewYaw %= 360;
            if (viewYaw < -180) viewYaw += 360;
            if (viewYaw >= 180) viewYaw -= 360;
            if (viewYaw == 0) viewYaw = 0; // negative zeroは同じ視線として扱う。
            if (viewPitch == 0) viewPitch = 0;
        }
    }

    /** 通信中に変更しない、DB期待世代と捕捉ローカル世代です。
     * @param values 捕捉した操作値
     * @param expectedVersion DBの期待版
     * @param revision 捕捉したローカル版
     */
    public record Capture(Values values, int expectedVersion, long revision) { }
}
