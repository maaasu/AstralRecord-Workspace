package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.core.CommandRegister;
import io.github.maaasu.astralRecord.core.event.EventManager;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.gui.debug.PagingDebugGui;
import io.github.maaasu.astralRecord.feature.gui.debug.event.PagingDebugGuiEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.item.event.ItemInteractionBlockEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.event.InventoryEquipmentGuiEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveTask;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.repository.MenuShortcutRepository;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerInventoryClickEvent;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerModeEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerSneakEventHandler;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackJoinEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackStatusEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.status.service.StatusRegenTask;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.event.UserLoginEventHandler;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
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
    private CurrencyService currencyService;
    private StatusService statusService;
    private StatusRegenTask statusRegenTask;
    private DodgeService dodgeService;
    private PlayerHudService playerHudService;
    private ResourcePackService resourcePackService;
    private MenuView menuView;
    private MenuShortcutRepository menuShortcutRepository;
    private PagingDebugGui pagingDebugGui;
    private MobService mobService;
    private EventManager eventManager;

    @Override
    public void onLoad() {
        instance = this;
        itemService = new ItemService();
        lootService = new LootService();
        itemStackFactory = new ItemStackFactory(lootService);
        mobService = new MobService(this, new MobRepository());
        // CommandManagerの初期化はPaper Lifecycle APIの制約上、onLoad()内で行う
        // コマンドをここで登録し、initialize()を呼び出す
        new CommandRegister(itemService, itemStackFactory, mobService);
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
        if (playerService != null) {
            playerService.saveAllOnlinePlayersAndClear();
        }
        if (inventoryService != null) {
            inventoryService.awaitPendingWrites(5000L);
        }
        if (playerHudService != null) {
            playerHudService.stop();
        }
        if (statusRegenTask != null) {
            statusRegenTask.stop();
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

        // inventory
        var inventoryRepository = new InventoryRepository();
        var equipmentLoadoutRepository = new EquipmentLoadoutRepository();
        inventoryService = new InventoryService(inventoryRepository, equipmentLoadoutRepository, itemService, itemStackFactory);
        currencyService = new CurrencyService(inventoryService);

        // status
        statusService = new StatusService();
        statusRegenTask = new StatusRegenTask(statusService);
        playerHudService = new PlayerHudService(statusService);

        // dodge
        dodgeService = new DodgeService(this, statusService);

        var playerSaveCoordinator = new PlayerSaveCoordinator(
            java.util.List.of(new InventorySaveTask(inventoryService))
        );
        // player
        playerService = new PlayerService(
            userService,
            accountService,
            inventoryService,
            statusService,
            playerSaveCoordinator
        );

        // resource pack
        resourcePackService = new ResourcePackService(ConfigProperties.getInstance());

        // menu
        menuShortcutRepository = new MenuShortcutRepository();
        menuView = new MenuView(this);
        pagingDebugGui = new PagingDebugGui();

        // item & loot
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            lootService.loadAll();
            itemService.loadAll();
        });

        // mob
        mobService.loadAll();

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
            new PlayerJoinEventHandler(this, playerService),
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
            new ItemInteractionBlockEventHandler(),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MenuOpenEventHandler(this, menuView, menuShortcutRepository, inventoryService, currencyService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new InventoryEquipmentGuiEventHandler(menuView, inventoryService, currencyService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PagingDebugGuiEventHandler(pagingDebugGui, menuView),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerModeEventHandler(),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSneakEventHandler(dodgeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerInventoryClickEvent(),
            getServer().getPluginManager()
        );
        playerHudService.start(this);
        statusRegenTask.start(this);
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
     * メニュー GUI 表示ビューを取得します。
     *
     * @return メニュー GUI 表示ビュー
     */
    public MenuView getMenuView() {
        return menuView;
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

    public MobService getMobService() {
        return mobService;
    }
}
