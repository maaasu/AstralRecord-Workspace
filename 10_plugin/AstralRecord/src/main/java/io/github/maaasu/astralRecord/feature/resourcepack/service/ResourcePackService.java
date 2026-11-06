package io.github.maaasu.astralRecord.feature.resourcepack.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.resourcepack.repository.ResourcePackRepository;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java Edition 向けリソースパック要求の送信と、クライアント側ステータス通知の処理を担当します。
 */
public class ResourcePackService {

    private static final int SHA1_LENGTH = 40;
    private static final UUID MANAGED_JAVA_PACK_ID = UUID.fromString("8524fc21-aaed-48bd-bb99-0e435cba1315");

    private final Plugin plugin;
    private final ConfigProperties configProperties;
    private final ResourcePackRepository repository = new ResourcePackRepository();
    private final Set<UUID> loadedManagedJavaPacks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> requestedActionRingPanels = new ConcurrentHashMap<>();
    private final Map<UUID, String> requestedGuiThemes = new ConcurrentHashMap<>();
    private final Map<UUID, Player> pendingViewers = new LinkedHashMap<>();
    private boolean manifestLoading;

    /**
     * リソースパックサービスを生成します。
     *
     * @param plugin 非同期取得とメインスレッドでの送信に使用するプラグイン
     * @param configProperties プラグイン設定
     */
    public ResourcePackService(Plugin plugin, ConfigProperties configProperties) {
        this.plugin = plugin;
        this.configProperties = configProperties;
    }

    /**
     * リソースパック要求を送信できる設定か判定します。
     *
     * @return 有効なら true
     */
    public boolean isEnabled() {
        return configProperties.isResourcePackManagedEnabled() || isFixedPackEnabled();
    }

