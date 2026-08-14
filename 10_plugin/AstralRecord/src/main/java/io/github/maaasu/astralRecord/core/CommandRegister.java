package io.github.maaasu.astralRecord.core;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.command.MasterDataCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountModeCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountModeTabCompleter;
import io.github.maaasu.astralRecord.feature.account.command.AccountTabCompleter;
import io.github.maaasu.astralRecord.feature.account.command.LevelCommand;
import io.github.maaasu.astralRecord.feature.account.command.LevelTabCompleter;
import io.github.maaasu.astralRecord.feature.boss.command.BossCommand;
import io.github.maaasu.astralRecord.feature.dungeon.command.DungeonCommand;
import io.github.maaasu.astralRecord.feature.gathering.command.GatheringCommand;
import io.github.maaasu.astralRecord.feature.gathering.command.GatheringTabCompleter;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryCommand;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryTabCompleter;
import io.github.maaasu.astralRecord.feature.item.command.ItemCommand;
import io.github.maaasu.astralRecord.feature.item.command.ItemTabCompleter;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.market.command.MarketCommand;
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
import io.github.maaasu.astralRecord.feature.particle.command.ParticleCommand;
import io.github.maaasu.astralRecord.feature.particle.command.ParticleTabCompleter;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingCommand;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingTabCompleter;
import io.github.maaasu.astralRecord.feature.player.command.DirectMessageCommand;
import io.github.maaasu.astralRecord.feature.player.command.DirectMessageTabCompleter;
import io.github.maaasu.astralRecord.feature.playerclass.command.ClassCommand;
import io.github.maaasu.astralRecord.feature.playerclass.command.ClassTabCompleter;
import io.github.maaasu.astralRecord.feature.quest.command.QuestCommand;
import io.github.maaasu.astralRecord.feature.skill.command.SkillCommand;
import io.github.maaasu.astralRecord.feature.skilltree.command.SkillTreeCommand;
import io.github.maaasu.astralRecord.feature.skilltree.command.SkillTreeTabCompleter;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.sell.command.SellCommand;
import io.github.maaasu.astralRecord.feature.shop.command.ShopCommand;
import io.github.maaasu.astralRecord.feature.shop.command.ShopTabCompleter;
import io.github.maaasu.astralRecord.feature.storage.command.StorageCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusBuffCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusBuffTabCompleter;
import io.github.maaasu.astralRecord.feature.status.command.StatusTabCompleter;
import io.github.maaasu.astralRecord.feature.textdisplay.command.TextDisplayCommand;
import io.github.maaasu.astralRecord.feature.textdisplay.command.TextDisplayTabCompleter;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.teleporter.command.TeleporterCommand;
import io.github.maaasu.astralRecord.feature.teleporter.command.TeleporterTabCompleter;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.trade.command.TradeCommand;
import io.github.maaasu.astralRecord.feature.trainingdummy.command.TrainingDummyCommand;
import io.github.maaasu.astralRecord.feature.trainingdummy.command.TrainingDummyTabCompleter;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.feature.trade.command.TradeTabCompleter;
import io.github.maaasu.astralRecord.feature.user.command.UserCommand;
import io.github.maaasu.astralRecord.feature.user.command.UserPermissionCommand;
import io.github.maaasu.astralRecord.feature.user.command.UserPermissionTabCompleter;
import io.github.maaasu.astralRecord.feature.user.command.UserTabCompleter;
import io.github.maaasu.astralRecord.feature.webauth.command.WebAuthCommand;
import io.github.maaasu.astralRecord.feature.webauth.repository.WebAuthRepository;
import io.github.maaasu.astralRecord.feature.webauth.service.WebAuthService;
import io.github.maaasu.astralRecord.feature.world.command.WorldCommand;
import io.github.maaasu.astralRecord.feature.world.command.WorldTabCompleter;
import io.github.maaasu.astralRecord.feature.world.command.WorldTeleportCommand;
import io.github.maaasu.astralRecord.feature.world.command.WorldTeleportTabCompleter;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.test.SkillTreeSpawnCheckCommand;
import io.github.maaasu.astralRecord.test.SkillTreeSpawnCheckTabCompleter;
import io.github.maaasu.astralRecord.test.TestCommand;
import io.github.maaasu.astralRecord.test.TestTabCompleter;

