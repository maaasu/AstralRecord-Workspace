package io.github.maaasu.astralRecord.feature.dungeon.service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 装備個体に紐づく現在ダンジョン地図をメモリ上だけで管理します。 */
public final class CartographSessionRegistry {
    private final Map<String, Binding> bindingsByEquipment = new HashMap<>();

    /** @return 同じプレイヤー・セッションへ登録済みなら {@code true} */
    public boolean isBound(
            @NotNull String equipmentInstanceId,
            @NotNull UUID playerId,
            @NotNull UUID sessionId
    ) {
        return new Binding(playerId, sessionId).equals(bindingsByEquipment.get(equipmentInstanceId));
    }

    /** 装備個体を現在セッションへ登録します。 */
    public void bind(
            @NotNull String equipmentInstanceId,
            @NotNull UUID playerId,
            @NotNull UUID sessionId
    ) {
        bindingsByEquipment.put(equipmentInstanceId, new Binding(playerId, sessionId));
    }

    /** 指定参加者がセッションから離れたとき、その参加者所有の登録だけを削除します。 */
    public void removeParticipant(@NotNull UUID playerId, @NotNull UUID sessionId) {
        bindingsByEquipment.entrySet().removeIf(entry ->
                entry.getValue().playerId().equals(playerId)
                        && entry.getValue().sessionId().equals(sessionId));
    }

    /** セッション終了時に紐づく登録をすべて削除します。 */
    public void removeSession(@NotNull UUID sessionId) {
        bindingsByEquipment.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }

    /** 全登録を削除します。 */
    public void clear() {
        bindingsByEquipment.clear();
    }

    /** テスト・診断用に装備個体の現在登録を返します。 */
    public @Nullable Binding find(@NotNull String equipmentInstanceId) {
        return bindingsByEquipment.get(equipmentInstanceId);
    }

    /** 指定プレイヤー・セッションへ登録された装備個体 ID を返します。 */
    public @Nullable String findForPlayerSession(
            @NotNull UUID playerId,
            @NotNull UUID sessionId
    ) {
        Binding expected = new Binding(playerId, sessionId);
        return bindingsByEquipment.entrySet().stream()
                .filter(entry -> entry.getValue().equals(expected))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /** 装備個体とセッションの一時関連です。 */
    public record Binding(@NotNull UUID playerId, @NotNull UUID sessionId) {
    }
}
