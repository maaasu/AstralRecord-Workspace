package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.core.CommandRegister;
import io.github.maaasu.astralRecord.core.event.EventManager;
import io.github.maaasu.astralRecord.feature.adventurerecord.event.AdventureRecordGuiEventHandler;
import io.github.maaasu.astralRecord.feature.adventurerecord.gui.AdventureRecordGui;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.combat.event.CombatDamageEventHandler;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.buff.service.BuffAcquisitionDisplayService;
import io.github.maaasu.astralRecord.shared.gui.debug.PagingDebugGui;
import io.github.maaasu.astralRecord.shared.gui.debug.event.PagingDebugGuiEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.item.event.ItemInteractionBlockEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemWeaponAttackEventHandler;
import io.github.maaasu.astralRecord.feature.item.executor.WeaponAttackSkillExecutor;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseEffectService;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
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
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerDetailGui;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListGui;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.event.MobInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.mob.service.MobAiService;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.spawner.event.MobSpawnerBlockEventHandler;
import io.github.maaasu.astralRecord.feature.mob.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.mob.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.mob.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.party.event.PartyGuiEventHandler;
import io.github.maaasu.astralRecord.feature.party.event.PartyQuitEventHandler;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyMemberActionGui;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerModeEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerInputEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerSneakEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerVanillaDamageBlockEventHandler;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
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
import io.github.maaasu.astralRecord.feature.skill.event.SkillActionRingEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.executor.FireBoostSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.status.service.StatusRegenTask;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.status.event.PlayerHeldItemStatusEventHandler;
import io.github.maaasu.astralRecord.feature.user.event.UserLoginEventHandler;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.world.config.PluginJoinSpawnWorldConfig;
import io.github.maaasu.astralRecord.feature.world.event.WorldNaturalSpawnBlockEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldJoinSpawnEventHandler;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
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
    private ResourcePackService resourcePackService;
    private MenuView menuView;
    private MenuOpenEventHandler menuOpenEventHandler;
    private PlayerListGui playerListGui;
    private PlayerDetailGui playerDetailGui;
    private PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler;
    private PagingDebugGui pagingDebugGui;
    private MobService mobService;
    private MobSpawnerService mobSpawnerService;
    private MobAiService mobAiService;
    private MobCombatService mobCombatService;
    private MobDropPresentationService mobDropPresentationService;
    private EventManager eventManager;
    private ParticleDisplayService particleDisplayService;
    private DisplayTextService displayTextService;
    private OverheadDisplayService overheadDisplayService;
    private PlayerSettingService playerSettingService;
    private PlayerSettingGui playerSettingGui;
    private SkillService skillService;
    private SkillActionRingService skillActionRingService;
    private SkillBindPresetService skillBindPresetService;
    private SkillOwnershipService skillOwnershipService;
    private SkillBindGui skillBindGui;
    private SkillBindGuiEventHandler skillBindGuiEventHandler;
    private DamageService damageService;
    private BundleUseService bundleUseService;
    private BundleUseEffectService bundleUseEffectService;
    private ItemDropAnimationService itemDropAnimationService;
    private BuffAcquisitionDisplayService buffAcquisitionDisplayService;
    private PotionUseService potionUseService;
    private PlayerClassService playerClassService;
    private ItemWeaponAttackService itemWeaponAttackService;
    private WorldService worldService;
    private WorldSpawnParticleTask worldSpawnParticleTask;
    private PartyService partyService;
    private PartyGui partyGui;
    private PartyMemberActionGui partyMemberActionGui;
    private LoginBonusService loginBonusService;
    private MailService mailService;
    private MailGuiEventHandler mailGuiEventHandler;
    private AdventureRecordService adventureRecordService;
    private AdventureRecordGuiEventHandler adventureRecordGuiEventHandler;
    private ShopService shopService;
    private ShopGui shopGui;
    private ShopGuiEventHandler shopGuiEventHandler;
    private String joinSpawnWorldId;

    @Override
    public void onLoad() {
        instance = this;
        itemService = new ItemService();
        lootService = new LootService();
        itemStackFactory = new ItemStackFactory(lootService, itemService);
        mobService = new MobService(this, new MobRepository());
        mobSpawnerService = new MobSpawnerService(
                this,
                mobService,
                new MobSpawnerDefinitionRepository(),
                new MobSpawnerLocationRepository(this)
        );
        worldService = new WorldService(new WorldRepository());
        joinSpawnWorldId = PluginJoinSpawnWorldConfig.load(this);
        // CommandManagerの初期化はPaper Lifecycle APIの制約上、onLoad()内で行う
        // コマンドをここで登録し、initialize()を呼び出す
        new CommandRegister(itemService, itemStackFactory, mobService, mobSpawnerService, worldService);
        CommandManager.getInstance().initialize(this);
    }

    @Override
    public void onEnable() {
        // AuditLoggerの初期化
        AuditLogger.initDefault();

        // すべての LogEntry 実装クラスを自動スキャンして AuditLogger を初期化
        AuditLoggerRegistry.init("io.github.maaasu.astralRecord");

        if (!setupInfrastructure()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. featureの初期化
        setupFeature();

        // 4. 機能層（イベント・コマンド）の登録
        registerPluginFeatures();
    }

    @Override
    public void onDisable() {
        if (inventoryAutoSaveTask != null) {
            inventoryAutoSaveTask.stop();
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
        if (displayTextService != null) {
            displayTextService.stop();
        }
        if (mobAiService != null) {
            mobAiService.stop();
        }
        if (mobSpawnerService != null) {
            mobSpawnerService.stop();
        }
        if (worldSpawnParticleTask != null) {
            worldSpawnParticleTask.stop();
        }
        if (partyService != null) {
            partyService.clearAll();
        }
        if (skillActionRingService != null) {
            skillActionRingService.stop();
        }
        if (skillService != null) {
            skillService.stop();
        }
        if (mobService != null) {
            mobService.destroyAll();
        }
        // AuditLoggerのシャットダウン
        AuditLogger.shutdownDefault();
        AuditLoggerRegistry.shutdownAll();
        // コマンドマネージャーのシャットダウン
        CommandManager.getInstance().shutdown();
        getServer().getScheduler().cancelTasks(this);
        SqlServerManager.getInstance().shutdown();
    }

    /**
     * 設定ファイル、データベース等の基盤部分をセットアップ
     * @return 成功した場合はtrue
     */
    private boolean setupInfrastructure() {
        try {
            //設定ファイルを初期化
            ConfigManager.getInstance().initialize();


            // DB 初期化
            SqlServerManager.getInstance().initialize();

            // フォルダ型データベース初期化
            FileDatabaseManager.getInstance();

            // YamlDB設定の初期化
            YamlDbConfigUtil.INSTANCE.reload();

            // AstralRecord API 疎通確認（非同期）
            ApiHealthChecker.checkAsync();

            return true;
        }catch (Exception e) {
            Logger.log(LogId.E_900, e);
            return false;
        }
    }

    /**
     * プラグインの機能をセットアップします。
     */
    private void setupFeature() {
        // account
        var accountRepository = new AccountRepository();
        accountService = new AccountService(accountRepository);

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
        inventoryAutoSaveTask = new InventoryAutoSaveTask(inventoryPersistence, inventoryStateRegistry);
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
        displayTextService = new DisplayTextService();
        bundleUseEffectService = new BundleUseEffectService();
        itemDropAnimationService = new ItemDropAnimationService(this, itemStackFactory);
        bundleUseService = new BundleUseService(
            this,
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
        buffAcquisitionDisplayService = new BuffAcquisitionDisplayService(displayTextService);
        potionUseService = new PotionUseService(inventoryService, statusService, buffAcquisitionDisplayService);
        statusRegenTask = new StatusRegenTask(statusService);
        playerHudService = new PlayerHudService(statusService, playerClassService);
        overheadDisplayService = new OverheadDisplayService(displayTextService, statusService, mobService);

        // combat
        mobDropPresentationService = new MobDropPresentationService(
                this,
                itemService,
                inventoryService,
                itemDropAnimationService
        );
        mobCombatService = new MobCombatService(
                mobService,
                new MobKnockbackService(mobService),
                new MobDropService(),
                mobDropPresentationService,
                partyService,
                adventureRecordService
        );
        damageService = new DamageService(statusService, mobService, mobCombatService, displayTextService, playerSettingService);

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

        // resource pack
        resourcePackService = new ResourcePackService(ConfigProperties.getInstance());

        // menu
        menuView = new MenuView(this);
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
        shopGui = new ShopGui(this, shopService, itemStackFactory);
        shopGuiEventHandler = new ShopGuiEventHandler(shopGui, shopService, menuView);

        // skill
        skillService = new SkillService(new SkillRepository(), new SkillRegistry(), this);
        skillBindPresetService = new SkillBindPresetService(new SkillBindPresetRepository());
        skillService.registerExecutor(new FireBoostSkillExecutor(particleDisplayService));
        skillService.registerExecutor(new WeaponAttackSkillExecutor(particleDisplayService, damageService));
        skillService.registerBuiltInDefinitions(BuiltInWeaponAttackDefinitions.definitions());
        itemStackFactory.setSkillService(skillService);
        skillOwnershipService = new SkillOwnershipService(playerClassService, inventoryService, itemService);
        skillService.setOwnershipService(skillOwnershipService);
        skillActionRingService = new SkillActionRingService(this, skillBindPresetService, skillService, skillOwnershipService);
        skillBindGui = new SkillBindGui(this);
        itemWeaponAttackService = new ItemWeaponAttackService(itemService, skillService);

        // item, loot, skill, class（マスタデータ非同期ロード）
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            lootService.loadAll();
            itemService.loadAll();
            skillService.reloadDefinitions();
            playerClassService.loadAll();
        });

        // mob
        mobService.loadAll();
        mobSpawnerService.loadAll();
        mobAiService = new MobAiService(mobService);
        mobAiService.start();

        // world
        worldService.loadAll();
        worldSpawnParticleTask = new WorldSpawnParticleTask(this, worldService, particleDisplayService);

        // item: ProtocolLib パケットアダプタ（icon 差し替え）登録
        ItemStackPacketAdapter packetAdapter = new ItemStackPacketAdapter(this);
        packetAdapter.register();

        // event manager
        eventManager = new EventManager(this);
    }

    /**
     * イベントやコマンドなどの機能を登録
     */
    private void registerPluginFeatures() {
        eventManager.registerHandler(
            new UserLoginEventHandler(userService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerJoinEventHandler(this, playerService, loginBonusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new LoginBonusGuiEventHandler(loginBonusService.getGui()),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldJoinSpawnEventHandler(this, joinSpawnWorldId, worldService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldNaturalSpawnBlockEventHandler(worldService, mobService),
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
            new ItemInteractionBlockEventHandler(itemService, bundleUseService, potionUseService),
            getServer().getPluginManager()
        );
        menuOpenEventHandler = new MenuOpenEventHandler(this, menuView, inventoryService, currencyService, statusService);
        eventManager.registerHandler(menuOpenEventHandler, getServer().getPluginManager());
        mailGuiEventHandler = new MailGuiEventHandler(new MailGuiView(this), mailService, menuView, inventoryService);
        eventManager.registerHandler(
            mailGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            shopGuiEventHandler,
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
            new InventoryEquipmentGuiEventHandler(menuView, inventoryService, currencyService, statusService),
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
        skillBindGuiEventHandler = new SkillBindGuiEventHandler(
            this,
            skillBindGui,
            skillService,
            skillBindPresetService,
            skillOwnershipService,
            inventoryService,
            menuView
        );
        eventManager.registerHandler(
            skillBindGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new SkillActionRingEventHandler(skillActionRingService, itemService),
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
            new CombatDamageEventHandler(damageService, skillActionRingService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MobSpawnerBlockEventHandler(mobSpawnerService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MobInteractionEventHandler(mobService, shopGuiEventHandler, menuView),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ItemWeaponAttackEventHandler(itemWeaponAttackService, skillActionRingService),
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
        worldSpawnParticleTask.start();
        mobSpawnerService.start();
        // インベントリオートセーブ (60s) を開始
        inventoryAutoSaveTask.start(this, InventoryAutoSaveTask.DEFAULT_INTERVAL_TICKS);
    }
    /**
     * AstralSaga のインスタンスを取得します。
     * 他のクラスからインスタンスを取得する必要がある場合に使用します。
     */
    public static AstralRecord getInstance() {
        return instance;
    }

    /**
     * {@link ItemStackFactory} のインスタンスを取得します。
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
     * 通貨サービスを取得します。
     *
     * @return 通貨サービス
     */
    public CurrencyService getCurrencyService() {
        return currencyService;
    }

    /**
     * ログインボーナスサービスを取得します。
     *
     * @return ログインボーナスサービス
     */
    public LoginBonusService getLoginBonusService() {
        return loginBonusService;
    }

    /**
     * メニュー GUI 表示ビューを取得します。
     *
     * @return メニュー GUI 表示ビュー
     */
    public MenuView getMenuView() {
        return menuView;
    }

    public MenuOpenEventHandler getMenuOpenEventHandler() {
        return menuOpenEventHandler;
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
     * ページング確認用のダミー GUI を取得します。
     *
     * @return ページング確認 GUI
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
     * TextDisplay 基盤サービスを返します。
     *
     * @return TextDisplay 基盤サービス
     */
    public DisplayTextService getDisplayTextService() {
        return displayTextService;
    }

    public MobService getMobService() {
        return mobService;
    }

    /**
     * Mob スポナーサービスを取得します。
     *
     * @return Mob スポナーサービス
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
     * スキルサービスを取得します。
     *
     * @return スキルサービス
     */
    public SkillService getSkillService() {
        return skillService;
    }

    /**
     * スキルバインド GUI イベントハンドラを返します。
     *
     * @return スキルバインド GUI イベントハンドラ
     */
    public SkillBindGuiEventHandler getSkillBindGuiEventHandler() {
        return skillBindGuiEventHandler;
    }

    /**
     * 戦闘ダメージサービスを取得します。
     *
     * @return 戦闘ダメージサービス
     */
    public DamageService getDamageService() {
        return damageService;
    }

    /**
     * 職業サービスを取得します。
     *
     * @return 職業サービス
     */
    public PlayerClassService getPlayerClassService() {
        return playerClassService;
    }

    /**
     * WorldMasterData サービスを取得します。
     *
     * @return WorldMasterData サービス
     */
    public WorldService getWorldService() {
        return worldService;
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
}
