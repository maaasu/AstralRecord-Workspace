package io.github.maaasu.astralRecord.feature.whitelist.service;

import io.github.maaasu.astralRecord.feature.discord.service.GlobalChatBridge;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigKeys;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * サーバー whitelist の状態、設定保存、接続中プレイヤーの遮断を管理します。
 */
public final class WhitelistService {
    private static final WhitelistService INSTANCE = new WhitelistService();

    private volatile @Nullable GlobalChatBridge globalChatBridge;

    private WhitelistService() {
    }

    /**
     * whitelist サービスの共有インスタンスを取得します。
     *
     * @return whitelist サービス
     */
    public static @NotNull WhitelistService getInstance() {
        return INSTANCE;
    }

    /**
     * whitelist が有効かどうかを返します。
     *
     * @return 有効なら {@code true}
     */
    public boolean isEnabled() {
        return ConfigProperties.getInstance().isPluginWhitelistEnabled();
    }

    /**
     * whitelist 有効時に接続を許可する UUID か判定します。
     *
     * @param playerUuid 判定対象 UUID
     * @return whitelist が無効、または debugUsers / whitelistUsers のいずれかに含まれる場合は {@code true}
     */
    public boolean isAllowed(@Nullable UUID playerUuid) {
        return !isEnabled()
            || ConfigProperties.getInstance().isDebugUser(playerUuid)
            || ConfigProperties.getInstance().isWhitelistUser(playerUuid);
    }

    /**
     * Discord全体チャット中継の参照を設定します。
     * 現在の whitelist 状態も設定直後に中継へ反映します。
     *
     * @param globalChatBridge 全体チャット中継。利用しない場合は {@code null}
     */
    public void setGlobalChatBridge(@Nullable GlobalChatBridge globalChatBridge) {
        this.globalChatBridge = globalChatBridge;
        if (globalChatBridge != null) {
            globalChatBridge.setMaintenanceMode(isEnabled());
        }
    }

    /**
     * whitelist 状態を変更して config.yml へ保存します。
     * このメソッドはサーバーのメインスレッドから呼び出してください。
     * 有効化時は debugUsers / whitelistUsers のいずれにも含まれない接続中プレイヤーを即時にキックします。
     *
     * @param enabled 更新後の状態
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public void setEnabled(boolean enabled) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Whitelist state must be changed on the primary thread");
        }

        ConfigManager configManager = ConfigManager.getInstance();
        configManager.set(ConfigKeys.PLUGIN_WHITELIST_ENABLED, enabled);
        configManager.save();
        ConfigProperties.getInstance().setPluginWhitelistEnabled(enabled);

        GlobalChatBridge bridge = globalChatBridge;
        if (bridge != null) {
            bridge.setMaintenanceMode(enabled);
        }

        if (enabled) {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                if (!isAllowed(player.getUniqueId())) {
                    player.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_7113.getId()));
                }
            }
        }
    }

    /**
     * whitelist ユーザーを config.yml に追加します。
     * このメソッドはサーバーのメインスレッドから呼び出してください。
     *
     * @param playerUuid 追加するプレイヤー UUID
     * @return 未登録の UUID を追加した場合は {@code true}、既に登録済みの場合は {@code false}
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public synchronized boolean addWhitelistUser(@NotNull UUID playerUuid) {
        return updateWhitelistUsers(playerUuid, true);
    }

    /**
     * whitelist ユーザーを config.yml から削除します。
     * このメソッドはサーバーのメインスレッドから呼び出してください。
     *
     * @param playerUuid 削除するプレイヤー UUID
     * @return 登録済みの UUID を削除した場合は {@code true}、未登録の場合は {@code false}
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public synchronized boolean removeWhitelistUser(@NotNull UUID playerUuid) {
        return updateWhitelistUsers(playerUuid, false);
    }

    private boolean updateWhitelistUsers(@NotNull UUID playerUuid, boolean add) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Whitelist users must be changed on the primary thread");
        }

        ConfigProperties configProperties = ConfigProperties.getInstance();
        Set<UUID> users = new java.util.HashSet<>(configProperties.getPluginWhitelistUsers());
        boolean changed = add ? users.add(playerUuid) : users.remove(playerUuid);
        if (!changed) {
            return false;
        }

        ConfigManager configManager = ConfigManager.getInstance();
        configManager.set(
            ConfigKeys.PLUGIN_WHITELIST_USERS,
            users.stream().map(UUID::toString).sorted().toList()
        );
        configManager.save();
        configProperties.setPluginWhitelistUsers(users);
        return true;
    }
}
