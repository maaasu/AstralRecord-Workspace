package io.github.maaasu.astralRecord.core;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.command.ReloadCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountTabCompleter;
import io.github.maaasu.astralRecord.feature.gathering.command.GatheringCommand;
import io.github.maaasu.astralRecord.feature.gathering.command.GatheringTabCompleter;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.shared.gui.debug.command.PagingDebugCommand;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryCommand;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryTabCompleter;
import io.github.maaasu.astralRecord.feature.item.command.ItemCommand;
import io.github.maaasu.astralRecord.feature.item.command.ItemTabCompleter;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.menu.command.EnhanceCommand;
import io.github.maaasu.astralRecord.feature.loginbonus.command.LoginBonusCommand;
import io.github.maaasu.astralRecord.feature.menu.command.MenuCommand;
import io.github.maaasu.astralRecord.feature.menu.command.PlayerInfoCommand;
import io.github.maaasu.astralRecord.feature.menu.command.PlayerInfoTabCompleter;
import io.github.maaasu.astralRecord.feature.menu.command.TrashCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobTabCompleter;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.party.command.PartyCommand;
import io.github.maaasu.astralRecord.feature.party.command.PartyTabCompleter;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingCommand;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingTabCompleter;
import io.github.maaasu.astralRecord.feature.player.command.DirectMessageCommand;
import io.github.maaasu.astralRecord.feature.player.command.DirectMessageTabCompleter;
import io.github.maaasu.astralRecord.feature.playerclass.command.ClassCommand;
import io.github.maaasu.astralRecord.feature.playerclass.command.ClassTabCompleter;
import io.github.maaasu.astralRecord.feature.skill.command.SkillCommand;
import io.github.maaasu.astralRecord.feature.skilltree.command.SkillTreeCommand;
import io.github.maaasu.astralRecord.feature.skilltree.command.SkillTreeTabCompleter;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.sell.command.SellCommand;
import io.github.maaasu.astralRecord.feature.shop.command.ShopCommand;
import io.github.maaasu.astralRecord.feature.shop.command.ShopTabCompleter;
import io.github.maaasu.astralRecord.feature.storage.command.StorageCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusTabCompleter;
import io.github.maaasu.astralRecord.feature.trade.command.TradeCommand;
import io.github.maaasu.astralRecord.feature.trade.command.TradeTabCompleter;
import io.github.maaasu.astralRecord.feature.user.command.UserCommand;
import io.github.maaasu.astralRecord.feature.user.command.UserTabCompleter;
import io.github.maaasu.astralRecord.feature.waystone.command.WaystoneCommand;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneService;
import io.github.maaasu.astralRecord.feature.webauth.command.WebAuthCommand;
import io.github.maaasu.astralRecord.feature.webauth.repository.WebAuthRepository;
import io.github.maaasu.astralRecord.feature.webauth.service.WebAuthService;
import io.github.maaasu.astralRecord.feature.world.command.WorldCommand;
import io.github.maaasu.astralRecord.feature.world.command.WorldTabCompleter;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;
import io.github.maaasu.astralRecord.test.SkillTreeSpawnCheckCommand;
import io.github.maaasu.astralRecord.test.SkillTreeSpawnCheckTabCompleter;
import io.github.maaasu.astralRecord.test.TestCommand;
import io.github.maaasu.astralRecord.test.TestTabCompleter;

/**
 * Registers plugin commands before {@link CommandManager#initialize(AstralRecord)} runs.
 */
public class CommandRegister {
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final MobService mobService;
    private final MobSpawnerService spawnerService;
    private final NpcPlacementService npcPlacementService;
    private final WorldService worldService;
    private final SkillTreeService skillTreeService;
    private final WaystoneService waystoneService;
    private final GatheringService gatheringService;
    private final GatheringSpawnerService gatheringSpawnerService;

    public CommandRegister(
            ItemService itemService,
            ItemStackFactory itemStackFactory,
            MobService mobService,
            MobSpawnerService spawnerService,
            NpcPlacementService npcPlacementService,
            WorldService worldService,
            SkillTreeService skillTreeService,
            WaystoneService waystoneService,
            GatheringService gatheringService,
            GatheringSpawnerService gatheringSpawnerService
    ) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.mobService = mobService;
        this.spawnerService = spawnerService;
        this.npcPlacementService = npcPlacementService;
        this.worldService = worldService;
        this.skillTreeService = skillTreeService;
        this.waystoneService = waystoneService;
        this.gatheringService = gatheringService;
        this.gatheringSpawnerService = gatheringSpawnerService;
        registerCommand();
    }

    public final void registerCommand() {
        CommandManager cm = CommandManager.getInstance();

        cm.registerCommand("status", new StatusCommand(), new StatusTabCompleter());
        cm.registerCommand("inventory", new InventoryCommand(), new InventoryTabCompleter());
        cm.registerCommand("menu", new MenuCommand());
        cm.registerCommand("enhance", new EnhanceCommand());
        cm.registerCommand("loginbonus", new LoginBonusCommand());
        cm.registerCommand("player", new PlayerInfoCommand(), new PlayerInfoTabCompleter());
        cm.registerCommand("trash", new TrashCommand());
        cm.registerCommand("sell", new SellCommand());
        cm.registerCommand("storage", new StorageCommand());
        cm.registerCommand("pagingdummy", new PagingDebugCommand());
        cm.registerCommand("item", new ItemCommand(itemService), new ItemTabCompleter(itemService));
        cm.registerCommand("mob", new MobCommand(mobService, spawnerService, npcPlacementService), new MobTabCompleter(mobService, spawnerService));
        cm.registerCommand("gathering", new GatheringCommand(gatheringService, gatheringSpawnerService), new GatheringTabCompleter(gatheringService, gatheringSpawnerService));
        cm.registerCommand("world", new WorldCommand(worldService), new WorldTabCompleter(worldService));
        cm.registerCommand("user", new UserCommand(), new UserTabCompleter());
        cm.registerCommand("account", new AccountCommand(), new AccountTabCompleter());
        cm.registerCommand("setting", new PlayerSettingCommand(), new PlayerSettingTabCompleter());
        cm.registerCommand("message", new DirectMessageCommand(), new DirectMessageTabCompleter());
        cm.registerCommand("class", new ClassCommand(), new ClassTabCompleter());
        cm.registerCommand("skill", new SkillCommand());
        cm.registerCommand("skilltree", new SkillTreeCommand(skillTreeService), new SkillTreeTabCompleter(skillTreeService));
        cm.registerCommand("waystone", new WaystoneCommand(waystoneService));
        cm.registerCommand("party", new PartyCommand(), new PartyTabCompleter());
        cm.registerCommand("trade", new TradeCommand(), new TradeTabCompleter());
        cm.registerCommand("shop", new ShopCommand(), new ShopTabCompleter());
        cm.registerCommand("web", new WebAuthCommand(new WebAuthService(new WebAuthRepository())));
        cm.registerCommand("test", new TestCommand(AstralRecord.getInstance()), new TestTabCompleter());
        cm.registerCommand("testskilltree", new SkillTreeSpawnCheckCommand(), new SkillTreeSpawnCheckTabCompleter());
        cm.registerCommand("astreload", new ReloadCommand());
    }
}
