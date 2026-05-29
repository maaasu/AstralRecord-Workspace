package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * TextDisplay アニメーションの 1 フレーム定義です。
 *
 * @param text          このフレームで表示する文字列
 * @param offset        アンカー位置へ加算するオフセット
 * @param durationTicks フレーム継続 tick 数
 */
public record DisplayAnimationFrame(
        @NotNull String text,
        @NotNull Vector offset,
        long durationTicks
) {

    /**
     * フレーム定義を構築します。
     *
     * @param text          表示文字列
     * @param offset        アンカー加算オフセット
     * @param durationTicks 継続 tick 数
     */
    public DisplayAnimationFrame {
        if (durationTicks <= 0L) {
            throw new IllegalArgumentException("durationTicks must be positive.");
        }
        offset = offset.clone();
    }
}
