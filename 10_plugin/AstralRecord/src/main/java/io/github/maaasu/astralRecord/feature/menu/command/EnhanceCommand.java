package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentEnhancementService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;

/**
 * 装備強化 GUI を開くコマンドです。
 */
public final class EnhanceCommand extends AstCommand {

    /**
     * `/enhance` コマンドを生成します。
     */
    public EnhanceCommand() {
        super("enhance", "Open equipment enhancement GUI.", "/enhance", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        if (args.length > 0) {
            sendUsage(player.getBukkit());
            return;
        }

        EquipmentEnhancementService enhancementService = AstralRecord.getInstance().getEquipmentEnhancementService();
        if (enhancementService == null) {
            GuiSound.DENY.play(player.getBukkit());
            return;
        }

        GuiSound.OPEN.play(player.getBukkit());
        enhancementService.open(player.getBukkit());
    }
}
