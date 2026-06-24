package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.core.CommandRegister;
import io.github.maaasu.astralRecord.core.event.EventManager;
import io.github.maaasu.astralRecord.feature.adventurerecord.event.AdventureRecordGuiEventHandler;
import io.github.maaasu.astralRecord.feature.adventurerecord.gui.AdventureRecordGui;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.boss.event.BossEntryEventHandler;
import io.github.maaasu.astralRecord.feature.boss.event.BossPlayerEventHandler;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.boss.service.BossFieldInstanceService;
import io.github.maaasu.astralRecord.feature.combat.event.CombatDamageEventHandler;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.buff.service.BuffAcquisitionDisplayService;
import io.github.maaasu.astralRecord.feature.gathering.event.GatheringInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.repository.GatheringDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.event.GatheringSpawnerBlockEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.shared.gui.debug.PagingDebugGui;
import io.github.maaasu.astralRecord.shared.gui.debug.event.PagingDebugGuiEventHandler;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitService;
import io.github.maaasu.astralRecord.shared.gui.event.GuiClickCooldownEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.item.event.ItemInteractionBlockEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemAdminGuiEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemWeaponAttackEventHandler;
import io.github.maaasu.astralRecord.feature.item.gui.ItemAdminGuiView;
import io.github.maaasu.astralRecord.feature.item.executor.WeaponAttackSkillExecutor;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseEffectService;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentEnhancementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.event.InventoryEquipmentGuiEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryAutoSaveTask;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveTask;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.loginbonus.event.LoginBonusGuiEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mail.event.MailGuiEventHandler;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerDetailGui;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListGui;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.event.MobInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.mob.event.NpcPlacementWorldEventHandler;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobAiService;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.spawner.event.MobSpawnerBlockEventHandler;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.party.event.PartyGuiEventHandler;
import io.github.maaasu.astralRecord.feature.party.event.PartyQuitEventHandler;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyMemberActionGui;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.ManagedChatEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerModeEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerInputEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerSneakEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerVanillaDamageBlockEventHandler;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathEventHandler;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.event.PlayerSettingGuiEventHandler;
import io.github.maaasu.astralRecord.feature.playersetting.event.PlayerSettingJoinEventHandler;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingDefaults;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackJoinEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackStatusEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.sell.service.SellService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillActionRingEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.executor.FireBoostSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.IronWillSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.StatusPassiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skilltree.event.SkillTreeEventHandler;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.status.service.StatusRegenTask;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.feature.status.event.PlayerHeldItemStatusEventHandler;
import io.github.maaasu.astralRecord.feature.textdisplay.event.TextDisplayPlacementWorldEventHandler;
import io.github.maaasu.astralRecord.feature.textdisplay.repository.TextDisplayPlacementRepository;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterInteractEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterPlayerEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.gui.TeleporterGui;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.teleporter.service.WaystoneHitBoxResolver;
import io.github.maaasu.astralRecord.feature.teleporter.view.WaystonePacketView;
import io.github.maaasu.astralRecord.feature.trade.event.TradeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.feature.user.event.UserLoginEventHandler;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.world.config.PluginJoinSpawnWorldConfig;
import io.github.maaasu.astralRecord.feature.world.event.WorldChangeTitleEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldNaturalSpawnBlockEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldJoinSpawnEventHandler;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.feature.world.service.WorldSpawnParticleTask;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.api.ApiHealthChecker;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config.YamlDbConfigUtil;
import io.github.maaasu.astralRecord.infrastructure.database.sqlserver.SqlServerManager;
import io.github.maaasu.astralRecord.infrastructure.logging.AuditLogger;
import io.github.maaasu.astralRecord.infrastructure.logging.AuditLoggerRegistry;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.display.OverheadDisplayService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.plugin.java.JavaPlugin;

public final class AstralRecord extends JavaPlugin {

    private static AstralRecord instance;

