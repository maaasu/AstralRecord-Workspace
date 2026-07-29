package io.github.maaasu.astralarchitect.ticket;

import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * クリエイティブモード、権限、チケット所有権を一か所で判定します。
 */
public final class TicketAccessPolicy {

    /**
     * プレイヤーがAstralArchitectへアクセスできるか判定します。
     *
     * @param player 対象プレイヤー
     * @return クリエイティブかつ利用権限を持つ場合はtrue
     */
    public boolean canUse(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                && (player.hasPermission("astralarchitect.use")
                || player.hasPermission("astralarchitect.admin"));
    }

    /**
     * 実行者が対象チケットを管理できるか判定します。
     * コンソールは許可されたコマンド側でのみ本判定へ到達する前提です。
     *
     * @param sender 実行者
     * @param ticket チケット
     * @return 管理可能な場合はtrue
     */
    public boolean canManage(CommandSender sender, TicketMetadata ticket) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        if (!(sender instanceof Player player) || !canUse(player)) {
            return false;
        }
        if (player.hasPermission("astralarchitect.admin")) {
            return true;
        }
        return UUID.fromString(ticket.ownerUuid()).equals(player.getUniqueId());
    }

    /**
     * 実行者が管理者操作を行えるか判定します。
     *
     * @param sender 実行者
     * @return コンソール、またはクリエイティブかつ管理権限を持つ場合はtrue
     */
    public boolean isAdmin(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }
        return sender instanceof Player player
                && player.getGameMode() == GameMode.CREATIVE
                && player.hasPermission("astralarchitect.admin");
    }
}
