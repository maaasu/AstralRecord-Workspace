package io.github.maaasu.astralRecord.feature.storage.service;

import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * ストレージ GUI の公開入口を提供するサービスです。
 */
public final class StorageService {
    private final MenuOpenEventHandler menuOpenEventHandler;

    public StorageService(@NotNull MenuOpenEventHandler menuOpenEventHandler) {
        this.menuOpenEventHandler = menuOpenEventHandler;
    }

    public void open(@NotNull Player player) {
        open(player, 0);
    }

    public void open(@NotNull Player player, int pageIndex) {
        menuOpenEventHandler.openStorage(player, pageIndex);
    }
}
