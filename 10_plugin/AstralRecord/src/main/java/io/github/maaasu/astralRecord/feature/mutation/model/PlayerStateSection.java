package io.github.maaasu.astralRecord.feature.mutation.model;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNull;

/**
 * 同一アカウントの所持品と原子的に保存する機能別の不変スナップショットです。
 * @param name API契約のセクション名
 * @param payload 捕捉した完成状態。構築時に複製される
 * @param acknowledge 同じstateロック内で、捕捉世代の保存済みフラグだけを進める処理
 */
public record PlayerStateSection(
    @NotNull String name, @NotNull JsonElement payload,
    @NotNull java.util.function.Consumer<JsonElement> acknowledge
) {
    public PlayerStateSection {
        payload = payload.deepCopy();
    }
}
