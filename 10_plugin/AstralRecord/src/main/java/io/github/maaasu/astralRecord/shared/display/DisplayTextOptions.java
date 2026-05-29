package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * TextDisplay の見た目と補間設定をまとめた不変オプションです。
 *
 * @param text                  初期表示文字列
 * @param offset                アンカー位置へ加算する標準オフセット
 * @param billboard             billboard モード
 * @param lineWidth             行幅
 * @param viewRange             描画距離
 * @param textOpacity           文字不透明度
 * @param seeThrough            透過越し表示
 * @param shadowed              影表示
 * @param defaultBackground     既定背景を使うか
 * @param backgroundColor       背景色。null の場合は Paper 既定値
 * @param interpolationDuration 補間時間
 * @param teleportDuration      移動補間時間
 * @param persistent            永続エンティティとするか
 * @param brightnessBlock       ブロック光量。null で未指定
 * @param brightnessSky         空光量。null で未指定
 */
public record DisplayTextOptions(
        @NotNull String text,
        @NotNull Vector offset,
        @NotNull Display.Billboard billboard,
        int lineWidth,
        float viewRange,
        byte textOpacity,
        boolean seeThrough,
        boolean shadowed,
        boolean defaultBackground,
        @Nullable Color backgroundColor,
        int interpolationDuration,
        int teleportDuration,
        boolean persistent,
        @Nullable Integer brightnessBlock,
        @Nullable Integer brightnessSky
) {

    private static final int DEFAULT_LINE_WIDTH = 240;
    private static final float DEFAULT_VIEW_RANGE = 48.0F;
    private static final byte FULL_OPACITY = (byte) 0xFF;

    /**
     * オプションを構築します。
     */
    public DisplayTextOptions {
        offset = offset.clone();
        lineWidth = Math.max(1, lineWidth);
        viewRange = Math.max(1.0F, viewRange);
        interpolationDuration = clamp(interpolationDuration, 0, 59);
        teleportDuration = clamp(teleportDuration, 0, 59);
        if ((brightnessBlock == null) != (brightnessSky == null)) {
            throw new IllegalArgumentException("brightnessBlock and brightnessSky must both be null or both be set.");
        }
    }

    /**
     * 汎用デフォルトオプションを生成します。
     *
     * @param text 表示文字列
     * @return デフォルトオプション
     */
    public static @NotNull DisplayTextOptions defaults(@NotNull String text) {
        return new DisplayTextOptions(
                text,
                new Vector(),
                Display.Billboard.CENTER,
                DEFAULT_LINE_WIDTH,
                DEFAULT_VIEW_RANGE,
                FULL_OPACITY,
                false,
                false,
                false,
                null,
                2,
                2,
                false,
                null,
                null
        );
    }

    /**
     * 頭上表示向けの既定オプションを生成します。
     *
     * @param text 表示文字列
     * @return 頭上表示向けオプション
     */
    public static @NotNull DisplayTextOptions overhead(@NotNull String text) {
        return defaults(text).withSeeThrough(true).withShadowed(true).withViewRange(64.0F);
    }

    /**
     * 浮遊ダメージ表示向けの既定オプションを生成します。
     *
     * @param text 表示文字列
     * @return ダメージ表示向けオプション
     */
    public static @NotNull DisplayTextOptions damage(@NotNull String text) {
        return defaults(text)
                .withSeeThrough(true)
                .withShadowed(true)
                .withViewRange(48.0F)
                .withInterpolationDuration(1)
                .withTeleportDuration(1);
    }

    /**
     * 固定ボード表示向けの既定オプションを生成します。
     *
     * @param text 表示文字列
     * @return ボード表示向けオプション
     */
    public static @NotNull DisplayTextOptions board(@NotNull String text) {
        return defaults(text)
                .withBillboard(Display.Billboard.FIXED)
                .withLineWidth(320)
                .withViewRange(96.0F)
                .withShadowed(true);
    }

    /**
     * 文字列を差し替えた新オプションを返します。
     *
     * @param newText 新しい文字列
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withText(@NotNull String newText) {
        return new DisplayTextOptions(
                newText, offset, billboard, lineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * 標準オフセットを差し替えた新オプションを返します。
     *
     * @param newOffset 新しいオフセット
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withOffset(@NotNull Vector newOffset) {
        return new DisplayTextOptions(
                text, newOffset, billboard, lineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * billboard を差し替えた新オプションを返します。
     *
     * @param newBillboard 新しい billboard
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withBillboard(@NotNull Display.Billboard newBillboard) {
        return new DisplayTextOptions(
                text, offset, newBillboard, lineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * 行幅を差し替えた新オプションを返します。
     *
     * @param newLineWidth 新しい行幅
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withLineWidth(int newLineWidth) {
        return new DisplayTextOptions(
                text, offset, billboard, newLineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * viewRange を差し替えた新オプションを返します。
     *
     * @param newViewRange 新しい viewRange
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withViewRange(float newViewRange) {
        return new DisplayTextOptions(
                text, offset, billboard, lineWidth, newViewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * seeThrough を差し替えた新オプションを返します。
     *
     * @param newSeeThrough 新しい seeThrough
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withSeeThrough(boolean newSeeThrough) {
        return new DisplayTextOptions(
                text, offset, billboard, lineWidth, viewRange, textOpacity, newSeeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * shadowed を差し替えた新オプションを返します。
     *
     * @param newShadowed 新しい shadowed
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withShadowed(boolean newShadowed) {
        return new DisplayTextOptions(
                text, offset, billboard, lineWidth, viewRange, textOpacity, seeThrough, newShadowed,
                defaultBackground, backgroundColor, interpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * interpolationDuration を差し替えた新オプションを返します。
     *
     * @param newInterpolationDuration 新しい補間時間
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withInterpolationDuration(int newInterpolationDuration) {
        return new DisplayTextOptions(
                text, offset, billboard, lineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, newInterpolationDuration, teleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    /**
     * teleportDuration を差し替えた新オプションを返します。
     *
     * @param newTeleportDuration 新しい移動補間時間
     * @return 差し替え後オプション
     */
    public @NotNull DisplayTextOptions withTeleportDuration(int newTeleportDuration) {
        return new DisplayTextOptions(
                text, offset, billboard, lineWidth, viewRange, textOpacity, seeThrough, shadowed,
                defaultBackground, backgroundColor, interpolationDuration, newTeleportDuration, persistent,
                brightnessBlock, brightnessSky
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
