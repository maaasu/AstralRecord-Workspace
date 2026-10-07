package io.github.maaasu.astralRecord.feature.builder.model;

/**
 * WorldEdit自身のセッションから読み取った表示・確認用スナップショット。
 * @param available 提供元Pluginが有効か
 * @param readable 公開APIを読め、別ワールドoverrideがないか
 * @param selection 完成した範囲。未完成ならnull
 * @param clipboard 単一のコピー内容。未コピーまたは複数コピーならnull
 * @param mask 変更先・変更元マスクの参照。両方未設定ならnull
 * @param placement WorldEditの実際の配置基準（ワールドと整数座標）
 * @param transformed FAWEの全体変換が有効か
 * @param multipleClipboards FAWEで複数のコピーを保持しているか
 */
public record WorldEditSnapshot(boolean available, boolean readable, Selection selection,
                                Clipboard clipboard, Object mask, String placement, boolean transformed,
                                boolean multipleClipboards) {
    /** 完成した直方体の範囲。volumeはWorldEditから取得した体積です。 */
    public record Selection(String worldName, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ, long volume, boolean cuboid) { }
    /** コピー内容の参照と変換。確認後のコピー/回転変更の検知にも使用します。 */
    public record Clipboard(Object identity, String transform, long volume,
                            int width, int height, int length, boolean safeTransform) { }
}
