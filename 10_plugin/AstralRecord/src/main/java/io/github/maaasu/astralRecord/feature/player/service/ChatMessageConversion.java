package io.github.maaasu.astralRecord.feature.player.service;

import org.jetbrains.annotations.NotNull;

/**
 * チャット本文の変換前・変換後を保持する値です。
 *
 * @param original 送信時に表示する変換前本文
 * @param converted かな漢字変換後の本文
 */
public record ChatMessageConversion(@NotNull String original, @NotNull String converted) {

    /**
     * 変換前後を併記する必要があるかを返します。
     *
     * @return 変換結果が変換前と異なる場合は true
     */
    public boolean hasConvertedText() {
        return !original.equals(converted);
    }

    /**
     * 中継先へ渡すプレーンテキストを返します。
     *
     * @return 変換済みなら {@code 原文[変換後]}、変換不要なら原文
     */
    public @NotNull String asPlainText() {
        return hasConvertedText() ? original + "[" + converted + "]" : original;
    }
}
