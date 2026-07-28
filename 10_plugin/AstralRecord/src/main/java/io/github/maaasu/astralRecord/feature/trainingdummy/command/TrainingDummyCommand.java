package io.github.maaasu.astralRecord.feature.trainingdummy.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** 管理者がカカシ配置を管理する /dummy コマンドです。 */
public final class TrainingDummyCommand extends AstCommand {
    private final TrainingDummyService service;
    private final TrainingDummyGui gui;
    public TrainingDummyCommand(@NotNull TrainingDummyService service, @NotNull TrainingDummyGui gui) {
        super("dummy", "検証用カカシを管理します。", "/dummy <place|remove|reload|open> [id]", true, UserPermission.ADMIN.getValue());
        this.service = service; this.gui = gui;
    }
    @Override protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1) { sendUsage(player.getBukkit()); return; }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "place" -> { if (args.length < 2) { sendUsage(player.getBukkit()); return; } service.place(args[1], player.getBukkit().getLocation()); var definition = service.find(args[1].toLowerCase(java.util.Locale.ROOT)); if (definition != null) gui.open(player.getBukkit(), definition); }
            case "remove" -> { if (args.length < 2) { sendUsage(player.getBukkit()); return; } service.remove(args[1]); }
            case "reload" -> service.loadAll();
            case "open" -> { if (args.length < 2) { sendUsage(player.getBukkit()); return; } var definition = service.find(args[1]); if (definition != null) gui.open(player.getBukkit(), definition); }
            default -> sendUsage(player.getBukkit());
        }
    }
}
