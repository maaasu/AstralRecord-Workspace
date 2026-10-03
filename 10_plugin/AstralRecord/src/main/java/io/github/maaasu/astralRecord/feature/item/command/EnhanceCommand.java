package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemEnhanceService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Predicate;

/** 管理者が指定した所持装備の強化値を設定・加算・減算するコマンドです。 */
public final class EnhanceCommand extends AstCommand {
    private final ItemEnhanceService enhanceService;
    private final InventoryService inventoryService;

    /**
     * 強化管理コマンドを初期化します。
     *
     * @param enhanceService 強化対象の解決・値変更サービス
     * @param inventoryService 変更後の装備表示サービス
     */
    public EnhanceCommand(@NotNull ItemEnhanceService enhanceService, @NotNull InventoryService inventoryService) {
        super("enhance", "所持装備の強化値を設定します。",
            "/enhance <itemName> <level|+delta|-delta> [player]", false, UserPermission.ADMIN.getValue());
        this.enhanceService = enhanceService;
        this.inventoryService = inventoryService;
    }

    /**
     * 装備名と値を解決し、対象の所持装備の強化値を変更します。
     *
     * @param sender 管理者またはコンソール
     * @param args 装備指定、値指定、省略可能な末尾プレイヤー名
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        Arguments input = parseArguments(args, name -> Bukkit.getPlayerExact(name) != null);
        if (input == null) {
            sendUsage(sender);
            return;
        }
        AstPlayer target = resolveTarget(sender, input.targetName());
        if (target == null) {
            return;
        }
        if (!AccountModeGuard.isGameplayPlayer(target)) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5065);
            return;
        }

        final ItemEnhanceService.ChangeResult result;
        try {
            result = enhanceService.setLevel(target, input.selection(), input.levelInput());
        } catch (InventorySaveCoordinator.ExternalOperationPendingException pending) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5232);
            return;
        }
        if (result == null) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5231);
            return;
        }
        inventoryService.refreshEquipmentInstanceDisplay(target, result.instance());
        inventoryService.refreshManagedInventoryUi(target);
        AstralRecord.getInstance().getStatusService().refreshStatus(target);
        PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5230,
            result.displayName(), result.previousLevel(), result.instance().getEnhanceLevel(), result.maxLevel());
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(target, PlayerMsgId.P_5230,
                result.displayName(), result.previousLevel(), result.instance().getEnhanceLevel(), result.maxLevel());
        }
    }

    /**
     * 空白を含む装備指定を保持し、末尾の値指定と省略可能なプレイヤーを切り分けます。
     *
     * @param args コマンド引数
     * @return 解釈済み入力。装備指定や十進整数がない場合は null
     */
    static @Nullable Arguments parseArguments(@NotNull String[] args) {
        return parseArguments(args, ignored -> false);
    }

    /**
     * オンラインの対象名を優先して、末尾の数値指定とプレイヤー指定を切り分けます。
     * 数字だけの対象名も、直前が強化値であればプレイヤー指定として扱います。
     *
     * @param args コマンド引数
     * @param isOnlinePlayer 名前がオンラインプレイヤーと一致する場合に true を返す判定
     * @return 解釈済み入力。装備指定や十進整数がない場合は null
     */
    static @Nullable Arguments parseArguments(@NotNull String[] args, @NotNull Predicate<String> isOnlinePlayer) {
        if (args.length < 2) {
            return null;
        }
        int levelIndex = args.length - 1;
        String targetName = null;
        boolean numericTarget = levelIndex >= 2 && isLevelInput(args[levelIndex - 1])
            && isOnlinePlayer.test(args[levelIndex]);
        if (!isLevelInput(args[levelIndex]) || numericTarget) {
            targetName = args[levelIndex];
            levelIndex--;
        }
        if (levelIndex < 1 || !isLevelInput(args[levelIndex]) || targetName != null && targetName.isBlank()) {
            return null;
        }
        String selection = String.join(" ", Arrays.copyOf(args, levelIndex)).strip();
        return selection.isEmpty() ? null : new Arguments(selection, args[levelIndex], targetName);
    }

    /**
     * 十進整数の設定値または符号付き差分かを判定します。
     *
     * @param input 強化値の入力候補
     * @return 数字列、または正負の符号と数字列であれば true
     */
    private static boolean isLevelInput(@NotNull String input) {
        return input.matches("[+-]?[0-9]+");
    }

    /**
     * 末尾プレイヤー指定をオンラインの読み込み済み状態へ解決します。
     *
     * @param sender コマンド送信者
     * @param name プレイヤー名。省略時は実行者自身
     * @return 対象状態。解決不能なら共通エラーを送信して null
     */
    private @Nullable AstPlayer resolveTarget(@NotNull CommandSender sender, @Nullable String name) {
        if (name != null) {
            Player player = Bukkit.getPlayerExact(name);
            AstPlayer target = player == null ? null : AstPlayerCache.get(player);
            if (target == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), name));
            }
            return target;
        }
        AstPlayer target = getAstPlayer(sender);
        if (target == null) {
            if (sender instanceof Player player) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), player.getName()));
            } else {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            }
        }
        return target;
    }

    /** 装備指定・値指定・末尾プレイヤーを保持する解釈結果です。 */
    record Arguments(@NotNull String selection, @NotNull String levelInput, @Nullable String targetName) {
    }
}
