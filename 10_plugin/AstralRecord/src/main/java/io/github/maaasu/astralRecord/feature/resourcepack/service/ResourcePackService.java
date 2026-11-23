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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private final Map<UUID, Set<Integer>> requestedJavaWornHelmetModels = new HashMap<>();
    private final Map<UUID, Set<Integer>> readyJavaWornHelmetModels = new ConcurrentHashMap<>();
    private final Map<UUID, String> requestedActionRingPanels = new ConcurrentHashMap<>();
    private final Map<UUID, String> requestedGuiThemes = new ConcurrentHashMap<>();
    private final Set<UUID> requestedGameplaySounds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockGameplaySounds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedSunbirdPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadedSunbirdPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedSunbirdPresentationV2 = ConcurrentHashMap.newKeySet();
    private final Set<UUID> loadedSunbirdPresentationV2 = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedSeleneaPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedMossZombiePresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockSeleneaPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaArchmagePhoenixPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockArchmagePhoenixPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaNyxeronPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockNyxeronPresentation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaItemTooltips = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockItemTooltips = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaWeaponAttackProjectiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockWeaponAttackProjectiles = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaSkillEffects = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockSkillEffects = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaCombatRewardPlaque = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaChallengeCountdown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockChallengeCountdown = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaDungeonClearSound = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockDungeonClearSound = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaReceptionDisplay = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockCombatRewardPlaque = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockCombatRewardPlaqueTiming = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockCombatRewardPlaqueWidth = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ResourcePackRepository.ProfileHud> requestedJavaProfileHud = new ConcurrentHashMap<>();
    private final Set<UUID> requestedJavaLocationHud = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaSkillCooldownHud = ConcurrentHashMap.newKeySet();
    private final Set<UUID> requestedJavaFishingRodTip = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> requestedJavaQuestHud = new ConcurrentHashMap<>();
    private final Set<UUID> requestedJavaActionRingCooldownHud = ConcurrentHashMap.newKeySet();
    private final Set<UUID> readyBedrockQuestHud = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> bedrockActionRingHudRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Player> pendingViewers = new LinkedHashMap<>();
    private Consumer<Player> refreshWornHelmetView = player -> {};
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
        requestedSunbirdPresentationV2.remove(player.getUniqueId());
        loadedSunbirdPresentationV2.remove(player.getUniqueId());
        requestedSeleneaPresentation.remove(player.getUniqueId());
        requestedMossZombiePresentation.remove(player.getUniqueId());
        requestedJavaArchmagePhoenixPresentation.remove(player.getUniqueId());
        requestedJavaNyxeronPresentation.remove(player.getUniqueId());
        requestedJavaWeaponAttackProjectiles.remove(player.getUniqueId());
        requestedJavaSkillEffects.remove(player.getUniqueId());
        requestedJavaCombatRewardPlaque.remove(player.getUniqueId());
        requestedJavaReceptionDisplay.remove(player.getUniqueId());
        requestedJavaChallengeCountdown.remove(player.getUniqueId());
        requestedJavaDungeonClearSound.remove(player.getUniqueId());
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
                    requestedJavaWornHelmetModels.remove(viewer);
                    boolean hadWornHelmetModels = readyJavaWornHelmetModels.remove(viewer) != null;
                    requestedJavaItemTooltips.remove(viewer);
                    requestedJavaProfileHud.remove(viewer);
                    requestedJavaLocationHud.remove(viewer);
                    requestedJavaSkillCooldownHud.remove(viewer);
                    requestedJavaFishingRodTip.remove(viewer);
                    requestedJavaQuestHud.remove(viewer);
                    requestedJavaActionRingCooldownHud.remove(viewer);
                    if (hadWornHelmetModels) {
                        refreshWornHelmetView.accept(player);
                    } else if (hadItemTooltips) {
                        player.updateInventory();
                    }
                    requestedActionRingPanels.remove(viewer);
                    requestedGuiThemes.remove(viewer);
                    requestedGameplaySounds.remove(viewer);
                    requestedJavaWeaponAttackProjectiles.remove(viewer);
                    requestedJavaSkillEffects.remove(viewer);
                    if (pack.locationHud()) requestedJavaLocationHud.add(viewer);
                    if (pack.skillCooldownHud()) requestedJavaSkillCooldownHud.add(viewer);
                    if (pack.fishingRodTip()) requestedJavaFishingRodTip.add(viewer);
                    if (pack.questHudVersion() >= 2) requestedJavaQuestHud.put(viewer, pack.questHudVersion());
                    if (pack.actionRingCooldownHud()) requestedJavaActionRingCooldownHud.add(viewer);
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
                    if (pack.mossZombiePresentation()) requestedMossZombiePresentation.add(viewer);
                    if (pack.seleneaPresentation()) {
                        requestedSeleneaPresentation.add(viewer);
                    }
                    if (pack.archmagePhoenixPresentation()) {
                        requestedJavaArchmagePhoenixPresentation.add(viewer);
                    }
                    if (pack.nyxeronPresentation()) {
                        requestedJavaNyxeronPresentation.add(viewer);
                    }
                    if (pack.weaponAttackProjectiles()) {
                        requestedJavaWeaponAttackProjectiles.add(viewer);
                    }
                    if (pack.skillEffects()) {
                        requestedJavaSkillEffects.add(viewer);
                    }
                    if (pack.combatRewardPlaque()) {
                        requestedJavaCombatRewardPlaque.add(viewer);
                    }
                    if (pack.challengeCountdown()) requestedJavaChallengeCountdown.add(viewer);
                    if (pack.dungeonClearSound()) requestedJavaDungeonClearSound.add(viewer);
                    if (pack.receptionDisplay()) {
                        requestedJavaReceptionDisplay.add(viewer);
                    }
                    if (pack.profileHud() != null) {
                        requestedJavaProfileHud.put(viewer, pack.profileHud());
                    }
                    if (pack.sunbirdPresentation()) requestedSunbirdPresentation.add(viewer);
                    if (pack.sunbirdPresentationV2()) requestedSunbirdPresentationV2.add(viewer);
                    player.addResourcePack(MANAGED_JAVA_PACK_ID, url, HexFormat.of().parseHex(pack.sha1()),
                            blankToNull(configProperties.getResourcePackPrompt()), configProperties.isResourcePackForce());
                    if (!pack.wornHelmetModels().isEmpty()) {
                        requestedJavaWornHelmetModels.put(viewer, pack.wornHelmetModels());
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
            Set<Integer> previousHelmetModels = wornHelmetModels(viewer);
            switch (status) {
                case SUCCESSFULLY_LOADED -> {
                    loadedManagedJavaPacks.add(viewer);
                    Set<Integer> helmetModels = requestedJavaWornHelmetModels.get(viewer);
                    if (helmetModels != null) {
                        readyJavaWornHelmetModels.put(viewer, helmetModels);
                    }
                    if (requestedSunbirdPresentation.contains(viewer)) {
                        loadedSunbirdPresentation.add(viewer);
                    }
                    if (requestedSunbirdPresentationV2.contains(viewer)) {
                        loadedSunbirdPresentationV2.add(viewer);
                    }
                }
                case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                    loadedManagedJavaPacks.remove(viewer);
                    requestedJavaWornHelmetModels.remove(viewer);
                    readyJavaWornHelmetModels.remove(viewer);
                    loadedSunbirdPresentation.remove(viewer);
                    loadedSunbirdPresentationV2.remove(viewer);
                    requestedSunbirdPresentation.remove(viewer);
                    requestedSunbirdPresentationV2.remove(viewer);
                    requestedSeleneaPresentation.remove(viewer);
                    requestedMossZombiePresentation.remove(viewer);
                    requestedJavaArchmagePhoenixPresentation.remove(viewer);
                    requestedJavaNyxeronPresentation.remove(viewer);
                    requestedJavaWeaponAttackProjectiles.remove(viewer);
                    requestedJavaSkillEffects.remove(viewer);
                    requestedJavaCombatRewardPlaque.remove(viewer);
                    requestedJavaReceptionDisplay.remove(viewer);
                    requestedJavaChallengeCountdown.remove(viewer);
                    requestedJavaDungeonClearSound.remove(viewer);
                }
                default -> { }
            }
            if (!previousHelmetModels.equals(wornHelmetModels(viewer))) {
                refreshWornHelmetView.accept(player);
            } else if (hadItemTooltips != hasJavaItemTooltips(viewer)) {
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
     * 着用ヘルメット用モデルの利用可否が変わったときの装備再送を登録します。
     * 登録と呼び出しは Bukkit メインスレッドで行います。
     *
     * @param refresh 本人と追跡中プレイヤーの装備表示を再同期する処理
     */
    public void onWornHelmetModelsChanged(Consumer<Player> refresh) {
        refreshWornHelmetView = refresh;
    }

    /**
     * 適用済みの管理パックに含まれる Java 版の着用ヘルメット用モデルIDを返します。
     * パケット送信スレッドから参照できます。
     *
     * @param playerId 閲覧者のUUID
     * @return 着用モデルの不変集合。未適用・非対応なら空集合
     */
    public Set<Integer> wornHelmetModels(UUID playerId) {
        return readyJavaWornHelmetModels.getOrDefault(playerId, Set.of());
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
     * @param playerId 現在の閲覧者UUID
     * @return 対応管理パックの適用成功を受けたJava接続ならtrue
     */
    public boolean hasJavaQuestHud(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaQuestHud.containsKey(playerId);
    }

    /** ガイドとクエストを描けるJava版パックの適用を確認します。 */
    public boolean hasJavaGuideHud(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaQuestHud.getOrDefault(playerId, 0) == 3;
    }

    /** @param playerId 閲覧者UUID @return リングCTを描画できる管理パックの適用成功後のみtrue */
    public boolean hasJavaActionRingCooldownHud(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaActionRingCooldownHud.contains(playerId);
    }

    /**
     * @param playerId 対象接続UUID
     * @return 32行のCT一覧に対応する互換版5の管理パック適用済みならtrue
     */
    public boolean hasJavaSkillCooldownHud(UUID playerId) {
        return hasJavaLocationHud(playerId) && requestedJavaSkillCooldownHud.contains(playerId);
    }

    /**
     * @param playerId 表示対象の接続UUID
     * @return 現在地HUDを含む管理配布パックの適用に成功した場合true
     */
    public boolean hasJavaLocationHud(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaLocationHud.contains(playerId);
    }

    /**
     * @param playerId Java 版の閲覧者
     * @return 適用済みパックが一人称の新しい釣り竿位置に対応する場合はtrue
     */
    public boolean hasJavaFishingRodTip(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaFishingRodTip.contains(playerId);
    }

    /** @param playerId 閲覧者UUID @return 対応パックを登録しログインした統合版接続ならtrue */
    public boolean hasBedrockQuestHud(UUID playerId) {
        return readyBedrockQuestHud.contains(playerId);
    }

    /** @param player Proxyが現在の接続の対応パックを確認したオンライン閲覧者 */
    public void markBedrockQuestHudReady(Player player) {
        if (player.isOnline()) readyBedrockQuestHud.add(player.getUniqueId());
    }

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
     * Minecraft調の書体とバフアイコンを含むJava版パックの適用を確認します。
     * @param playerId 表示対象のUUID
     * @return 対応パックの適用が成功済みならtrue
     */
    public boolean hasJavaHudV5(UUID playerId) {
        ResourcePackRepository.ProfileHud profileHud = requestedJavaProfileHud.get(playerId);
        return profileHud != null && profileHud.version() >= 5 && hasManagedJavaPack(playerId);
    }

    /**
     * 小さい正式クラス名の字形を含むJava版パックの適用を確認します。
     * @param playerId 表示対象のUUID
     * @return 横長HUD用の対応パックの適用が成功済みならtrue
     */
    public boolean hasJavaHudV6(UUID playerId) {
        ResourcePackRepository.ProfileHud profileHud = requestedJavaProfileHud.get(playerId);
        return profileHud != null && profileHud.version() >= 6 && hasManagedJavaPack(playerId);
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

    /** 分割モデルと連続エフェクトを含む管理 Java パックの適用成功を確認します。 */
    public boolean hasSunbirdPresentationV2(UUID playerId) {
        return loadedSunbirdPresentationV2.contains(playerId);
    }

    /**
     * セレネアの互換版1を含む管理Javaパックの適用成功を確認します。
     * @param playerId 閲覧者のUUID
     * @return 対応版が適用済みならtrue
     */
    public boolean hasJavaSeleneaPresentation(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedSeleneaPresentation.contains(playerId);
    }

    /** 苔むしたゾンビのモデルを含む管理パックの読み込み成功を確認します。 */
    public boolean hasJavaMossZombiePresentation(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedMossZombiePresentation.contains(playerId);
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
     * @param playerId 閲覧者のUUID
     * @return 不死鳥の本体モデルを含む管理Javaパックの適用に成功した場合はtrue
     */
    public boolean hasJavaArchmagePhoenixPresentation(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaArchmagePhoenixPresentation.contains(playerId);
    }

    /**
     * @param playerId 閲覧者のUUID
     * @return 現在の統合版接続で不死鳥の本体モデルを描画できる場合はtrue
     */
    public boolean hasBedrockArchmagePhoenixPresentation(UUID playerId) {
        return readyBedrockArchmagePhoenixPresentation.contains(playerId);
    }

    /**
     * Proxyの互換版1の通知をオンライン接続に登録します。
     * @param player 通知対象の閲覧者
     */
    public void markBedrockArchmagePhoenixPresentationReady(Player player) {
        if (player.isOnline()) readyBedrockArchmagePhoenixPresentation.add(player.getUniqueId());
    }

    /**
     * @param playerId 閲覧者のUUID
     * @return 天球心臓ニクセロンの本体と技を含む管理Javaパックの適用に成功した場合はtrue
     */
    public boolean hasJavaNyxeronPresentation(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaNyxeronPresentation.contains(playerId);
    }

    /**
     * @param playerId 閲覧者のUUID
     * @return 現在の統合版接続でニクセロンの本体と技を描画できる場合はtrue
     */
    public boolean hasBedrockNyxeronPresentation(UUID playerId) {
        return readyBedrockNyxeronPresentation.contains(playerId);
    }

    /**
     * Proxyの互換版1の通知をオンライン接続に登録します。
     * @param player 通知対象の閲覧者
     */
    public void markBedrockNyxeronPresentationReady(Player player) {
        if (player.isOnline()) readyBedrockNyxeronPresentation.add(player.getUniqueId());
    }

    /**
     * 戦闘報酬プレートの互換版1を含む管理Javaパックの適用成功を確認します。
     *
     * @param playerId 閲覧者のUUID
     * @return 対応版が適用済みならtrue
     */
    public boolean hasJavaCombatRewardPlaque(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaCombatRewardPlaque.contains(playerId);
    }

    /** @param playerId 閲覧者UUID @return 適用済みJavaパックが挑戦演出v1に対応する場合true */
    public boolean hasJavaChallengeCountdown(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaChallengeCountdown.contains(playerId);
    }

    /** @param playerId 閲覧者UUID @return 現在の統合版接続が挑戦演出v1に対応する場合true */
    public boolean hasBedrockChallengeCountdown(UUID playerId) {
        return readyBedrockChallengeCountdown.contains(playerId);
    }

    /** @param player Proxyが確認した現在のオンライン接続 */
    public void markBedrockChallengeCountdownReady(Player player) {
        if (player.isOnline()) readyBedrockChallengeCountdown.add(player.getUniqueId());
    }

    /**
     * 対応する管理パックを適用した接続だけがダンジョンクリア音を使えます。
     * @param player 再生対象のプレイヤー
     * @return 専用音を含むパックの適用を確認済みなら true
     */
    public boolean hasDungeonClearSound(Player player) {
        UUID viewer = player.getUniqueId();
        return readyBedrockDungeonClearSound.contains(viewer)
            || (hasManagedJavaPack(viewer) && requestedJavaDungeonClearSound.contains(viewer));
    }

    /**
     * Proxy が検証した Bedrock パックのダンジョンクリア音対応を記録します。
     * @param player 対応通知を受信したオンラインプレイヤー
     */
    public void markBedrockDungeonClearSoundReady(Player player) {
        if (player.isOnline()) readyBedrockDungeonClearSound.add(player.getUniqueId());
    }

    /**
     * 受付モデル対応の管理Javaパックが現在の接続で適用済みか確認します。
     * @param playerId 閲覧者のUUID
     * @return 受付モデル対応を宣言したパックの適用が成功済みならtrue
     */
    public boolean hasJavaReceptionDisplay(UUID playerId) {
        return hasManagedJavaPack(playerId) && requestedJavaReceptionDisplay.contains(playerId);
    }

    /**
     * Proxyが確認した統合版パックの戦闘報酬プレート対応状態を調べます。
     *
     * @param playerId 閲覧者のUUID
     * @return 現在の接続が対応パックを読み込んだ場合はtrue
     */
    public boolean hasBedrockCombatRewardPlaque(UUID playerId) {
        return readyBedrockCombatRewardPlaque.contains(playerId);
    }

    /**
     * Proxyから届いた戦闘報酬プレートの対応通知を現在の接続に登録します。
     *
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockCombatRewardPlaqueReady(Player player) {
        if (player.isOnline()) {
            readyBedrockCombatRewardPlaque.add(player.getUniqueId());
        }
    }

    /**
     * @param playerId 閲覧者のUUID
     * @return 現在の統合版接続が表示寿命を指定できる場合はtrue
     */
    public boolean hasBedrockCombatRewardPlaqueTiming(UUID playerId) {
        return readyBedrockCombatRewardPlaqueTiming.contains(playerId);
    }

    /**
     * 表示寿命に対応したパックを通知された接続に記録します。
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockCombatRewardPlaqueTimingReady(Player player) {
        if (player.isOnline()) {
            readyBedrockCombatRewardPlaqueTiming.add(player.getUniqueId());
            readyBedrockCombatRewardPlaque.add(player.getUniqueId());
        }
    }

    /**
     * @param playerId 閲覧者のUUID
     * @return 現在の統合版接続が横幅を指定できる場合はtrue
     */
    public boolean hasBedrockCombatRewardPlaqueWidth(UUID playerId) {
        return readyBedrockCombatRewardPlaqueWidth.contains(playerId);
    }

    /**
     * 可変幅のパックに対応した接続として、従来の描画・時間への対応も記録します。
     * @param player 通知の対象プレイヤー
     */
    public void markBedrockCombatRewardPlaqueWidthReady(Player player) {
        if (player.isOnline()) {
            markBedrockCombatRewardPlaqueTimingReady(player);
            readyBedrockCombatRewardPlaqueWidth.add(player.getUniqueId());
        }
    }

    /**
     * 退出時に、この接続の取得待ちと適用状態を解除します。
     *
     * @param playerId 退出した接続のUUID
     */
    public void forgetViewer(UUID playerId) {
        pendingViewers.remove(playerId);
        loadedManagedJavaPacks.remove(playerId);
        requestedJavaWornHelmetModels.remove(playerId);
        readyJavaWornHelmetModels.remove(playerId);
        requestedActionRingPanels.remove(playerId);
        requestedGuiThemes.remove(playerId);
        requestedGameplaySounds.remove(playerId);
        readyBedrockGameplaySounds.remove(playerId);
        readyBedrockChallengeCountdown.remove(playerId);
        readyBedrockDungeonClearSound.remove(playerId);
        loadedSunbirdPresentation.remove(playerId);
        requestedSunbirdPresentation.remove(playerId);
        requestedSunbirdPresentationV2.remove(playerId);
        loadedSunbirdPresentationV2.remove(playerId);
        requestedSeleneaPresentation.remove(playerId);
        requestedMossZombiePresentation.remove(playerId);
        readyBedrockSeleneaPresentation.remove(playerId);
        requestedJavaArchmagePhoenixPresentation.remove(playerId);
        readyBedrockArchmagePhoenixPresentation.remove(playerId);
        requestedJavaNyxeronPresentation.remove(playerId);
        readyBedrockNyxeronPresentation.remove(playerId);
        requestedJavaItemTooltips.remove(playerId);
        readyBedrockItemTooltips.remove(playerId);
        requestedJavaWeaponAttackProjectiles.remove(playerId);
        readyBedrockWeaponAttackProjectiles.remove(playerId);
        requestedJavaSkillEffects.remove(playerId);
        readyBedrockSkillEffects.remove(playerId);
        requestedJavaCombatRewardPlaque.remove(playerId);
        requestedJavaReceptionDisplay.remove(playerId);
        requestedJavaChallengeCountdown.remove(playerId);
        requestedJavaDungeonClearSound.remove(playerId);
        readyBedrockCombatRewardPlaque.remove(playerId);
        readyBedrockCombatRewardPlaqueTiming.remove(playerId);
        readyBedrockCombatRewardPlaqueWidth.remove(playerId);
        requestedJavaProfileHud.remove(playerId);
        requestedJavaLocationHud.remove(playerId);
        requestedJavaSkillCooldownHud.remove(playerId);
        requestedJavaFishingRodTip.remove(playerId);
        requestedJavaQuestHud.remove(playerId);
        requestedJavaActionRingCooldownHud.remove(playerId);
        readyBedrockQuestHud.remove(playerId);
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
