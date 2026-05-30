package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /temp コマンドのタブ補完クラスです。
 */
public final class TempTabCompleter extends AstTabCompleter {

    private static final List<String> MODES = List.of("block", "drop");
    private static final List<String> MATERIALS = java.util.Arrays.stream(Material.values())
        .filter(material -> material != Material.AIR)
        .map(Material::name)
        .sorted()
        .toList();

    /**
     * TempTabCompleter を初期化します。
     */
    public TempTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return MATERIALS.stream()
                .filter(material -> material.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return MODES.stream()
                .filter(mode -> mode.startsWith(prefix))
                .toList();
        }
        return List.of();
    }
}
