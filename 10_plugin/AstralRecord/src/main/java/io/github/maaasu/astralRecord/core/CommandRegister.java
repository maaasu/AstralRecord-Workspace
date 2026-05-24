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
import io.github.maaasu.astralRecord.feature.menu.command.MenuCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobCommand;
import io.github.maaasu.astralRecord.feature.mob.command.MobTabCompleter;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.temp.command.TempCommand;
import io.github.maaasu.astralRecord.temp.command.TempTabCompleter;
import io.github.maaasu.astralRecord.temp.command.TestCommand;
import io.github.maaasu.astralRecord.temp.command.TestTabCompleter;
import io.github.maaasu.astralRecord.feature.status.command.StatusCommand;
import io.github.maaasu.astralRecord.feature.status.command.StatusTabCompleter;
import io.github.maaasu.astralRecord.feature.user.command.UserCommand;
import io.github.maaasu.astralRecord.feature.user.command.UserTabCompleter;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;

/**
 * Registers plugin commands before {@link CommandManager#initialize(AstralRecord)} runs.
 */
public class CommandRegister {
    private final ItemService itemService;
    private final MobService mobService;

    public CommandRegister(ItemService itemService, ItemStackFactory itemStackFactory, MobService mobService) {
        this.itemService = itemService;
        this.mobService = mobService;
        registerCommand();
    }

    public final void registerCommand() {
        CommandManager cm = CommandManager.getInstance();

        cm.registerCommand("test", new TestCommand(), new TestTabCompleter());
        cm.registerCommand("temp", new TempCommand(), new TempTabCompleter());
        cm.registerCommand("status", new StatusCommand(), new StatusTabCompleter());
        cm.registerCommand("inventory", new InventoryCommand(), new InventoryTabCompleter());
        cm.registerCommand("menu", new MenuCommand());
        cm.registerCommand("pagingdummy", new PagingDebugCommand());
        cm.registerCommand("item", new ItemCommand(itemService), new ItemTabCompleter(itemService));
        cm.registerCommand("mob", new MobCommand(mobService), new MobTabCompleter(mobService));
        cm.registerCommand("user", new UserCommand(), new UserTabCompleter());
        cm.registerCommand("account", new AccountCommand(), new AccountTabCompleter());
        cm.registerCommand("astreload", new ReloadCommand());
    }
}
