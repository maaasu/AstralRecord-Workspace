package io.github.maaasu.astralRecord.feature.player;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** AstralRecord が起動した同期的なゲームモード変更を識別します。 */
public final class GameModeChangeGuard {
    private static final ThreadLocal<Set<UUID>> MANAGED_PLAYERS = ThreadLocal.withInitial(HashSet::new);

    private GameModeChangeGuard() {
    }

    /**
     * AstralRecord 内部のゲームモード変更として実行します。
     *
     * @param player 変更対象プレイヤー
     * @param gameMode 設定するゲームモード
     */
    public static void setGameMode(@NotNull Player player, @NotNull GameMode gameMode) {
        Set<UUID> managedPlayers = MANAGED_PLAYERS.get();
        UUID playerId = player.getUniqueId();
        managedPlayers.add(playerId);
        try {
            player.setGameMode(gameMode);
        } finally {
            managedPlayers.remove(playerId);
            if (managedPlayers.isEmpty()) {
                MANAGED_PLAYERS.remove();
            }
        }
    }

    /**
     * 現在処理中のイベントが AstralRecord 内部のゲームモード変更か判定します。
     *
     * @param player 判定対象プレイヤー
     * @return AstralRecord 内部の変更であれば {@code true}
     */
    public static boolean isManagedChange(@NotNull Player player) {
        return MANAGED_PLAYERS.get().contains(player.getUniqueId());
    }
}
