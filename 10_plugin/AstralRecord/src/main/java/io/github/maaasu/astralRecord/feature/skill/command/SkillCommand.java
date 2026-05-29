package io.github.maaasu.astralRecord.feature.skill.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * スキル関連 GUI を開くコマンドです。
 */
public final class SkillCommand extends AstCommand {
    public SkillCommand() {
        super("skill", "Open skill bind GUI.", "/skill [gui]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("gui")) {
            var handler = AstralRecord.getInstance().getSkillBindGuiEventHandler();
            if (handler != null) {
                handler.open(player.getBukkit());
            }
            return;
        }
        sendUsage(player.getBukkit());
    }
}