    // feature services
    private ItemService itemService;
    private LootService lootService;
    private ItemStackFactory itemStackFactory;
    private AccountService accountService;
    private UserService userService;
    private PlayerService playerService;
    private PlayerMessageService playerMessageService;
    private InventoryService inventoryService;
    private InventoryPersistence inventoryPersistence;
    private PlayerInventoryStateRegistry inventoryStateRegistry;
    private InventoryAutoSaveTask inventoryAutoSaveTask;
    private CurrencyService currencyService;
    private StatusService statusService;
    private StatusRegenTask statusRegenTask;
    private DodgeService dodgeService;
    private AirActionService airActionService;
    private PlayerHudService playerHudService;
    private PlayerDeathService playerDeathService;
    private ResourcePackService resourcePackService;
    private MenuView menuView;
    private MenuOpenEventHandler menuOpenEventHandler;
    private MenuGuiTransitionService menuGuiTransitionService;
    private TrashService trashService;
    private SellService sellService;
    private StorageService storageService;
    private PlayerListGui playerListGui;
    private PlayerDetailGui playerDetailGui;
    private PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler;
    private PagingDebugGui pagingDebugGui;
    private MobService mobService;
    private MobSpawnerService mobSpawnerService;
    private GatheringService gatheringService;
    private GatheringSpawnerService gatheringSpawnerService;
    private NpcPlacementService npcPlacementService;
    private MobAiService mobAiService;
    private MobCombatService mobCombatService;
    private MobDropPresentationService mobDropPresentationService;
    private EventManager eventManager;
    private ParticleDisplayService particleDisplayService;
    private DisplayTextService displayTextService;
    private TextDisplayPlacementService textDisplayPlacementService;
    private TeleporterService teleporterService;
    private TeleporterGui teleporterGui;
    private TeleporterGuiEventHandler teleporterGuiEventHandler;
    private WaystonePacketView waystonePacketView;
    private WaystoneHitBoxResolver waystoneHitBoxResolver;
    private OverheadDisplayService overheadDisplayService;
    private PlayerSettingService playerSettingService;
    private PlayerSettingGui playerSettingGui;
    private SkillService skillService;
    private SkillActionRingService skillActionRingService;
    private PassiveSkillService passiveSkillService;
    private SkillTreeService skillTreeService;
    private SkillBindPresetService skillBindPresetService;
    private SkillOwnershipService skillOwnershipService;
    private SkillBindGui skillBindGui;
    private SkillBindGuiEventHandler skillBindGuiEventHandler;
    private DamageService damageService;
    private BundleUseService bundleUseService;
    private BundleUseEffectService bundleUseEffectService;
    private ItemDropAnimationService itemDropAnimationService;
    private MovementCancelableWaitService movementCancelableWaitService;
    private BuffAcquisitionDisplayService buffAcquisitionDisplayService;
    private PotionUseService potionUseService;
    private PlayerClassService playerClassService;
    private ItemWeaponAttackService itemWeaponAttackService;
    private EquipmentEnhancementService equipmentEnhancementService;
    private WorldService worldService;
    private ReturnToBaseService returnToBaseService;
    private WorldSpawnParticleTask worldSpawnParticleTask;
    private PartyService partyService;
    private PartyGui partyGui;
    private PartyMemberActionGui partyMemberActionGui;
    private LoginBonusService loginBonusService;
    private MailService mailService;
    private MailGuiEventHandler mailGuiEventHandler;
    private ItemAdminGuiEventHandler itemAdminGuiEventHandler;
    private AdventureRecordService adventureRecordService;
    private AdventureRecordGuiEventHandler adventureRecordGuiEventHandler;
    private ShopService shopService;
    private ShopGui shopGui;
    private ShopGuiEventHandler shopGuiEventHandler;
    private TradeService tradeService;
    private TradeGui tradeGui;
    private TradeCancelConfirmGui tradeCancelConfirmGui;
    private GoldAmountSettingGui goldAmountSettingGui;
    private BossFieldInstanceService bossFieldInstanceService;
    private BossChallengeService bossChallengeService;
    private String joinSpawnWorldId;

    @Override
    public void onLoad() {
        instance = this;
        itemService = new ItemService();
        lootService = new LootService();
        itemStackFactory = new ItemStackFactory(lootService, itemService);
        mobService = new MobService(this, new MobRepository());
        npcPlacementService = new NpcPlacementService(this, mobService, new NpcPlacementRepository(this));
        textDisplayPlacementService = new TextDisplayPlacementService(this, new TextDisplayPlacementRepository(this));
        teleporterService = new TeleporterService(this, new WaystoneDefinitionRepository(this), new AccountWaystoneRepository());
        mobSpawnerService = new MobSpawnerService(
                this,
                mobService,
                new MobSpawnerDefinitionRepository(),
                new MobSpawnerLocationRepository(this)
        );
        gatheringService = new GatheringService(
                this,
                new GatheringDefinitionRepository(),
                new MobDropService(),
                null
        );
        gatheringSpawnerService = new GatheringSpawnerService(
                this,
                gatheringService,
                new GatheringSpawnerDefinitionRepository(),
                new GatheringSpawnerLocationRepository(this)
        );
        worldService = new WorldService(new WorldRepository());
        skillTreeService = new SkillTreeService(
                this,
                worldService,
                null,
                new SkillTreeNodeRepository(),
                new SkillTreeStructureRepository(this),
                new SkillTreePlayerStateRepository(this)
        );
        joinSpawnWorldId = PluginJoinSpawnWorldConfig.load(this);
        // CommandManager は Paper Lifecycle API の制約に合わせて
        // onLoad() で初期化し、コマンド登録クラスを先に生成する。
        new CommandRegister(
                itemService,
                itemStackFactory,
                mobService,
                mobSpawnerService,
                npcPlacementService,
                worldService,
                skillTreeService,
                gatheringService,
                gatheringSpawnerService,
                textDisplayPlacementService,
                teleporterService
        );
        CommandManager.getInstance().initialize(this);
    }

