package io.github.maaasu.astralRecord.feature.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * account mode が通常プレイ処理を許可する状態かを判定するユーティリティです。
 */
public final class AccountModeGuard {
    private AccountModeGuard() {
        // utility class
    }

    /**
     * AstPlayer が通常プレイ処理を実行できる account mode かを判定します。
     *
     * @param astPlayer 判定対象。null の場合は false
     * @return 通常プレイ処理を実行できる場合は true
     */
    public static boolean isGameplayPlayer(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode().shouldProcessGameplay();
    }

    /**
     * Bukkit Player に対応する AstPlayer が通常プレイ処理を実行できる account mode かを判定します。
     *
     * @param player 判定対象。null の場合は false
     * @return 通常プレイ処理を実行できる場合は true
     */
    public static boolean isGameplayPlayer(@Nullable Player player) {
        if (player == null) {
            return false;
        }
        return isGameplayPlayer(AstPlayerCache.get(player));
    }
}
