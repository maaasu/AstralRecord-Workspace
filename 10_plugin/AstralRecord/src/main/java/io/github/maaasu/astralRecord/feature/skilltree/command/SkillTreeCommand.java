package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * /skilltree コマンドです。
 */
public class SkillTreeCommand extends AstCommand {
    private final SkillTreeService service;

    public SkillTreeCommand(@NotNull SkillTreeService service) {
        super("skilltree", "スキルツリーを開きます。", "/skilltree [back]", true);
        this.service = service;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            handleTeleport(player);
            return;
        }
        if (args.length == 1 && "back".equalsIgnoreCase(args[0])) {
            handleBack(player);
            return;
        }
        sendUsage(player.getBukkit());
    }

    private void handleBack(@NotNull AstPlayer player) {
        service.returnToBase(player.getBukkit()).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                    }
                })
        );
    }

    private void handleTeleport(@NotNull AstPlayer player) {
        if (!service.canTeleportFrom(player.getBukkit().getWorld())) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5829.getId()));
            return;
        }
        service.teleportToSkillTree(player).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                        return;
                    }
                    sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5819.getId()));
                })
        );
    }

}
