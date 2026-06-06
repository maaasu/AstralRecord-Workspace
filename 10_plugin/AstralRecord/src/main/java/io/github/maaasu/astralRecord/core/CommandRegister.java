package io.github.maaasu.astralRecord.core;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.command.ReloadCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountCommand;
import io.github.maaasu.astralRecord.feature.account.command.AccountTabCompleter;
import io.github.maaasu.astralRecord.shared.gui.debug.command.PagingDebugCommand;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryCommand;
import io.github.maaasu.astralRecord.feature.inventory.command.InventoryTabCompleter;
import io.github.maaasu.astralRecord.feature.item.command.ItemCommand;
import io.github.maaasu.astralRecord.feature.item.command.ItemTabCompleter;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loginbonus.command.LoginBonusCommand;
import io.github.maaasu.astralRecord.feature.menu.command.MenuCommand;
import io.github.maaasu.astralRecord.feature.menu.command.PlayerInfoCommand;
import io.github.maaasu.astralRecord.feature.menu.command.PlayerInfoTabCompleter;
import io.github.maaasu.astralRecord.feature.menu.command.TrashCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobTabCompleter;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.party.command.PartyCommand;
import io.github.maaasu.astralRecord.feature.party.command.PartyTabCompleter;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingCommand;
import io.github.maaasu.astralRecord.feature.playersetting.command.PlayerSettingTabCompleter;
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
import io.github.maaasu.astralRecord.feature.user.command.UserCommand;
import io.github.maaasu.astralRecord.feature.user.command.UserTabCompleter;
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
    private final WorldService worldService;
    private final SkillTreeService skillTreeService;

    public CommandRegister(
            ItemService itemService,
            ItemStackFactory itemStackFactory,
            MobService mobService,
            MobSpawnerService spawnerService,
            WorldService worldService,
            SkillTreeService skillTreeService
    ) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.mobService = mobService;
        this.spawnerService = spawnerService;
        this.worldService = worldService;
        this.skillTreeService = skillTreeService;
        registerCommand();
    }

    public final void registerCommand() {
        CommandManager cm = CommandManager.getInstance();

        cm.registerCommand("status", new StatusCommand(), new StatusTabCompleter());
        cm.registerCommand("inventory", new InventoryCommand(), new InventoryTabCompleter());
        cm.registerCommand("menu", new MenuCommand());
        cm.registerCommand("loginbonus", new LoginBonusCommand());
        cm.registerCommand("player", new PlayerInfoCommand(), new PlayerInfoTabCompleter());
        cm.registerCommand("trash", new TrashCommand());
        cm.registerCommand("sell", new SellCommand());
        cm.registerCommand("storage", new StorageCommand());
        cm.registerCommand("pagingdummy", new PagingDebugCommand());
        cm.registerCommand("item", new ItemCommand(itemService), new ItemTabCompleter(itemService));
        cm.registerCommand("mob", new MobCommand(mobService, spawnerService), new MobTabCompleter(mobService, spawnerService));
        cm.registerCommand("world", new WorldCommand(worldService), new WorldTabCompleter(worldService));
        cm.registerCommand("user", new UserCommand(), new UserTabCompleter());
        cm.registerCommand("account", new AccountCommand(), new AccountTabCompleter());
        cm.registerCommand("setting", new PlayerSettingCommand(), new PlayerSettingTabCompleter());
        cm.registerCommand("class", new ClassCommand(), new ClassTabCompleter());
        cm.registerCommand("skill", new SkillCommand());
        cm.registerCommand("skilltree", new SkillTreeCommand(skillTreeService), new SkillTreeTabCompleter(skillTreeService));
        cm.registerCommand("party", new PartyCommand(), new PartyTabCompleter());
        cm.registerCommand("shop", new ShopCommand(), new ShopTabCompleter());
        cm.registerCommand("test", new TestCommand(AstralRecord.getInstance()), new TestTabCompleter());
        cm.registerCommand("testskilltree", new SkillTreeSpawnCheckCommand(), new SkillTreeSpawnCheckTabCompleter());
        cm.registerCommand("astreload", new ReloadCommand());
    }
}
