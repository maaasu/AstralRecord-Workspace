package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /skilltree の固定タブ補完です。 */
public class SkillTreeTabCompleter extends AstTabCompleter {
    public SkillTreeTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return args.length == 1 ? List.of("back") : List.of();
    }
}
