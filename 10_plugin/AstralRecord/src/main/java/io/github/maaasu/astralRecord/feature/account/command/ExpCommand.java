package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.rebirth.service.RebirthService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** ADMIN向けにプレイヤーEXPだけを付与するコマンドです。 */
public final class ExpCommand extends AstCommand {
    public ExpCommand() { super("exp", "プレイヤーEXPを付与します。", "/exp <value> [player]", false, UserPermission.ADMIN.getValue()); }
    @Override protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!admin(sender)) { sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId())); return; }
        if (args.length < 1 || args.length > 2) { sendUsage(sender); return; }
        int value;
        try { value = Integer.parseInt(args[0]); } catch (NumberFormatException e) { sendUsage(sender); return; }
        if (value <= 0) { sendUsage(sender); return; }
        AstPlayer target = target(sender, args.length == 2 ? args[1] : null);
        if (target == null) return;
        RebirthService service = AstralRecord.getInstance().getRebirthService();
        if (service == null) { sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5853.getId())); return; }
        var result = service.grantExperience(target, value);
        target.setAccount(result.updatedAccount());
        var tree = AstralRecord.getInstance().getSkillTreeService(); if (tree != null) tree.refreshProgressDerivedState(target);
        AstralRecord.getInstance().getStatusService().refreshStatus(target);
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7191.getId(), value, target.getBukkit().getName()));
        if (sender != target.getBukkit()) PlayerMessageService.getInstance().send(target, PlayerMsgId.P_7191, value, target.getBukkit().getName());
    }
    private boolean admin(CommandSender sender) { if (!(sender instanceof Player p)) return true; AstPlayer a=AstPlayerCache.get(p); return a != null && a.hasAdminPermission(); }
    private AstPlayer target(CommandSender sender, String name) {
        Player p = name == null ? (sender instanceof Player self ? self : null) : Bukkit.getPlayerExact(name);
        AstPlayer a = p == null ? null : AstPlayerCache.get(p);
        if (a == null) sendError(sender, PlayerMsgResource.format(name == null ? PlayerMsgId.P_5305.getId() : PlayerMsgId.P_5814.getId(), name));
        return a;
    }
}
