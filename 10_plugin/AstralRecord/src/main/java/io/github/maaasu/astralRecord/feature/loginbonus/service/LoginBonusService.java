package io.github.maaasu.astralRecord.feature.loginbonus.service;

import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ログインボーナスの日次受け取り状態と GUI 表示を管理します。
 */
public final class LoginBonusService {
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Tokyo");

    private final LoginBonusGui gui;
    private final Map<UUID, LocalDate> receivedDates = new ConcurrentHashMap<>();

    /**
     * ログインボーナスサービスを構築します。
     *
     * @param gui 表示に使用する GUI
     */
    public LoginBonusService(@NotNull LoginBonusGui gui) {
        this.gui = gui;
    }

    /**
     * データロード済みプレイヤーへログインボーナス画面を開きます。
     *
     * @param player 対象プレイヤー
     */
    public void openAfterDataLoaded(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !player.isOnline()) {
            return;
        }

        UUID accountId = astPlayer.getAccount().getUuid();
        LocalDate today = LocalDate.now(DATE_ZONE);
        boolean alreadyReceivedToday = today.equals(receivedDates.get(accountId));
        if (!alreadyReceivedToday) {
            receivedDates.put(accountId, today);
        }
        gui.open(player, alreadyReceivedToday, today);
    }

    /**
     * ログインボーナス GUI を返します。
     *
     * @return ログインボーナス GUI
     */
    public @NotNull LoginBonusGui getGui() {
        return gui;
    }
}
