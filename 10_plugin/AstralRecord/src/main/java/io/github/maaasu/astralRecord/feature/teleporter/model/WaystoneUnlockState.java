package io.github.maaasu.astralRecord.feature.teleporter.model;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * 選択中アカウント単位のウェイストーン解除状態です。
 */
public record WaystoneUnlockState(@NotNull UUID accountId, @NotNull Set<String> unlockedWaystoneIds) {
    /**
     * 指定ウェイストーンが解除済みかどうかを返します。
     *
     * @param definition 判定対象のウェイストーン定義
     * @return 常時開放または DB 上で解除済みの場合は true
     */
    public boolean isUnlocked(@NotNull WaystoneDefinition definition) {
        return !definition.lockEnabled() || unlockedWaystoneIds.contains(definition.id());
    }

    /**
     * 指定ウェイストーン ID を解除済みにした新しい状態を返します。
     *
     * @param waystoneId 解除済みにするウェイストーン ID
     * @return 追加後の解除状態
     */
    @NotNull
    public WaystoneUnlockState withUnlocked(@NotNull String waystoneId) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(unlockedWaystoneIds);
        ids.add(waystoneId);
        return new WaystoneUnlockState(accountId, Set.copyOf(ids));
    }
}