import java.util.function.Supplier;

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
    private final GatheringService gatheringService;
    private final GatheringSpawnerService gatheringSpawnerService;
    private final TextDisplayPlacementService textDisplayPlacementService;
    private final TeleporterService teleporterService;
    private final TrainingDummyService trainingDummyService;
    private final TrainingDummyGui trainingDummyGui;
    private final Supplier<ParticleDisplayService> particleDisplayServiceSupplier;

    public CommandRegister(
            ItemService itemService,
            ItemStackFactory itemStackFactory,
            MobService mobService,
            MobSpawnerService spawnerService,
            NpcPlacementService npcPlacementService,
            WorldService worldService,
            SkillTreeService skillTreeService,
            GatheringService gatheringService,
            GatheringSpawnerService gatheringSpawnerService,
            TextDisplayPlacementService textDisplayPlacementService,
            TeleporterService teleporterService,
            TrainingDummyService trainingDummyService,
            TrainingDummyGui trainingDummyGui,
            Supplier<ParticleDisplayService> particleDisplayServiceSupplier
    ) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.mobService = mobService;
        this.spawnerService = spawnerService;
        this.npcPlacementService = npcPlacementService;
        this.worldService = worldService;
        this.skillTreeService = skillTreeService;
        this.gatheringService = gatheringService;
        this.gatheringSpawnerService = gatheringSpawnerService;
        this.textDisplayPlacementService = textDisplayPlacementService;
        this.teleporterService = teleporterService;
        this.trainingDummyService = trainingDummyService;
        this.trainingDummyGui = trainingDummyGui;
        this.particleDisplayServiceSupplier = particleDisplayServiceSupplier;
        registerCommand();
    }

    public final void registerCommand() {
        CommandManager cm = CommandManager.getInstance();

        cm.registerCommand("status", new StatusCommand(), new StatusTabCompleter());
        cm.registerCommand("statusbuff", new StatusBuffCommand(), new StatusBuffTabCompleter());
        cm.registerCommand("inventory", new InventoryCommand(), new InventoryTabCompleter());
        cm.registerCommand("menu", new MenuCommand());
        cm.registerCommand("player", new PlayerInfoCommand(), new PlayerInfoTabCompleter());
        cm.registerCommand("trash", new TrashCommand());
        cm.registerCommand("sell", new SellCommand());
        cm.registerCommand("storage", new StorageCommand());
        cm.registerCommand("item", new ItemCommand(itemService), new ItemTabCompleter(itemService));
        cm.registerCommand("mob", new MobCommand(mobService, spawnerService, npcPlacementService), new MobTabCompleter(mobService, spawnerService, npcPlacementService));
        cm.registerCommand("dummy", new TrainingDummyCommand(trainingDummyService, trainingDummyGui), new TrainingDummyTabCompleter(trainingDummyService));
        cm.registerCommand("gathering", new GatheringCommand(gatheringService, gatheringSpawnerService), new GatheringTabCompleter(gatheringService, gatheringSpawnerService));
        WorldCommand worldCommand = new WorldCommand(worldService);
        cm.registerCommand("world", worldCommand, new WorldTabCompleter(worldService));
        cm.registerCommand("wtp", new WorldTeleportCommand(worldCommand), new WorldTeleportTabCompleter(worldService));
        cm.registerCommand("boss", new BossCommand());
        cm.registerCommand("dungeon", new DungeonCommand());
        cm.registerCommand("user", new UserCommand(), new UserTabCompleter());
        cm.registerCommand("uperm", new UserPermissionCommand("uperm", "/uperm <permission> [<player|uuid>]"), new UserPermissionTabCompleter());
        cm.registerCommand("account", new AccountCommand(), new AccountTabCompleter());
        cm.registerCommand("level", new LevelCommand(), new LevelTabCompleter());
        cm.registerCommand("am", new AccountModeCommand("am", "/am <mode> [<player|accountUuid>]"), new AccountModeTabCompleter());
        cm.registerCommand("setting", new PlayerSettingCommand(), new PlayerSettingTabCompleter());
        cm.registerCommand("message", new DirectMessageCommand(), new DirectMessageTabCompleter());
        cm.registerCommand("class", new ClassCommand(), new ClassTabCompleter());
        cm.registerCommand("skill", new SkillCommand());
        cm.registerCommand("skilltree", new SkillTreeCommand(skillTreeService), new SkillTreeTabCompleter());
        cm.registerCommand("party", new PartyCommand(), new PartyTabCompleter());
        ParticleCommand particleCommand = new ParticleCommand(AstralRecord.getInstance(), particleDisplayServiceSupplier);
        ParticleTabCompleter particleTabCompleter = new ParticleTabCompleter();
        cm.registerCommand("particle", particleCommand, particleTabCompleter);
        cm.registerCommand("p", particleCommand, particleTabCompleter);
        cm.registerCommand("trade", new TradeCommand(), new TradeTabCompleter());
        cm.registerCommand("shop", new ShopCommand(), new ShopTabCompleter());
        cm.registerCommand("market", new MarketCommand());
        cm.registerCommand("quest", new QuestCommand());
        cm.registerCommand("web", new WebAuthCommand(new WebAuthService(new WebAuthRepository())));
        cm.registerCommand("textdisplay", new TextDisplayCommand(textDisplayPlacementService), new TextDisplayTabCompleter(textDisplayPlacementService));
        cm.registerCommand("teleporter", new TeleporterCommand(teleporterService), new TeleporterTabCompleter(teleporterService));
        cm.registerCommand("test", new TestCommand(AstralRecord.getInstance()), new TestTabCompleter());
        cm.registerCommand("testskilltree", new SkillTreeSpawnCheckCommand(), new SkillTreeSpawnCheckTabCompleter());
        cm.registerCommand("masterdata", new MasterDataCommand(AstralRecord.getInstance()));
    }
}
