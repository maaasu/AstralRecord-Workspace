package io.github.maaasu.astralRecord.feature.trainingdummy.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** /dummy のタブ補完です。 */
public final class TrainingDummyTabCompleter extends AstTabCompleter {
    private final TrainingDummyService service;
    public TrainingDummyTabCompleter(@NotNull TrainingDummyService service) { super(true); this.service = service; }
    @Override protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) return List.of("place", "remove", "reload", "open");
        if (args.length == 2 && ("remove".equalsIgnoreCase(args[0]) || "open".equalsIgnoreCase(args[0]))) return List.copyOf(service.ids());
        return List.of();
    }
}
