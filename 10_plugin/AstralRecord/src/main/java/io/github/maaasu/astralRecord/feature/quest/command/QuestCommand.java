package io.github.maaasu.astralRecord.feature.quest.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.event.QuestGuiEventHandler;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

public final class QuestCommand extends AstCommand {
    public QuestCommand() {
        super("quest", "クエスト一覧を開きます。", "/quest", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        QuestGuiEventHandler handler = AstralRecord.getInstance().getQuestGuiEventHandler();
        if (handler == null) {
            return;
        }
        handler.openList(player.getBukkit());
    }
}
