package io.github.maaasu.astralarchitect.ticket;

import java.util.Objects;

/**
 * チケットが所有する包含端点形式の直方体範囲です。
 *
 * @param min 最小座標
 * @param max 最大座標
 */
public record TicketBounds(BlockPosition min, BlockPosition max) {

    /**
     * 最小座標が最大座標を超えないことを検証します。
     */
    public TicketBounds {
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw new IllegalArgumentException("Minimum position must not exceed maximum position");
        }
    }

    /**
     * 範囲のX方向サイズを返します。
     *
     * @return 1以上のX方向サイズ
     */
    public int width() {
        return Math.addExact(Math.subtractExact(max.x(), min.x()), 1);
    }

    /**
     * 範囲のY方向サイズを返します。
     *
     * @return 1以上のY方向サイズ
     */
    public int height() {
        return Math.addExact(Math.subtractExact(max.y(), min.y()), 1);
    }

    /**
     * 範囲のZ方向サイズを返します。
     *
     * @return 1以上のZ方向サイズ
     */
    public int length() {
        return Math.addExact(Math.subtractExact(max.z(), min.z()), 1);
    }

    /**
     * 範囲の総ブロック数をオーバーフローせず計算します。
     *
     * @return 総ブロック数
     * @throws ArithmeticException longの範囲を超える場合
     */
    public long volume() {
        return Math.multiplyExact(Math.multiplyExact((long) width(), height()), length());
    }

    /**
     * 指定座標が範囲内か判定します。
     *
     * @param position 判定する座標
     * @return 包含する場合はtrue
     */
    public boolean contains(BlockPosition position) {
        return position.x() >= min.x() && position.x() <= max.x()
                && position.y() >= min.y() && position.y() <= max.y()
                && position.z() >= min.z() && position.z() <= max.z();
    }
}
