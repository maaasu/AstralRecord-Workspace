package io.github.maaasu.astralRecord.feature.shop.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Stream;

public final class ShopTabCompleter extends AstTabCompleter {
    private final ShopRepository shopRepository = new ShopRepository();

    public ShopTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return shopRepository.findAll().stream()
                .flatMap(shop -> Stream.of(shop.id(), stripColor(shop.name())))
                .distinct()
                .toList();
        }
        return List.of();
    }

    private String stripColor(@NotNull String value) {
        return value.replaceAll("(?i)&[0-9a-fk-or]", "").trim();
    }
}