    @Override
    public void onEnable() {
        // AuditLogger を初期化
        AuditLogger.initDefault();

        // すべての LogEntry 実装クラスを走査し、AuditLogger を登録する
        AuditLoggerRegistry.init("io.github.maaasu.astralRecord");

        if (!setupInfrastructure()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. feature を初期化
        eventManager = new EventManager(this);
        eventManager.registerHandler(
            new NpcPlacementWorldEventHandler(this, npcPlacementService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TextDisplayPlacementWorldEventHandler(this, textDisplayPlacementService),
            getServer().getPluginManager()
        );
        setupFeature();

        // 4. イベントとコマンドを登録
        registerPluginFeatures();
    }

    @Override
    public void onDisable() {
        if (inventoryAutoSaveTask != null) {
            inventoryAutoSaveTask.stop();
        }
        if (accountService != null) {
            accountService.stop();
        }
        if (playerService != null) {
            playerService.saveAllOnlinePlayersAndClear();
        }
        if (inventoryService != null) {
            inventoryService.awaitPendingWrites(5000L);
        }
        if (airActionService != null) {
            airActionService.stop();
        }
        if (playerHudService != null) {
            playerHudService.stop();
        }
        if (statusRegenTask != null) {
            statusRegenTask.stop();
        }
        if (overheadDisplayService != null) {
            overheadDisplayService.stop();
        }
        if (playerDeathService != null) {
            playerDeathService.stop();
        }
        if (textDisplayPlacementService != null) {
            textDisplayPlacementService.saveIfDirty();
            textDisplayPlacementService.stop();
        }
        if (teleporterService != null) {
            teleporterService.stop();
        }
        if (displayTextService != null) {
            displayTextService.stop();
        }
        if (mobAiService != null) {
            mobAiService.stop();
        }
        if (mobSpawnerService != null) {
            mobSpawnerService.stop();
        }
        if (gatheringSpawnerService != null) {
            gatheringSpawnerService.stop();
        }
        if (gatheringService != null) {
            gatheringService.stop();
        }
        if (npcPlacementService != null) {
            npcPlacementService.saveIfDirty();
        }
        if (worldSpawnParticleTask != null) {
            worldSpawnParticleTask.stop();
        }
        if (partyService != null) {
            partyService.clearAll();
        }
        if (tradeService != null) {
            tradeService.cancelAll();
        }
        if (returnToBaseService != null) {
            returnToBaseService.cancelAll();
        }
        if (bossChallengeService != null) {
            bossChallengeService.stop();
        }
        if (skillActionRingService != null) {
            skillActionRingService.stop();
        }
        if (passiveSkillService != null) {
            passiveSkillService.stop();
        }
        if (skillTreeService != null) {
            skillTreeService.stop();
        }
        if (skillService != null) {
            skillService.stop();
        }
        if (mobService != null) {
            mobService.destroyAll();
        }
        // AuditLogger をシャットダウン
        AuditLogger.shutdownDefault();
        AuditLoggerRegistry.shutdownAll();
        // コマンドマネージャーをシャットダウン
        CommandManager.getInstance().shutdown();
        getServer().getScheduler().cancelTasks(this);
        if (ConfigProperties.getInstance().isSqlserverEnabled()) {
            SqlServerManager.getInstance().shutdown();
        }
    }

    /**
     * 設定ファイル、データベース接続、基盤サービス初期化をセットアップします。
     *
     * @return 成功した場合は true
     */
    private boolean setupInfrastructure() {
        try {
            // 設定ファイルを初期化
            ConfigManager.getInstance().initialize();


            // DB を初期化
            if (ConfigProperties.getInstance().isSqlserverEnabled()) {
                SqlServerManager.getInstance().initialize();
            } else {
                Logger.log(LogId.I_1104);
            }

            // フォルダ型データベースを初期化
            FileDatabaseManager.getInstance();

            // YamlDB 設定を初期化
            YamlDbConfigUtil.INSTANCE.reload();

            // AstralRecord API 疎通確認を非同期で開始
            ApiHealthChecker.checkAsync();

            return true;
        }catch (Exception e) {
            Logger.log(LogId.E_900, e);
            return false;
        }
    }

    /**
     * プラグインの feature 群をセットアップします。
     */
    private void setupFeature() {
        // account
        var accountRepository = new AccountRepository();
        accountService = new AccountService(this, accountRepository);

        // user
        var userRepository = new UserRepository();
        userService = new UserService(userRepository, accountService);
        partyService = new PartyService(this, userService);
        partyGui = new PartyGui(partyService);

        // inventory
        var inventoryRepository = new InventoryRepository();
        var equipmentLoadoutRepository = new EquipmentLoadoutRepository();
        inventoryStateRegistry = new PlayerInventoryStateRegistry();
        inventoryPersistence = new InventoryPersistence(inventoryRepository, equipmentLoadoutRepository);
        inventoryService = new InventoryService(
            inventoryRepository,
            equipmentLoadoutRepository,
            itemService,
            itemStackFactory,
            inventoryStateRegistry,
            inventoryPersistence
        );
        skillTreeService.setInventoryService(inventoryService);
        inventoryAutoSaveTask = new InventoryAutoSaveTask(inventoryService, inventoryPersistence, inventoryStateRegistry);
        currencyService = new CurrencyService(inventoryService);
        playerSettingService = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );
        adventureRecordService = new AdventureRecordService(
            this,
            new AdventureRecordRepository(),
            mobService,
            playerSettingService
        );
        particleDisplayService = new ParticleDisplayService(playerSettingService);
        mobSpawnerService.setParticleDisplayService(particleDisplayService);
        displayTextService = new DisplayTextService();
        textDisplayPlacementService.setDisplayTextService(displayTextService);
        waystonePacketView = new WaystonePacketView(teleporterService);
        teleporterGui = new TeleporterGui(teleporterService);
        teleporterGuiEventHandler = new TeleporterGuiEventHandler(teleporterGui, teleporterService, inventoryService);
        waystoneHitBoxResolver = new WaystoneHitBoxResolver(teleporterService);
        teleporterService.setRuntimeServices(inventoryService, worldService, waystonePacketView, teleporterGui, teleporterGuiEventHandler);
        movementCancelableWaitService = new MovementCancelableWaitService(this);
        bundleUseEffectService = new BundleUseEffectService();
        itemDropAnimationService = new ItemDropAnimationService(this, itemStackFactory, particleDisplayService);
        bundleUseService = new BundleUseService(
            movementCancelableWaitService,
            itemService,
            lootService,
            inventoryService,
            itemStackFactory,
            itemDropAnimationService,
            bundleUseEffectService,
            particleDisplayService
        );

        // class
        playerClassService = new PlayerClassService();

        // status
        statusService = new StatusService(itemService, inventoryService);
        statusService.setPlayerClassService(playerClassService);
        statusService.setSkillTreeService(skillTreeService);
        buffAcquisitionDisplayService = new BuffAcquisitionDisplayService(displayTextService);
        potionUseService = new PotionUseService(
            movementCancelableWaitService,
            inventoryService,
            statusService,
            buffAcquisitionDisplayService,
            particleDisplayService
        );
        statusRegenTask = new StatusRegenTask(statusService);
        playerHudService = new PlayerHudService(statusService, playerClassService, accountService);
        skillTreeService.setPlayerHudService(playerHudService);
        overheadDisplayService = new OverheadDisplayService(displayTextService, statusService, mobService, playerClassService);

        // combat
        mobDropPresentationService = new MobDropPresentationService(
                this,
                itemService,
                inventoryService,
                itemStackFactory,
                itemDropAnimationService
        );
        gatheringService.setDropPresentationService(mobDropPresentationService);
        var mobKnockbackService = new MobKnockbackService(mobService);
        mobCombatService = new MobCombatService(
                mobService,
                new MobDropService(),
                mobDropPresentationService,
                partyService,
                adventureRecordService,
                accountService,
                playerClassService,
                statusService,
                skillTreeService,
                particleDisplayService
        );
        playerDeathService = new PlayerDeathService(
            this,
            accountService,
            statusService,
            mobService,
            worldService,
            displayTextService,
            joinSpawnWorldId
        );
        mobCombatService.setPlayerDeathService(playerDeathService);
        damageService = new DamageService(
            statusService,
            mobService,
            mobCombatService,
            mobKnockbackService,
            displayTextService,
            playerSettingService,
            particleDisplayService,
            playerDeathService
        );

        // dodge
        dodgeService = new DodgeService(this, statusService, playerHudService, particleDisplayService);
        airActionService = new AirActionService(this, playerHudService, particleDisplayService);

        var playerSaveCoordinator = new PlayerSaveCoordinator(
            java.util.List.of(new InventorySaveTask(inventoryService, inventoryStateRegistry, inventoryPersistence))
        );
        // player
        playerService = new PlayerService(
            userService,
            accountService,
            inventoryService,
            inventoryPersistence,
            inventoryStateRegistry,
            statusService,
            playerSaveCoordinator
        );
        playerMessageService = new PlayerMessageService();
        returnToBaseService = new ReturnToBaseService(
            this,
            movementCancelableWaitService,
            worldService,
            inventoryService,
            particleDisplayService,
            joinSpawnWorldId
        );
        bossFieldInstanceService = new BossFieldInstanceService(this, worldService);
        String bossHubWorldId = getConfig().getString(
            "boss.hubWorldId",
            getConfig().getString("plugin.boss.hubWorldId", "boss_hub")
        );
        bossChallengeService = new BossChallengeService(
            this,
            mobService,
            worldService,
            partyService,
            playerMessageService,
            bossFieldInstanceService,
            displayTextService,
            bossHubWorldId
        );
        damageService.setBossChallengeService(bossChallengeService);

        // resource pack
        resourcePackService = new ResourcePackService(ConfigProperties.getInstance());

        // menu
        menuView = new MenuView(this);
        menuGuiTransitionService =
            new MenuGuiTransitionService(this, menuView, inventoryService);
        trashService = new TrashService(this, menuView, inventoryService, menuGuiTransitionService);
        sellService = new SellService(this, menuView, inventoryService, menuGuiTransitionService);
        storageService = new StorageService(menuView, inventoryService, menuGuiTransitionService);
        equipmentEnhancementService = new EquipmentEnhancementService(
            menuView,
            inventoryService,
            itemService,
            itemStackFactory
        );
        playerListGui = new PlayerListGui();
        playerDetailGui = new PlayerDetailGui();
        pagingDebugGui = new PagingDebugGui();
        playerSettingGui = new PlayerSettingGui(playerSettingService);
        adventureRecordGuiEventHandler = new AdventureRecordGuiEventHandler(
            new AdventureRecordGui(itemService),
            adventureRecordService,
            inventoryService
        );
        loginBonusService = new LoginBonusService(new LoginBonusGui(), inventoryService, itemService);
        partyMemberActionGui = new PartyMemberActionGui();
        mailService = new MailService(new MailRepository(), itemService, inventoryService);
        shopService = new ShopService(
            new ShopRepository(),
            new ShopRecipeRepository(),
            itemService,
            inventoryService,
            currencyService
        );
        itemAdminGuiEventHandler = new ItemAdminGuiEventHandler(
            new ItemAdminGuiView(this, itemStackFactory),
            itemService,
            inventoryService
        );
        shopGui = new ShopGui(this, shopService, itemStackFactory);
        shopGuiEventHandler = new ShopGuiEventHandler(shopGui, shopService, inventoryService);
        tradeGui = new TradeGui();
        tradeCancelConfirmGui = new TradeCancelConfirmGui();
        goldAmountSettingGui = new GoldAmountSettingGui();
        tradeService = new TradeService(
            this,
            tradeGui,
            tradeCancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            playerMessageService,
            itemService
        );

        // skill
        skillService = new SkillService(new SkillRepository(), new SkillRegistry(), this);
        skillBindPresetService = new SkillBindPresetService(new SkillBindPresetRepository());
        skillService.registerExecutor(new FireBoostSkillExecutor(particleDisplayService));
        skillService.registerExecutor(new IronWillSkillExecutor());
        skillService.registerExecutor(new StatusPassiveSkillExecutor());
        skillService.registerExecutor(new WeaponAttackSkillExecutor(particleDisplayService, damageService));
        skillService.registerBuiltInDefinitions(BuiltInWeaponAttackDefinitions.definitions());
        itemStackFactory.setSkillService(skillService);
        skillOwnershipService = new SkillOwnershipService(playerClassService, inventoryService, itemService, skillTreeService);
        skillService.setOwnershipService(skillOwnershipService);
        passiveSkillService = new PassiveSkillService(this, skillService, skillBindPresetService, skillOwnershipService);
        passiveSkillService.setStatusService(statusService);
        statusService.setPassiveSkillService(passiveSkillService);
        skillTreeService.setStatusService(statusService);
        skillTreeService.setSkillService(skillService);
        skillTreeService.setPassiveSkillService(passiveSkillService);
        skillActionRingService = new SkillActionRingService(this, skillBindPresetService, skillService, skillOwnershipService);
        skillBindGui = new SkillBindGui(this);
        itemWeaponAttackService = new ItemWeaponAttackService(inventoryService, skillService);

        // item, loot, skill, class・医・繧ｹ繧ｿ繝・・繧ｿ髱槫酔譛溘Ο繝ｼ繝会ｼ・
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            lootService.loadAll();
            itemService.loadAll();
            skillService.reloadDefinitions();
            playerClassService.loadAll();
        });

