package io.github.maaasu.astralRecord.feature.teleporter.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /teleporter のタブ補完です。
 */
public final class TeleporterTabCompleter extends AstTabCompleter {
    private static final List<String> ITEM_MATERIAL_NAMES = Arrays.stream(Material.values())
            .filter(Material::isItem)
            .filter(material -> !material.isAir())
            .map(Material::name)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();

    private final TeleporterService teleporterService;

    public TeleporterTabCompleter(@NotNull TeleporterService teleporterService) {
        super(true);
        this.teleporterService = teleporterService;
    }

    /**
     * 管理権限を持つプレイヤー向けにサブコマンドとアイコン Material を補完します。
     *
     * @param player 補完要求元
     * @param args 入力中の引数
     * @return 現在位置での補完候補
     */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!player.hasPermissionLevel(99)) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("set", "remove", "list", "reload");
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) {
            return List.of("true", "false");
        }
        if (args.length == 4 && "set".equalsIgnoreCase(args[0])) {
            return List.of("0", "100", "500", "1000");
        }
        if (args.length == 5 && "set".equalsIgnoreCase(args[0])) {
            return ITEM_MATERIAL_NAMES;
        }
        if (args.length == 2 && "remove".equalsIgnoreCase(args[0])) {
            return teleporterService.getAll().stream()
                    .map(WaystoneDefinition::id)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }
}
