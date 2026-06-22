package io.github.maaasu.astralRecord.feature.textdisplay.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /textdisplay コマンドのタブ補完です。
 */
public final class TextDisplayTabCompleter extends AstTabCompleter {

    private final TextDisplayPlacementService placementService;

    /**
     * TextDisplayTabCompleter を初期化します。
     *
     * @param placementService 固定 TextDisplay 配置サービス
     */
    public TextDisplayTabCompleter(@NotNull TextDisplayPlacementService placementService) {
        super(true);
        this.placementService = placementService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("place", "remove", "list", "reload");
        }
        if (args.length == 2 && "remove".equalsIgnoreCase(args[0])) {
            return placementService.getPlacements().stream()
                    .map(TextDisplayPlacement::id)
                    .toList();
        }
        return List.of();
    }
}
