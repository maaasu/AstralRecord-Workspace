package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /temp コマンドの実装クラス。
 * <p>
 * permission 99 以上のプレイヤーのみ実行可能な管理者向けコマンドです。
 * <p>
 * 使用方法:
 * <pre>
 * /temp &lt;uiType&gt; - 指定した UI を開く
 * </pre>
 */
public class TempCommand extends AstCommand {

    /** このコマンドの実行に必要な最低権限レベル */
    private static final int REQUIRED_PERMISSION = 99;

    static final List<String> SUPPORTED_UI_TYPES = List.of(
        "chest",
        "double_chest",
        "hopper",
        "dispenser",
        "dropper",
        "furnace",
        "blast_furnace",
        "smoker",
        "brewing",
        "anvil",
        "enchanting",
        "workbench"
    );

    /**
     * TempCommand を初期化します。
     */
    public TempCommand() {
        super(
            "temp",
            "管理者向けテンポラリコマンド",
            "/temp <" + String.join("|", SUPPORTED_UI_TYPES) + ">",
            true,
            REQUIRED_PERMISSION
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 1) {
            sendUsage(player.getBukkit());
            return;
        }

        Inventory inventory = createInventory(args[0]);
        if (inventory == null) {
            sendUsage(player.getBukkit());
            return;
        }

        player.getBukkit().openInventory(inventory);
    }

    protected Inventory createInventory(@NotNull String uiType) {
        String normalized = uiType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "chest" -> Bukkit.createInventory(null, 27);
            case "double_chest" -> Bukkit.createInventory(null, 54);
            case "hopper" -> Bukkit.createInventory(null, InventoryType.HOPPER);
            case "dispenser" -> Bukkit.createInventory(null, InventoryType.DISPENSER);
            case "dropper" -> Bukkit.createInventory(null, InventoryType.DROPPER);
            case "furnace" -> Bukkit.createInventory(null, InventoryType.FURNACE);
            case "blast_furnace" -> Bukkit.createInventory(null, InventoryType.BLAST_FURNACE);
            case "smoker" -> Bukkit.createInventory(null, InventoryType.SMOKER);
            case "brewing" -> Bukkit.createInventory(null, InventoryType.BREWING);
            case "anvil" -> Bukkit.createInventory(null, InventoryType.ANVIL);
            case "enchanting" -> Bukkit.createInventory(null, InventoryType.ENCHANTING);
            case "workbench" -> Bukkit.createInventory(null, InventoryType.WORKBENCH);
            default -> null;
        };
    }
}
