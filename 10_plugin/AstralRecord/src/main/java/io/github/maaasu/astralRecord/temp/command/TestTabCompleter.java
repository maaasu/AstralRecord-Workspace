package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /test の packet display 検証向けタブ補完です。
 */
public final class TestTabCompleter extends AstTabCompleter {
    private static final List<String> ROOT = List.of("packet");
    private static final List<String> PACKET_SUB = List.of("demo", "item", "text", "line", "clear");
    private static final List<String> MATERIALS = Arrays.stream(Material.values())
            .filter(material -> material != Material.AIR)
            .map(Material::name)
            .sorted()
            .toList();

    /**
     * TestTabCompleter を生成します。
     */
    public TestTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        if (!"packet".equalsIgnoreCase(args[0])) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(PACKET_SUB, args[1]);
        }
        if (args.length == 3 && ("demo".equalsIgnoreCase(args[1]) || "item".equalsIgnoreCase(args[1]))) {
            return filter(MATERIALS, args[2]);
        }
        return List.of();
    }

    private @NotNull List<String> filter(@NotNull List<String> candidates, @NotNull String rawPrefix) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