        // mob
        mobService.loadAll();
        npcPlacementService.loadAll();
        textDisplayPlacementService.loadAll();
        mobSpawnerService.loadAll();
        gatheringService.loadAll();
        gatheringSpawnerService.loadAll();
        teleporterService.loadAll();
        // world
        worldService.loadAll();
        mobAiService = new MobAiService(mobService, mobCombatService, skillService, playerDeathService, particleDisplayService);
        mobAiService.start();
        skillTreeService.loadAll();
        worldSpawnParticleTask = new WorldSpawnParticleTask(this, worldService, particleDisplayService);

        // item: ProtocolLib 繝代こ繝・ヨ繧｢繝繝励ち・・con 蟾ｮ縺玲崛縺茨ｼ臥匳骭ｲ
        ItemStackPacketAdapter packetAdapter = new ItemStackPacketAdapter(this);
        packetAdapter.register();

    }

    /**
     * 繧､繝吶Φ繝医ｄ繧ｳ繝槭Φ繝峨↑縺ｩ縺ｮ讖溯・繧堤匳骭ｲ
     */
    private void registerPluginFeatures() {
        eventManager.registerHandler(
            new GuiClickCooldownEventHandler(),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new UserLoginEventHandler(userService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerJoinEventHandler(this, playerService, loginBonusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ManagedChatEventHandler(this),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new LoginBonusGuiEventHandler(loginBonusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldJoinSpawnEventHandler(this, joinSpawnWorldId, worldService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldChangeTitleEventHandler(worldService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldNaturalSpawnBlockEventHandler(this, worldService, mobService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new BossEntryEventHandler(bossChallengeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new BossPlayerEventHandler(bossChallengeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ResourcePackJoinEventHandler(resourcePackService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ResourcePackStatusEventHandler(resourcePackService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ItemInteractionBlockEventHandler(inventoryService, bundleUseService, potionUseService),
            getServer().getPluginManager()
        );
        menuOpenEventHandler = new MenuOpenEventHandler(
            this,
            menuView,
            inventoryService,
            currencyService,
            statusService,
            menuGuiTransitionService,
            trashService,
            sellService,
            storageService,
            skillTreeService,
            returnToBaseService
        );
        eventManager.registerHandler(menuOpenEventHandler, getServer().getPluginManager());
        mailGuiEventHandler = new MailGuiEventHandler(new MailGuiView(this, itemService), mailService, menuView, inventoryService);
        eventManager.registerHandler(
            mailGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            itemAdminGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            shopGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TradeGuiEventHandler(
                this,
                tradeGui,
                tradeCancelConfirmGui,
                goldAmountSettingGui,
                tradeService,
                inventoryService,
                playerMessageService
            ),
            getServer().getPluginManager()
        );
        playerBrowserGuiEventHandler = new PlayerBrowserGuiEventHandler(
            this,
            playerListGui,
            playerDetailGui,
            partyService,
            statusService,
            menuView
        );
        eventManager.registerHandler(
            playerBrowserGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new InventoryEquipmentGuiEventHandler(
                menuView,
                inventoryService,
                currencyService,
                statusService,
                passiveSkillService,
                equipmentEnhancementService
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerHeldItemStatusEventHandler(this, statusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PagingDebugGuiEventHandler(pagingDebugGui, menuView),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSettingJoinEventHandler(this, playerSettingService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSettingGuiEventHandler(playerSettingGui, playerSettingService, inventoryService, menuView),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            adventureRecordGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            teleporterGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TeleporterInteractEventHandler(teleporterService, waystoneHitBoxResolver),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TeleporterPlayerEventHandler(this, teleporterService),
            getServer().getPluginManager()
        );
        skillBindGuiEventHandler = new SkillBindGuiEventHandler(
            this,
            skillBindGui,
            skillService,
            skillBindPresetService,
            skillOwnershipService,
            passiveSkillService,
            inventoryService,
            menuView
        );
        eventManager.registerHandler(
            skillBindGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new SkillActionRingEventHandler(skillActionRingService, inventoryService, skillTreeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerModeEventHandler(),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerInputEventHandler(airActionService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSneakEventHandler(airActionService, dodgeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerVanillaDamageBlockEventHandler(),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerDeathEventHandler(playerDeathService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new CombatDamageEventHandler(damageService, skillActionRingService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MobSpawnerBlockEventHandler(mobSpawnerService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new GatheringSpawnerBlockEventHandler(gatheringSpawnerService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new GatheringInteractionEventHandler(gatheringService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new SkillTreeEventHandler(skillTreeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MobInteractionEventHandler(
                mobService,
                shopGuiEventHandler,
                menuView,
                playerClassService,
                storageService,
                equipmentEnhancementService
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ItemWeaponAttackEventHandler(itemWeaponAttackService, skillActionRingService, skillTreeService, mobService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PartyGuiEventHandler(partyGui, partyMemberActionGui, partyService, menuView, playerBrowserGuiEventHandler),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PartyQuitEventHandler(partyService),
            getServer().getPluginManager()
        );
        playerHudService.start(this);
        statusRegenTask.start(this);
        displayTextService.start(this);
        overheadDisplayService.start(this);
        playerDeathService.start();
        worldSpawnParticleTask.start();
        mobSpawnerService.start();
        gatheringService.start();
        gatheringSpawnerService.start();
        bossChallengeService.start();
        passiveSkillService.start();
        skillTreeService.start();
        // 繧､繝ｳ繝吶Φ繝医Μ繧ｪ繝ｼ繝医そ繝ｼ繝・(60s) 繧帝幕蟋・
        inventoryAutoSaveTask.start(this, InventoryAutoSaveTask.DEFAULT_INTERVAL_TICKS);
    }
    /**
     * AstralSaga 縺ｮ繧､繝ｳ繧ｹ繧ｿ繝ｳ繧ｹ繧貞叙蠕励＠縺ｾ縺吶・
     * 莉悶・繧ｯ繝ｩ繧ｹ縺九ｉ繧､繝ｳ繧ｹ繧ｿ繝ｳ繧ｹ繧貞叙蠕励☆繧句ｿ・ｦ√′縺ゅｋ蝣ｴ蜷医↓菴ｿ逕ｨ縺励∪縺吶・
     */
    public static AstralRecord getInstance() {
        return instance;
    }

    /**
     * {@link ItemStackFactory} 縺ｮ繧､繝ｳ繧ｹ繧ｿ繝ｳ繧ｹ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return ItemStackFactory
     */
    public ItemStackFactory getItemStackFactory() {
        return itemStackFactory;
    }

    public ItemService getItemService() {
        return itemService;
    }

    public InventoryService getInventoryService() {
        return inventoryService;
    }

    /**
     * プレイヤーメッセージ送信サービスを取得する。
     *
     * @return プレイヤーメッセージ送信サービス
     */
    public PlayerMessageService getPlayerMessageService() {
        return playerMessageService;
    }

    /**
     * 騾夊ｲｨ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 騾夊ｲｨ繧ｵ繝ｼ繝薙せ
     */
    public CurrencyService getCurrencyService() {
        return currencyService;
    }

    /**
     * 繝ｭ繧ｰ繧､繝ｳ繝懊・繝翫せ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 繝ｭ繧ｰ繧､繝ｳ繝懊・繝翫せ繧ｵ繝ｼ繝薙せ
     */
    public LoginBonusService getLoginBonusService() {
        return loginBonusService;
    }

    /**
     * 繝｡繝九Η繝ｼ GUI 陦ｨ遉ｺ繝薙Η繝ｼ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 繝｡繝九Η繝ｼ GUI 陦ｨ遉ｺ繝薙Η繝ｼ
     */
    public MenuView getMenuView() {
        return menuView;
    }

    public MenuOpenEventHandler getMenuOpenEventHandler() {
        return menuOpenEventHandler;
    }

    public TrashService getTrashService() {
        return trashService;
    }

    public SellService getSellService() {
        return sellService;
    }

    /**
     * 装備強化 GUI サービスを取得します。
     *
     * @return 装備強化 GUI サービス
     */
    public EquipmentEnhancementService getEquipmentEnhancementService() {
        return equipmentEnhancementService;
    }

    public StorageService getStorageService() {
        return storageService;
    }

    public PlayerListGui getPlayerListGui() {
        return playerListGui;
    }

    public PlayerDetailGui getPlayerDetailGui() {
        return playerDetailGui;
    }

    public PlayerBrowserGuiEventHandler getPlayerBrowserGuiEventHandler() {
        return playerBrowserGuiEventHandler;
    }

    /**
     * 繝壹・繧ｸ繝ｳ繧ｰ遒ｺ隱咲畑縺ｮ繝繝溘・ GUI 繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 繝壹・繧ｸ繝ｳ繧ｰ遒ｺ隱・GUI
     */
    public PagingDebugGui getPagingDebugGui() {
        return pagingDebugGui;
    }

    public UserService getUserService() {
        return userService;
    }

    public AccountService getAccountService() {
        return accountService;
    }

    public StatusService getStatusService() {
        return statusService;
    }

    /**
     * TextDisplay 蝓ｺ逶､繧ｵ繝ｼ繝薙せ繧定ｿ斐＠縺ｾ縺吶・
     *
     * @return TextDisplay 蝓ｺ逶､繧ｵ繝ｼ繝薙せ
     */
    public DisplayTextService getDisplayTextService() {
        return displayTextService;
    }

    public OverheadDisplayService getOverheadDisplayService() {
        return overheadDisplayService;
    }

    public MobService getMobService() {
        return mobService;
    }

    /**
     * Mob 繧ｹ繝昴リ繝ｼ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return Mob 繧ｹ繝昴リ繝ｼ繧ｵ繝ｼ繝薙せ
     */
    public MobSpawnerService getMobSpawnerService() {
        return mobSpawnerService;
    }

    public PlayerSettingService getPlayerSettingService() {
        return playerSettingService;
    }

    public PlayerSettingGui getPlayerSettingGui() {
        return playerSettingGui;
    }

    /**
     * 繧ｹ繧ｭ繝ｫ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 繧ｹ繧ｭ繝ｫ繧ｵ繝ｼ繝薙せ
     */
    public SkillService getSkillService() {
        return skillService;
    }

    /**
     * スキルツリーサービスを返します。
     *
     * @return スキルツリーサービス
     */
    public SkillTreeService getSkillTreeService() {
        return skillTreeService;
    }

    /**
     * 繧ｹ繧ｭ繝ｫ繝舌う繝ｳ繝・GUI 繧､繝吶Φ繝医ワ繝ｳ繝峨Λ繧定ｿ斐＠縺ｾ縺吶・
     *
     * @return 繧ｹ繧ｭ繝ｫ繝舌う繝ｳ繝・GUI 繧､繝吶Φ繝医ワ繝ｳ繝峨Λ
     */
    public SkillBindGuiEventHandler getSkillBindGuiEventHandler() {
        return skillBindGuiEventHandler;
    }

    /**
     * 謌ｦ髣倥ム繝｡繝ｼ繧ｸ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 謌ｦ髣倥ム繝｡繝ｼ繧ｸ繧ｵ繝ｼ繝薙せ
     */
    public DamageService getDamageService() {
        return damageService;
    }

    /**
     * 閨ｷ讌ｭ繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return 閨ｷ讌ｭ繧ｵ繝ｼ繝薙せ
     */
    public PlayerClassService getPlayerClassService() {
        return playerClassService;
    }

    /**
     * WorldMasterData 繧ｵ繝ｼ繝薙せ繧貞叙蠕励＠縺ｾ縺吶・
     *
     * @return WorldMasterData 繧ｵ繝ｼ繝薙せ
     */
    public WorldService getWorldService() {
        return worldService;
    }

    public BossChallengeService getBossChallengeService() {
        return bossChallengeService;
    }

    public PartyService getPartyService() {
        return partyService;
    }

    public PartyGui getPartyGui() {
        return partyGui;
    }

    public PartyMemberActionGui getPartyMemberActionGui() {
        return partyMemberActionGui;
    }

    public MailGuiEventHandler getMailGuiEventHandler() {
        return mailGuiEventHandler;
    }

    /**
     * 管理者用アイテム一覧 GUI のイベントハンドラを返します。
     *
     * @return 管理者用アイテム一覧 GUI イベントハンドラ
     */
    public ItemAdminGuiEventHandler getItemAdminGuiEventHandler() {
        return itemAdminGuiEventHandler;
    }

    public AdventureRecordGuiEventHandler getAdventureRecordGuiEventHandler() {
        return adventureRecordGuiEventHandler;
    }

    public AdventureRecordService getAdventureRecordService() {
        return adventureRecordService;
    }

    public ShopService getShopService() {
        return shopService;
    }

    public ShopGuiEventHandler getShopGuiEventHandler() {
        return shopGuiEventHandler;
    }

    /**
     * トレードサービスを取得する。
     *
     * @return トレードサービス
     */
    public TradeService getTradeService() {
        return tradeService;
    }
}
