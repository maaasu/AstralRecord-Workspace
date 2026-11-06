package io.github.maaasu.astralRecord.feature.resourcepack.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
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
    private final Set<UUID> requestedGameplaySounds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockGameplaySounds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedSunbirdPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadedSunbirdPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedSeleneaPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockSeleneaPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaItemTooltips = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockItemTooltips = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaWeaponAttackProjectiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockWeaponAttackProjectiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaSkillEffects = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockSkillEffects = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ResourcePackRepository.ProfileHud> requestedJavaProfileHud = new ConcurrentHashMap<>();
    private final Map<UUID, String> bedrockActionRingHudRevisions = new ConcurrentHashMap<>();
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
        loadedSunbirdPresentation.remove(player.getUniqueId());
        requestedSunbirdPresentation.remove(player.getUniqueId());
        requestedSeleneaPresentation.remove(player.getUniqueId());
        requestedJavaWeaponAttackProjectiles.remove(player.getUniqueId());
        requestedJavaSkillEffects.remove(player.getUniqueId());
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
                    boolean hadItemTooltips = hasJavaItemTooltips(viewer);
                    loadedManagedJavaPacks.remove(viewer);
                    requestedJavaItemTooltips.remove(viewer);
                    requestedJavaProfileHud.remove(viewer);
                    if (hadItemTooltips) {
                        player.updateInventory();
                    }
                    requestedActionRingPanels.remove(viewer);
                    requestedGuiThemes.remove(viewer);
                    requestedGameplaySounds.remove(viewer);
                    requestedJavaWeaponAttackProjectiles.remove(viewer);
                    requestedJavaSkillEffects.remove(viewer);
                    if (pack.actionRingPanel() != null) {
                        requestedActionRingPanels.put(viewer, pack.actionRingPanel());
                    }
                    if (pack.guiTheme() != null) {
                        requestedGuiThemes.put(viewer, pack.guiTheme());
                    }
                    if (pack.hasGameplaySounds()) {
                        requestedGameplaySounds.add(viewer);
                    }
                    if (pack.itemTooltips()) {
                        requestedJavaItemTooltips.add(viewer);
                    }
                    if (pack.seleneaPresentation()) {
                        requestedSeleneaPresentation.add(viewer);
                    }
                    if (pack.weaponAttackProjectiles()) {
                        requestedJavaWeaponAttackProjectiles.add(viewer);
                    }
                    if (pack.skillEffects()) {
                        requestedJavaSkillEffects.add(viewer);
                    }
                    if (pack.profileHud() != null) {
                        requestedJavaProfileHud.put(viewer, pack.profileHud());
                    }
                    player.addResourcePack(MANAGED_JAVA_PACK_ID, url, HexFormat.of().parseHex(pack.sha1()),
                            blankToNull(configProperties.getResourcePackPrompt()), configProperties.isResourcePackForce());
                    if (pack.sunbirdPresentation()) {
                        requestedSunbirdPresentation.add(viewer);
                    }
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
            UUID viewer = player.getUniqueId();
            boolean hadItemTooltips = hasJavaItemTooltips(viewer);
            switch (status) {
                case SUCCESSFULLY_LOADED -> {
                    loadedManagedJavaPacks.add(viewer);
                    if (requestedSunbirdPresentation.contains(viewer)) {
                        loadedSunbirdPresentation.add(viewer);
                    }
                }
                case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                    loadedManagedJavaPacks.remove(viewer);
                    loadedSunbirdPresentation.remove(viewer);
                    requestedSeleneaPresentation.remove(viewer);
                    requestedJavaWeaponAttackProjectiles.remove(viewer);
                    requestedJavaSkillEffects.remove(viewer);
                }
                default -> { }
            }
            if (hadItemTooltips != hasJavaItemTooltips(viewer)) {
                player.updateInventory();
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
     * 接続が通常攻撃・GUI用の専用音を再生できるか判定します。
     * Java 版はパックの適用成功、統合版は Proxy からの対応パック通知を確認します。
     *
     * @param player 再生対象のオンラインプレイヤー
     * @return 専用音を送信できる場合は true
     */
    public boolean hasGameplaySounds(Player player) {
        UUID viewer = player.getUniqueId();
        return readyBedrockGameplaySounds.contains(viewer)
                || (hasManagedJavaPack(viewer) && requestedGameplaySounds.contains(viewer));
    }

    /**
     * 接続中の閲覧者が通常攻撃の軌跡・着弾テクスチャに対応するか判定します。
     *
     * @param player 閲覧者
     * @return 適用済みの管理 Java パックまたは準備済みの統合版パックが対応する場合は true
     */
    public boolean hasWeaponAttackProjectiles(Player player) {
        UUID viewer = player.getUniqueId();
        var astPlayer = AstPlayerCache.get(player);
        boolean bedrock = astPlayer == null ? isBedrock(player.getName()) : astPlayer.isBedrock();
        if (bedrock) {
            return readyBedrockWeaponAttackProjectiles.contains(viewer);
        }
        return hasManagedJavaPack(viewer) && requestedJavaWeaponAttackProjectiles.contains(viewer);
    }

    /**
     * Proxy が通知した統合版パックの通常攻撃飛翔演出の対応状態を現在の接続へ登録します。
     *
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockWeaponAttackProjectilesReady(Player player) {
        if (player.isOnline()) {
            readyBedrockWeaponAttackProjectiles.add(player.getUniqueId());
        }
    }

    /**
     * 接続のスキル演出の描画方式を返します。統合版判定はプレイヤーの初期化前にも行います。
     * @param player 閲覧者
     * @return 対応版。互換パックが未適用なら NONE
     */
    public SkillEffectsClient skillEffectsClient(Player player) {
        UUID viewer = player.getUniqueId();
        var astPlayer = AstPlayerCache.get(player);
        boolean bedrock = astPlayer == null ? isBedrock(player.getName()) : astPlayer.isBedrock();
        if (bedrock) {
            return readyBedrockSkillEffects.contains(viewer) ? SkillEffectsClient.BEDROCK : SkillEffectsClient.NONE;
        }
        return hasManagedJavaPack(viewer) && requestedJavaSkillEffects.contains(viewer)
                ? SkillEffectsClient.JAVA : SkillEffectsClient.NONE;
    }

    /** スキル演出の閲覧者別描画方式です。 */
    public enum SkillEffectsClient { NONE, JAVA, BEDROCK }

    /**
     * Proxy から対応パックの通知を受けた統合版接続を記録します。
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockSkillEffectsReady(Player player) {
        if (player.isOnline()) {
            readyBedrockSkillEffects.add(player.getUniqueId());
        }
    }

    /**
     * Proxy が対応パックを確認した統合版の接続を、メインスレッドで記録します。
     *
     * @param player 通知が届いた現在のオンラインプレイヤー
     */
    public void markBedrockGameplaySoundsReady(Player player) {
        if (player.isOnline()) {
            readyBedrockGameplaySounds.add(player.getUniqueId());
        }
    }

    /**
     * 対応する管理パックを適用済みの Java 版接続か判定します。パケット送信スレッドから参照できます。
     *
     * @param playerId 閲覧者の UUID
     * @return ツールチップの互換版が一致し、適用に成功した場合は true
     */
    public boolean hasJavaItemTooltips(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaItemTooltips.contains(playerId);
    }

    /**
     * 対応パックの通知を受け取った統合版接続か判定します。パケット送信スレッドから参照できます。
     *
     * @param playerId 閲覧者の UUID
     * @return 接続時のパックがツールチップに対応する場合は true
     */
    public boolean hasBedrockItemTooltips(UUID playerId) {
        return readyBedrockItemTooltips.contains(playerId);
    }

    /**
     * @param playerId 閲覧者 UUID
     * @param revision RPG が要求するアクションリング HUD 互換性識別子
     * @return 現在の統合版接続で互換性識別子が一致する場合は true
     */
    public boolean hasBedrockActionRingHud(UUID playerId, String revision) {
        return revision.equals(bedrockActionRingHudRevisions.get(playerId));
    }

    /**
     * Proxy から確認を受けた接続にアクションリング HUD の利用を許可します。
     *
     * @param player 対象プレイヤー
     * @param revision 接続中に選んだパックの互換性識別子
     */
    public void markBedrockActionRingHudReady(Player player, String revision) {
        if (player.isOnline()) {
            bedrockActionRingHudRevisions.put(player.getUniqueId(), revision);
        }
    }

    /**
     * Proxy が通知した統合版接続をメインスレッドで記録し、初回だけ表示を再送します。
     *
     * @param player 通知が届いた現在のオンラインプレイヤー
     */
    public void markBedrockItemTooltipsReady(Player player) {
        if (player.isOnline() && readyBedrockItemTooltips.add(player.getUniqueId())) {
            player.updateInventory();
        }
    }

    /**
     * 個人情報HUDを含むJava版の管理パックを適用済みか判定します。
     * @param playerId 表示対象のUUID
     * @return 対応パックの適用が成功済みならtrue
     */
    public boolean hasJavaProfileHud(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaProfileHud.containsKey(playerId);
    }

    /**
     * 横長の戦闘ゲージ字形を含む管理パックが適用済みか判定します。
     * @param playerId 表示対象のUUID
     * @return 対応パックの適用が成功済みならtrue
     */
    public boolean hasWideJavaCombatMeter(UUID playerId) {
        ResourcePackRepository.ProfileHud profileHud = requestedJavaProfileHud.get(playerId);
        return profileHud != null && profileHud.wideCombatMeter() && hasManagedJavaPack(playerId);
    }

    /**
     * 読みやすさを調整した字形を含む管理Javaパックを適用済みか判定します。
     * @param playerId 表示対象のUUID
     * @return 読みやすさ調整版の適用が成功済みならtrue
     */
    public boolean hasReadableJavaHudTypeface(UUID playerId) {
        ResourcePackRepository.ProfileHud profileHud = requestedJavaProfileHud.get(playerId);
        return profileHud != null && profileHud.version() >= 3 && hasManagedJavaPack(playerId);
    }

    /**
     * 正式クラス名と状態異常アイコンを収録したJavaパックが適用済みか判定します。
     * @param playerId 表示対象のUUID
     * @return 互換版4の適用が成功済みならtrue
     */
    public boolean hasJavaHudV4(UUID playerId) {
        ResourcePackRepository.ProfileHud profileHud = requestedJavaProfileHud.get(playerId);
        return profileHud != null && profileHud.version() >= 4 && hasManagedJavaPack(playerId);
    }

    /**
     * Sunbird演出の互換版1を含む管理Javaパックを適用済みか確認します。
     *
     * @param playerId 閲覧者のUUID
     * @return 対応版の適用が成功済みならtrue
     */
    public boolean hasSunbirdPresentation(UUID playerId) {
        return loadedSunbirdPresentation.contains(playerId);
    }

    /**
     * セレネアの互換版1を含む管理Javaパックの適用成功を確認します。
     * @param playerId 閲覧者のUUID
     * @return 対応版が適用済みならtrue
     */
    public boolean hasJavaSeleneaPresentation(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedSeleneaPresentation.contains(playerId);
    }

    /**
     * Proxyが確認した統合版パックの対応状態を調べます。
     * @param playerId 閲覧者のUUID
     * @return 現在の接続が対応パックを読み込んだ場合はtrue
     */
    public boolean hasBedrockSeleneaPresentation(UUID playerId) {
        return readyBedrockSeleneaPresentation.contains(playerId);
    }

    /**
     * Proxyから届いた互換版1の通知を現在のオンライン接続に登録します。
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockSeleneaPresentationReady(Player player) {
        if (player.isOnline()) readyBedrockSeleneaPresentation.add(player.getUniqueId());
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
        requestedGameplaySounds.remove(playerId);
        readyBedrockGameplaySounds.remove(playerId);
        loadedSunbirdPresentation.remove(playerId);
        requestedSunbirdPresentation.remove(playerId);
        requestedSeleneaPresentation.remove(playerId);
        readyBedrockSeleneaPresentation.remove(playerId);
        requestedJavaItemTooltips.remove(playerId);
        readyBedrockItemTooltips.remove(playerId);
        requestedJavaWeaponAttackProjectiles.remove(playerId);
        readyBedrockWeaponAttackProjectiles.remove(playerId);
        requestedJavaSkillEffects.remove(playerId);
        readyBedrockSkillEffects.remove(playerId);
        requestedJavaProfileHud.remove(playerId);
        bedrockActionRingHudRevisions.remove(playerId);
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
