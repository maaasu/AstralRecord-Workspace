package io.github.maaasu.astralRecord.feature.player.service;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * チャット本文を配信前の表示文字列へ変換する契約です。
 */
@FunctionalInterface
public interface ChatMessageConverter {

    /**
     * チャット本文を非同期で変換します。
     *
     * @param message 正規化済みのチャット本文
     * @return 変換後本文を返す完了可能なFuture。変換不能時も配信可能な本文を返す
     */
    @NotNull CompletableFuture<String> convert(@NotNull String message);
}
