package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.hud.view.PlayerHudView;
import io.github.maaasu.astralRecord.feature.hud.view.ProfileHudText;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.combat.service.CombatDpsTrackerService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.paladin.PaladinGuardRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.ArchmagePhoenixRuntimeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public class PlayerHudService {
    private static final long DPS_SAMPLE_WINDOW_MS = 10_000L;
    private final StatusService statusService;
    private final PlayerClassService playerClassService;
    private final AccountService accountService;
    private final CurrencyService currencyService;
    private final PlayerSettingService playerSettingService;
    private final ConditionService conditionService;
    private final BossChallengeService bossChallengeService;
    private final WorldService worldService;
    private final SkillTreeService skillTreeService;
    private final PlayerHudView playerHudView;
    private final ResourcePackService resourcePackService;
    private CombatDpsTrackerService combatDpsTrackerService;
    private PaladinGuardRuntimeService paladinGuardRuntimeService;
    private ArchmagePhoenixRuntimeService archmagePhoenixRuntimeService;
    private DungeonService dungeonService;
    private Function<AstPlayer, Component> swordSpiritRenderer = ignored -> Component.empty();
    private final Map<UUID, BukkitTask> actionBarOverrideTasks = new HashMap<>();
    private final Map<UUID, Function<AstPlayer, Component>> primaryActionBarRenderers = new HashMap<>();
    private AstralRecord plugin;
    private BukkitTask task;
    private boolean proxyOwnsTabHeader;
    private final Map<UUID, UUID> sidebarAccounts = new HashMap<>();
    private Predicate<Player> sidebarInputBlocked = ignored -> false;

    /**
     * ロード・セッション遷移・スキル選択など、効果ページ入力を停止する判定を設定します。
     * @param blocked メインスレッド上で入力を拒否する場合trueを返す判定
     */
    public void setSidebarInputBlocked(@NotNull Predicate<Player> blocked) {
        sidebarInputBlocked = blocked;
    }

    /**
     * HUD サービスを構築します。
     *
     * @param statusService       ステータスサービス
     * @param playerClassService  職業サービス（サイドバーの職業名・レベル・経験値進捗表示に使用）
     * @param accountService      アカウント経験値サービス
     * @param currencyService     所持Goldの参照サービス
     * @param playerSettingService プレイヤー設定サービス
     * @param conditionService 状態異常サービス
     * @param bossChallengeService ボス挑戦サービス
     * @param worldService ワールド表示名・種別の解決サービス
     * @param skillTreeService スキルツリーワールド判定と CP / PP 残高の解決サービス
     * @param resourcePackService Java版のパック適用状態の参照先
     */
    public PlayerHudService(
        StatusService statusService,
        PlayerClassService playerClassService,
        AccountService accountService,
        CurrencyService currencyService,
        PlayerSettingService playerSettingService,
        ConditionService conditionService,
        BossChallengeService bossChallengeService,
        WorldService worldService,
        SkillTreeService skillTreeService,
        ResourcePackService resourcePackService
    ) {
        this.statusService = statusService;
        this.playerClassService = playerClassService;
        this.accountService = accountService;
        this.currencyService = currencyService;
        this.playerSettingService = playerSettingService;
        this.conditionService = conditionService;
        this.bossChallengeService = bossChallengeService;
        this.worldService = worldService;
        this.skillTreeService = skillTreeService;
        this.resourcePackService = resourcePackService;
        this.playerHudView = new PlayerHudView(resourcePackService);
    }

    public void setCombatDpsTrackerService(@NotNull CombatDpsTrackerService combatDpsTrackerService) {
        this.combatDpsTrackerService = combatDpsTrackerService;
    }

    /** アクションバーへガード値を表示する専用リソースサービスを設定します。 */
    public void setPaladinGuardRuntimeService(
            @NotNull PaladinGuardRuntimeService paladinGuardRuntimeService
    ) {
        this.paladinGuardRuntimeService = paladinGuardRuntimeService;
    }

    /** @param runtime アクションバーへ不死鳥リソースを表示する状態サービス */
    public void setArchmagePhoenixRuntimeService(@NotNull ArchmagePhoenixRuntimeService runtime) {
        this.archmagePhoenixRuntimeService = runtime;
    }

    /**
     * 通常のHP/MP表示へ付加する剣気表示を設定します。
     * @param renderer 表示対象から剣気の現在数・上限と反撃の追加威力を返す処理
     */
    public void setSwordSpiritRenderer(@NotNull Function<AstPlayer, Component> renderer) {
        this.swordSpiritRenderer = renderer;
    }

    /** @param dungeonService ダンジョン Sidebar 情報の参照先 */
    public void setDungeonService(@NotNull DungeonService dungeonService) {
        this.dungeonService = dungeonService;
    }

    public void start(AstralRecord plugin) {
        if (task != null) {
            return;
        }
        this.plugin = plugin;
        proxyOwnsTabHeader = plugin.getConfig().getBoolean("network.enabled", true);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 10L, 10L);
    }

    /** 周期更新と一時ActionBarを停止し、個人情報HUDと効果ページを解除します。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BukkitTask actionBarOverrideTask : actionBarOverrideTasks.values()) {
            actionBarOverrideTask.cancel();
        }
        actionBarOverrideTasks.clear();
        sidebarAccounts.clear();
        playerHudView.clearBuffPages();
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearProfileHud(player);
        }
        proxyOwnsTabHeader = false;
        plugin = null;
    }

    /**
     * ドッジ受付バーの表示を開始します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void showDodgeWindow(AstPlayer astPlayer) {
        if (plugin == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }

        showActionBarOverride(astPlayer, () -> renderDodgeWindow(astPlayer), () -> isDodgeWindowActive(astPlayer));
    }

    /**
     * 壁張り付きバーの表示を開始します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void showWallClingWindow(AstPlayer astPlayer) {
        if (plugin == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }

        showActionBarOverride(astPlayer, () -> renderWallClingWindow(astPlayer), () -> isWallClingActive(astPlayer));
    }

    /**
     * ドッジ受付バーを解除し、通常のリソース表示へ戻します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void restoreStatusActionBar(AstPlayer astPlayer) {
        cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
        astPlayer.setSneakDodgeWindowExpiresAtMs(0L);
        astPlayer.setWallClingExpiresAtMs(0L);
        refreshActionBar(astPlayer);
    }

    /**
     * プレイヤー固有の ActionBar 描画関数を設定します。
     *
     * @param playerId プレイヤー UUID
     * @param renderer 通常 HUD の代わりに使う描画関数
     */
    public void setPrimaryActionBarRenderer(@NotNull UUID playerId, @NotNull Function<AstPlayer, Component> renderer) {
        primaryActionBarRenderers.put(playerId, renderer);
    }

    /**
     * 他の処理がActionBarを表示していない場合だけ描画関数を登録します。
     *
     * @param playerId 対象プレイヤー UUID
     * @param renderer 登録する描画関数
     */
    public void setPrimaryActionBarRendererIfAbsent(
            @NotNull UUID playerId,
            @NotNull Function<AstPlayer, Component> renderer
    ) {
        primaryActionBarRenderers.putIfAbsent(playerId, renderer);
    }

    /**
     * プレイヤー固有の ActionBar 描画関数を解除します。
     *
     * @param playerId プレイヤー UUID
     */
    public void clearPrimaryActionBarRenderer(@NotNull UUID playerId) {
        primaryActionBarRenderers.remove(playerId);
    }

    /**
     * 指定した描画関数が現在も登録されている場合だけ解除します。
     *
     * @param playerId 対象プレイヤー UUID
     * @param renderer 解除する描画関数
     */
    public void clearPrimaryActionBarRendererIfSame(
            @NotNull UUID playerId,
            @NotNull Function<AstPlayer, Component> renderer
    ) {
        primaryActionBarRenderers.remove(playerId, renderer);
    }

    /**
     * 現在の状態に応じた ActionBar を即時再描画します。
     *
     * @param astPlayer 対象プレイヤー
     */
    public void refreshActionBar(@NotNull AstPlayer astPlayer) {
        if (isWallClingActive(astPlayer)) {
            renderWallClingWindow(astPlayer);
            return;
        }
        if (isDodgeWindowActive(astPlayer)) {
            renderDodgeWindow(astPlayer);
            return;
        }
        renderPrimaryActionBar(astPlayer, statusService.getStatus(astPlayer));
    }

    private void updateAll() {
        double mspt = Bukkit.getServer().getAverageTickTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                clearProfileHud(player);
                continue;
            }

            StatusSnapshot snapshot = statusService.getStatus(astPlayer);
            int playerLevel = astPlayer.getAccount().getLevel();
            double experienceProgress = accountService.experienceProgress(astPlayer.getAccount());
            if (astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                renderSidebar(astPlayer, mspt);
                if (isWallClingActive(astPlayer)) {
                    renderWallClingWindow(astPlayer);
                } else if (isDodgeWindowActive(astPlayer)) {
                    renderDodgeWindow(astPlayer);
                } else {
                renderPrimaryActionBar(astPlayer, snapshot);
                }
            } else {
                cancelActionBarOverrideTask(player.getUniqueId());
                playerHudView.removeSidebar(player);
                clearProfileHud(player);
                clearSidebarBuffPage(player.getUniqueId());
            }
            playerHudView.renderBars(
                player,
                snapshot,
                playerLevel,
                experienceProgress,
                statusService.getShieldDisplayCapacity(astPlayer)
            );
            if (!proxyOwnsTabHeader) {
                playerClassService.updatePlayerListName(astPlayer);
                playerHudView.renderTabList(
                    player,
                    mspt,
                    playerSettingService.isPerformanceInfoDisplayEnabled(astPlayer.getUser().getUuid())
                );
            }
        }
    }

    /**
     * 個人情報、現在地、設定、挑戦情報、選択中の効果ページをサイドバーへ描画します。
     * @param astPlayer 表示対象
     * @param mspt 現在のMSPT
     */
    private void renderSidebar(@NotNull AstPlayer astPlayer, double mspt) {
        Player player = astPlayer.getBukkit();
        prepareSidebarBuffPage(astPlayer);
        int playerLevel = astPlayer.getAccount().getLevel();
        String className = playerClassService.getDisplayName(astPlayer.getClassId());
        double classExperienceProgress = playerClassService.classExperienceProgress(astPlayer);
        boolean showPerformanceInfo = playerSettingService.isPerformanceInfoDisplayEnabled(
            astPlayer.getUser().getUuid()
        );
        boolean showBuffInfo = playerSettingService.isBuffSidebarDisplayEnabled(
            astPlayer.getUser().getUuid()
        );
        BossChallengeSidebarInfo bossInfo = bossChallengeService.findSidebarInfo(player.getUniqueId());
        DungeonSidebarInfo dungeonInfo = dungeonService == null
                ? null : dungeonService.findSidebarInfo(player.getUniqueId());
        WorldType worldType = worldService.resolveWorldType(player.getWorld());
        boolean isSkillTreeWorld = skillTreeService.isSkillTreeWorld(player.getWorld());
        String skillTreeClassPointLabel = isSkillTreeWorld
                ? skillTreeService.currentClassPointLabel(astPlayer)
                : null;
        int availableClassPoints = isSkillTreeWorld
                ? skillTreeService.availableClassPoints(astPlayer)
                : 0;
        int availablePassivePoints = isSkillTreeWorld
                ? skillTreeService.availablePassivePoints(astPlayer)
                : 0;
        String regionName = astPlayer.getCurrentRegion();
        if (regionName == null || regionName.isBlank()) {
            regionName = worldType == null
                    ? player.getWorld().getName()
                    : worldType.getRegionDisplayName();
        }
        int classLevel = astPlayer.getClassLevel();
        boolean classLevelMax = playerClassService.isMaxClassLevel(astPlayer);
        long goldAmount = currencyService.getGoldAmount(astPlayer.getAccount().getUuid());
        Integer originalLevel = astPlayer.getAccount().getRebirthOriginalLevel();
        boolean overlay = renderJavaProfileHud(
                astPlayer, playerLevel, originalLevel, goldAmount, className, classLevel, classLevelMax,
                classExperienceProgress, isSkillTreeWorld, availableClassPoints, availablePassivePoints
        );
        playerHudView.renderSidebar(
            player,
            mspt,
            playerLevel,
            classExperienceProgress,
            classLevel,
            classLevelMax,
            className,
            goldAmount,
            !overlay,
            skillTreeClassPointLabel,
            availableClassPoints,
            availablePassivePoints,
            worldService.resolveDisplayName(player.getWorld()),
            regionName,
            resolveRegionLevel(astPlayer, worldType, bossInfo),
            showPerformanceInfo,
            bossInfo,
            dungeonInfo,
            showBuffInfo,
            statusService.getActiveBuffs(astPlayer),
            originalLevel
        );
    }

    /**
     * 対応パックがあるJava版の接続へ2行を描画します。統合版とパック非対応時はサイドバーに委ねます。
     * @param astPlayer 表示対象
     * @param playerLevel プレイヤーレベル
     * @param originalLevel 転生前レベル。転生中でなければnull
     * @param goldAmount 所持Gold
     * @param className 色コードを含むクラスの正式名
     * @param classLevel クラスレベル
     * @param classLevelMax クラスが最大レベルならtrue
     * @param classProgress クラス経験値の進捗
     * @param showPoints スキルツリーポイントを併記する場合true
     * @param classPoints 未使用CP
     * @param passivePoints 未使用PP
     * @return 個人情報を画面へ表示する場合true
     */
    private boolean renderJavaProfileHud(
            AstPlayer astPlayer,
            int playerLevel,
            @Nullable Integer originalLevel,
            long goldAmount,
            String className,
            int classLevel,
            boolean classLevelMax,
            double classProgress,
            boolean showPoints,
            int classPoints,
            int passivePoints
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        if (astPlayer.isBedrock() || !resourcePackService.hasJavaHudV4(playerId)) {
            clearProfileHud(player);
            return false;
        }

        ProfileHudText profile = ProfileHudText.create(playerLevel, originalLevel, goldAmount, className,
                classLevel, classLevelMax, classProgress, showPoints, classPoints, passivePoints,
                resourcePackService.hasJavaHudV6(playerId));
        if (profile == null) {
            clearProfileHud(player);
            return false;
        }
        playerHudView.setJavaProfile(playerId, profile);
        return true;
    }

    /**
     * 退出、復旧、モードやワールドの切替で個人情報を解除します。
     * @param player 表示対象
     */
    public void clearProfileHud(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (playerHudView.setJavaProfile(playerId, null) && player.isOnline()) {
            player.sendActionBar(Component.empty());
        }
    }

    /**
     * 自分のサイドバー効果ページを切り替えて即時描画します。メインスレッド専用です。
     * @param astPlayer 操作したプレイヤー
     * @param direction 前ページは負、次ページは正
     * @return 通常プレイ中で、ページ操作を受け付けた場合true
     */
    public boolean changeSidebarBuffPage(@NotNull AstPlayer astPlayer, int direction) {
        Player player = astPlayer.getBukkit();
        if (plugin == null || !player.isOnline() || player.isDead() || sidebarInputBlocked.test(player)
                || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }
        prepareSidebarBuffPage(astPlayer);
        playerHudView.moveBuffPage(player.getUniqueId(), direction);
        renderSidebar(astPlayer, Bukkit.getServer().getAverageTickTime());
        return true;
    }

    /** アカウント切替を検出し、別アカウントのページを引き継がないようにします。 */
    private void prepareSidebarBuffPage(@NotNull AstPlayer astPlayer) {
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!accountId.equals(sidebarAccounts.put(playerId, accountId))) {
            playerHudView.clearBuffPage(playerId);
        }
    }

    /**
     * 退出・復旧・ワールド移動時に、プレイヤーの効果ページ状態を破棄します。
     * @param playerId 対象プレイヤーUUID
     */
    public void clearSidebarBuffPage(@NotNull UUID playerId) {
        sidebarAccounts.remove(playerId);
        playerHudView.clearBuffPage(playerId);
    }

    /**
     * サイドバーに表示する地域レベルを現在ワールド種別から解決します。
     * 拠点は 0、ボスフィールドは挑戦中ボスのレベル、その他はプレイヤー地域状態を使用します。
     *
     * @param astPlayer 対象プレイヤー
     * @param worldType 現在ワールド種別。管理対象外の場合は {@code null}
     * @param bossInfo 挑戦中ボス情報。挑戦していない場合は {@code null}
     * @return 表示する地域レベル
     */
    private int resolveRegionLevel(
            @NotNull AstPlayer astPlayer,
            @Nullable WorldType worldType,
            @Nullable BossChallengeSidebarInfo bossInfo
    ) {
        if (worldType == WorldType.BASE) {
            return 0;
        }
        if (worldType == WorldType.BOSS_FIELD) {
            return bossInfo == null ? 0 : bossInfo.bossLevel();
        }
        return Math.max(0, astPlayer.getCurrentRegionLevel());
    }

    private void renderStatusActionBar(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        renderPrimaryActionBar(astPlayer, statusService.getStatus(astPlayer));
    }

    private void renderDodgeWindow(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        long remaining = Math.max(0L, astPlayer.getSneakDodgeWindowExpiresAtMs() - System.currentTimeMillis());
        double progress = (double) remaining / (double) io.github.maaasu.astralRecord.feature.player.service.DodgeService.QUICK_SNEAK_WINDOW_MS;
        playerHudView.renderDodgeWindowActionBar(player, progress,
                conditionService.getActiveConditions(AstEntity.player(astPlayer)));
    }

    private void renderWallClingWindow(AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        long remaining = Math.max(0L, astPlayer.getWallClingExpiresAtMs() - System.currentTimeMillis());
        double progress = (double) remaining / (double) io.github.maaasu.astralRecord.feature.player.service.AirActionService.WALL_CLING_DURATION_MS;
        playerHudView.renderWallClingActionBar(player, progress, statusService.getStatus(astPlayer),
                conditionService.getActiveConditions(AstEntity.player(astPlayer)));
    }

    private boolean isDodgeWindowActive(AstPlayer astPlayer) {
        return astPlayer.getSneakDodgeWindowExpiresAtMs() > System.currentTimeMillis();
    }

    private boolean isWallClingActive(AstPlayer astPlayer) {
        return astPlayer.getWallClingExpiresAtMs() > System.currentTimeMillis();
    }

    private void showActionBarOverride(AstPlayer astPlayer, Runnable renderer, BooleanSupplier activeChecker) {
        cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
        renderer.run();
        BukkitTask actionBarOverrideTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!activeChecker.getAsBoolean()) {
                cancelActionBarOverrideTask(astPlayer.getBukkit().getUniqueId());
                refreshActionBar(astPlayer);
                return;
            }
            renderer.run();
        }, 1L, 1L);
        actionBarOverrideTasks.put(astPlayer.getBukkit().getUniqueId(), actionBarOverrideTask);
    }

    private void cancelActionBarOverrideTask(UUID playerUuid) {
        BukkitTask task = actionBarOverrideTasks.remove(playerUuid);
        if (task != null) {
            task.cancel();
        }
    }

    private void renderPrimaryActionBar(@NotNull AstPlayer astPlayer, @NotNull StatusSnapshot snapshot) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline() || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return;
        }
        // 表示を省略する場合も、従来の取得経路で期限切れsampleを解放する。
        double currentDps = combatDpsTrackerService == null
                ? 0.0D
                : combatDpsTrackerService.getAverageDps(player.getUniqueId(), DPS_SAMPLE_WINDOW_MS);
        Component override = resolvePrimaryActionBar(astPlayer);
        if (override != null) {
            var activeConditions = resourcePackService.hasJavaHudV4(player.getUniqueId())
                    ? conditionService.getActiveConditions(AstEntity.player(astPlayer)) : List.<ActiveCondition>of();
            playerHudView.sendActionBar(player, override, activeConditions);
            return;
        }
        boolean showDps = playerSettingService.isCombatDpsDisplayEnabled(astPlayer.getUser().getUuid());
        PaladinGuardRuntimeService.GuardSnapshot guard = paladinGuardRuntimeService == null
                ? new PaladinGuardRuntimeService.GuardSnapshot(false, 0.0D, 0.0D)
                : paladinGuardRuntimeService.snapshot(astPlayer);
        ArchmagePhoenixRuntimeService.PhoenixSnapshot phoenix = archmagePhoenixRuntimeService == null
                ? new ArchmagePhoenixRuntimeService.PhoenixSnapshot(false, 0.0D, 0.0D)
                : archmagePhoenixRuntimeService.snapshot(astPlayer);
        playerHudView.renderActionBar(
            player,
            snapshot,
            conditionService.getActiveConditions(AstEntity.player(astPlayer)),
            statusService.getShieldRechargeState(astPlayer),
            currentDps,
            guard.active(),
            guard.current(),
            guard.maximum(),
            statusService.getShieldDisplayCapacity(astPlayer),
            phoenix.active(),
            phoenix.current(),
            phoenix.maximum(),
            swordSpiritRenderer.apply(astPlayer),
            showDps
        );
    }

    private @Nullable Component resolvePrimaryActionBar(@NotNull AstPlayer astPlayer) {
        Function<AstPlayer, Component> renderer = primaryActionBarRenderers.get(astPlayer.getBukkit().getUniqueId());
        return renderer == null ? null : renderer.apply(astPlayer);
    }
}