    /**
     * メインスレッドで参加者を受け付け、最新の配布情報を非同期に取得します。
     *
     * @param player 送信対象プレイヤー
     */
    public void applyTo(Player player) {
        if (configProperties.isResourcePackManagedEnabled() && !isBedrock(player.getName())) {
            pendingViewers.put(player.getUniqueId(), player);
            if (!manifestLoading) {
                manifestLoading = true;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::loadManifest);
            }
            return;
        }
        applyFixedPack(player);
    }

    /** 同時に参加したプレイヤーは一度のAPI取得にまとめ、送信だけメインスレッドで行います。 */
    private void loadManifest() {
        ResourcePackRepository.JavaPack pack = null;
        try {
            pack = repository.loadJavaPack();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            Logger.log(LogId.W_5553, exception);
        }
        ResourcePackRepository.JavaPack result = pack;
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> applyManifest(result));
        }
    }

    /**
     * 現在の接続だけにパックを要求し、未配布・取得失敗時は固定URL方式を確認します。
     *
     * @param pack API の配布情報。現在のパックがなければ null
     */
    private void applyManifest(@Nullable ResourcePackRepository.JavaPack pack) {
        manifestLoading = false;
        List<Player> viewers = List.copyOf(pendingViewers.values());
        pendingViewers.clear();
        String url = pack == null ? null : managedPackUrl(pack.sha1());
        for (Player player : viewers) {
            if (plugin.getServer().getPlayer(player.getUniqueId()) != player) {
                continue;
            }
            try {
                if (url != null) {
                    UUID viewer = player.getUniqueId();
                    loadedManagedJavaPacks.remove(viewer);
                    requestedActionRingPanels.remove(viewer);
                    requestedGuiThemes.remove(viewer);
                    if (pack.actionRingPanel() != null) {
                        requestedActionRingPanels.put(viewer, pack.actionRingPanel());
                    }
                    if (pack.guiTheme() != null) {
                        requestedGuiThemes.put(viewer, pack.guiTheme());
                    }
                    player.addResourcePack(MANAGED_JAVA_PACK_ID, url, HexFormat.of().parseHex(pack.sha1()),
                            blankToNull(configProperties.getResourcePackPrompt()), configProperties.isResourcePackForce());
                    Logger.log(LogId.I_5550, player.getName(), url, configProperties.isResourcePackForce());
                } else {
                    applyFixedPack(player);
                }
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_5550, exception, player.getName());
            }
        }
    }

    /**
     * 固定の公開サイトに対するダウンロードURLを組み立てます。API内のURLや認証情報は公開しません。
     *
     * @param sha1 検証済みのJavaパックのハッシュ
     * @return 配布URL。設定不正の場合はnull
     */
    private @Nullable String managedPackUrl(String sha1) {
        try {
            URI base = URI.create(configProperties.getResourcePackPublicBaseUrl().trim());
            if (base.getHost() != null && base.getRawUserInfo() == null && base.getRawQuery() == null
                    && base.getRawFragment() == null
                    && ("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))) {
                return base.resolve("/resource-packs/java/" + sha1 + ".zip").toASCIIString();
            }
        } catch (IllegalArgumentException ignored) {
            // 不正な設定では固定URL方式へフォールバックします。
        }
        Logger.log(LogId.W_5554);
        return null;
    }

    /**
     * 設定で有効にした従来のパックを送信します。
     *
     * @param player 送信対象
     */
    private void applyFixedPack(Player player) {
        if (!isFixedPackEnabled()) {
            return;
        }
        if (shouldSkipBedrock(player)) {
            Logger.log(LogId.I_5551, player.getName());
            return;
        }

        UUID packId = getPackId();
        String prompt = blankToNull(configProperties.getResourcePackPrompt());
        String sha1 = blankToNull(configProperties.getResourcePackSha1());

        if (sha1 != null && !isValidSha1(sha1)) {
            Logger.log(LogId.W_5550, getPackUrl(), sha1);
            sha1 = null;
        }

        player.setResourcePack(
                packId,
                getPackUrl(),
                sha1,
                prompt == null ? null : Component.text(prompt),
                configProperties.isResourcePackForce()
        );

        Logger.log(
                LogId.I_5550,
                player.getName(),
                getPackUrl(),
                configProperties.isResourcePackForce()
        );
    }

    /**
     * 指定 ID がこのサービスで送信したリソースパック ID か判定します。
     *
     * @param packId 判定対象リソースパック ID
     * @return 管理対象なら true
     */
    public boolean isManagedPack(UUID packId) {
        return (configProperties.isResourcePackManagedEnabled() && MANAGED_JAVA_PACK_ID.equals(packId))
                || (isFixedPackEnabled() && getPackId().equals(packId));
    }

    /**
     * リソースパックのクライアント側ステータスを処理します。
     *
     * @param player 通知元プレイヤー
     * @param packId 要求時のパックID
     * @param status リソースパックステータス
     */
    public void handleStatus(Player player, UUID packId, PlayerResourcePackStatusEvent.Status status) {
        if (MANAGED_JAVA_PACK_ID.equals(packId)) {
            switch (status) {
                case SUCCESSFULLY_LOADED -> loadedManagedJavaPacks.add(player.getUniqueId());
                case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED ->
                        loadedManagedJavaPacks.remove(player.getUniqueId());
                default -> { }
            }
        }
        switch (status) {
            case ACCEPTED -> Logger.log(LogId.I_5552, player.getName());
            case DOWNLOADED -> Logger.log(LogId.I_5553, player.getName());
            case SUCCESSFULLY_LOADED -> Logger.log(LogId.I_5554, player.getName());
            case DECLINED -> handleDeclined(player);
            case FAILED_DOWNLOAD -> sendPlayerMessage(player, PlayerMsgId.P_5550);
            case INVALID_URL -> sendPlayerMessage(player, PlayerMsgId.P_5551);
            case FAILED_RELOAD -> sendPlayerMessage(player, PlayerMsgId.P_5552);
            case DISCARDED -> Logger.log(LogId.W_5551, player.getName());
            default -> Logger.log(LogId.D_5550, player.getName(), status);
        }
    }

    /**
     * 管理配布された Java 版パックを適用済みか判定します。パケット送信スレッドからも参照できます。
     *
     * @param playerId 閲覧者のUUID
     * @return 適用成功を通知済みならtrue
     */
    public boolean hasManagedJavaPack(UUID playerId) {
        return loadedManagedJavaPacks.contains(playerId);
    }

    /**
     * 適用済みの管理パックが RPG プラグインと同じスプライト台帳を含むか判定します。
     *
     * @param playerId 閲覧者の UUID
     * @param revision RPG プラグインの台帳の SHA-256
     * @return 台帳が一致するパックを適用済みなら true
     */
    public boolean hasActionRingPanel(UUID playerId, String revision) {
        return hasManagedJavaPack(playerId) && revision.equals(requestedActionRingPanels.get(playerId));
    }

    /**
     * GUIフォント互換版1を含む管理パックの適用が成功しているか確認します。
     * 古い管理パック、固定URL、未適用・拒否・失敗した接続では専用字形を使いません。
     *
     * @param playerId 閲覧者のUUID
     * @return 対応版の適用が成功済みならtrue
     */
    public boolean hasGuiTheme(UUID playerId) {
        return hasManagedJavaPack(playerId) && "1".equals(requestedGuiThemes.get(playerId));
    }

    /**
     * 退出時に、この接続の取得待ちと適用状態を解除します。
     *
     * @param playerId 退出した接続のUUID
     */
    public void forgetViewer(UUID playerId) {
        pendingViewers.remove(playerId);
        loadedManagedJavaPacks.remove(playerId);
        requestedActionRingPanels.remove(playerId);
        requestedGuiThemes.remove(playerId);
    }

    /**
     * 統合版接続かを設定済みのFloodgate名prefixで判定します。
     *
     * @param playerName Bukkit接続名
     * @return 統合版向けに扱う接続ならtrue
     */
    public boolean isBedrock(String playerName) {
        return BedrockPlayerDetector.isBedrock(playerName, configProperties.getResourcePackBedrockNamePrefixes());
    }

    /** @return 固定URLパックが送信可能ならtrue */
    private boolean isFixedPackEnabled() {
        return configProperties.isResourcePackEnabled() && !getPackUrl().isBlank();
    }

    /**
     * リソースパック拒否時の通知とログを処理します。
     *
     * @param player 拒否したプレイヤー
     */
    private void handleDeclined(Player player) {
        sendPlayerMessage(player, PlayerMsgId.P_5553);
        Logger.log(LogId.W_5552, player.getName());
    }

    /**
     * 設定済みリソースパック URL を返します。
     *
     * @return 前後空白を除去した URL
     */
    private String getPackUrl() {
        return configProperties.getResourcePackUrl().trim();
    }

    /**
     * リソースパック URL から固定 UUID を生成します。
     *
     * @return リソースパック ID
     */
    private UUID getPackId() {
        return UUID.nameUUIDFromBytes(getPackUrl().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bedrock プレイヤー向けに Java リソースパック要求をスキップすべきか判定します。
     *
     * @param player 判定対象プレイヤー
     * @return スキップ対象なら true
     */
    private boolean shouldSkipBedrock(Player player) {
        return configProperties.isResourcePackSkipBedrock() && isBedrock(player.getName());
    }

    /**
     * SHA-1 文字列として妥当か判定します。
     *
     * @param value 判定対象文字列
     * @return 40 文字の 16 進文字列なら true
     */
    private static boolean isValidSha1(String value) {
        if (value.length() != SHA1_LENGTH) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 空文字列を null に変換します。
     *
     * @param value 変換対象文字列
     * @return trim 後に空なら null、それ以外は trim 済み文字列
     */
    private static @Nullable String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * プレイヤー向けメッセージ定義を使って通知します。
     *
     * @param player 通知対象プレイヤー
     * @param msgId メッセージ ID
     */
    private static void sendPlayerMessage(Player player, PlayerMsgId msgId) {
        PlayerMessageService.getInstance().send(player, msgId);
    }
}
