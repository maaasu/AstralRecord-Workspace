package io.github.maaasu.astralRecord.feature.builder.service;

import io.github.maaasu.astralRecord.feature.builder.model.*;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ビルダーGUIの状態・権限・確認済み操作を管理します。
 * すべてのメソッドはBukkitメインスレッド上で呼び、編集はWorldEditへ委譲します。
 */
public final class BuilderService {
    public static final String PERMISSION = "astralrecord.builder";
    private final Plugin plugin;
    private final WorldEditGateway gateway = new WorldEditGateway();
    private final Map<UUID, BuilderSettings> settings = new HashMap<>();
    private final Map<UUID, BuilderRequest> pending = new HashMap<>();
    private boolean stopped;

    /** @param plugin 同期タスクとGUI上限設定を提供する本体プラグイン */
    public BuilderService(Plugin plugin) { this.plugin = plugin; }

    /**
     * GUIの利用権限を判定します。WorldEdit操作の権限は別途維持します。
     * @param player 判定対象
     * @return 管理者またはGUI権限保持者ならtrue
     */
    public static boolean hasAccess(Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return player.hasPermission(PERMISSION) || (astPlayer != null && astPlayer.hasAdminPermission());
    }

    /**
     * 利用権限と任意依存の有効状態を確認し、不可の理由を通知します。
     * @param player 利用者
     * @return GUIを利用できる場合true
     */
    public boolean checkAccess(Player player) {
        if (stopped || !player.isOnline()) return false;
        if (!hasAccess(player)) { message(player, PlayerMsgId.P_7700); return false; }
        if (!inspect(player).available()) { message(player, PlayerMsgId.P_7701); return false; }
        return true;
    }

    /**
     * プレイヤー固有のGUI設定を返します。WorldEditのセッション自体は変更しません。
     * @param player 利用者
     * @return GUI設定
     */
    public BuilderSettings settings(Player player) {
        return settings.computeIfAbsent(player.getUniqueId(), ignored -> new BuilderSettings());
    }

    /**
     * WorldEditの現在状態を読みます。API非互換時はreadable=falseです。
     * @param player 利用者
     * @return 状態読取のスナップショット
     */
    public WorldEditSnapshot inspect(Player player) { return gateway.inspect(player); }

    /**
     * 描画用の無通知権限判定です。実行時も同じ条件を確認します。
     * @param player 利用者
     * @param operation 操作
     * @return GUI利用権限・WorldEdit個別権限・コマンド登録が揃えばtrue
     */
    public boolean canRun(Player player, BuilderOperation operation) {
        boolean permission = player.hasPermission(operation.permission)
                || ((operation == BuilderOperation.UNDO || operation == BuilderOperation.REDO)
                    && player.hasPermission(operation.permission + ".self"));
        return !stopped && hasAccess(player) && permission
                && gateway.resolve(command(operation, settings(player))) != null;
    }

    /**
     * 入力とWorldEdit状態を確認して、変更されない確認要求を作ります。
     * @param player 利用者
     * @param operation 操作
     * @return 確認用要求。不備があれば通知しnull
     */
    public @Nullable BuilderRequest prepare(Player player, BuilderOperation operation) {
        if (!checkAccess(player)) return null;
        if (!canRun(player, operation)) { message(player, PlayerMsgId.P_7702); return null; }
        BuilderSettings options = settings(player);
        if (!valid(options) || (operation == BuilderOperation.STACK && options.amount > 16)) {
            message(player, PlayerMsgId.P_7703); return null;
        }
        WorldEditSnapshot snapshot = inspect(player);
        if (!snapshot.readable()) { message(player, PlayerMsgId.P_7704); return null; }
        if (snapshot.transformed()) { message(player, PlayerMsgId.P_7713); return null; }
        if (operation.clipboardRequired && snapshot.multipleClipboards()) {
            message(player, PlayerMsgId.P_7714); return null;
        }
        if (operation.clipboardRequired && snapshot.clipboard() != null && !snapshot.clipboard().safeTransform()) {
            message(player, PlayerMsgId.P_7715); return null;
        }
        if (operation.selectionRequired && (snapshot.selection() == null || !snapshot.selection().cuboid())) {
            message(player, PlayerMsgId.P_7705); return null;
        }
        if (operation.selectionRequired && !snapshot.selection().worldName().equals(player.getWorld().getName())) {
            message(player, PlayerMsgId.P_7706); return null;
        }
        if (operation.clipboardRequired && snapshot.clipboard() == null) {
            message(player, PlayerMsgId.P_7707); return null;
        }
        if (usesPlacement(operation) && snapshot.placement().isBlank()) {
            message(player, PlayerMsgId.P_7704); return null;
        }
        long estimate = estimate(operation, options, snapshot);
        long limit = Math.max(1L, plugin.getConfig().getLong("builder.maxBlocks", 100_000L));
        if (estimate > limit || estimate == 0) {
            message(player, PlayerMsgId.P_7708, estimate, limit); return null;
        }
        var location = player.getLocation();
        BuilderRequest request = new BuilderRequest(UUID.randomUUID(), player.getUniqueId(), player.getWorld().getUID(),
                operation, command(operation, options), snapshot, location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), estimate);
        pending.put(player.getUniqueId(), request);
        return request;
    }

    /**
     * 確認済み要求を一度だけ実行します。遅延中の状態・位置・権限変更は拒否します。
     * @param player 確認したプレイヤー
     * @param request prepareが返した要求
     */
    public void execute(Player player, BuilderRequest request) {
        if (!request.playerId().equals(player.getUniqueId()) || pending.get(player.getUniqueId()) != request) return;
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pending.remove(player.getUniqueId(), request) || !checkAccess(player)) return;
            WorldEditSnapshot now = inspect(player);
            var location = player.getLocation();
            if (!canRun(player, request.operation()) || !now.readable() || now.transformed()
                    || !player.getWorld().getUID().equals(request.worldId())
                    || !Objects.equals(now.mask(), request.snapshot().mask())
                    || (request.operation().selectionRequired && !Objects.equals(now.selection(), request.snapshot().selection()))
                    || (request.operation().clipboardRequired && !Objects.equals(now.clipboard(), request.snapshot().clipboard()))
                    || (request.operation().clipboardRequired && (now.multipleClipboards()
                        || now.clipboard() == null || !now.clipboard().safeTransform()))
                    || (usesPlacement(request.operation()) && (!Objects.equals(now.placement(), request.snapshot().placement())
                        || location.getBlockX() != request.x() || location.getBlockY() != request.y() || location.getBlockZ() != request.z()))) {
                message(player, PlayerMsgId.P_7709);
                reopen(player); return;
            }
            dispatch(player, request.command());
        });
    }

    /**
     * 非破壊の選択・情報操作を実行します。元のWorldEdit権限を確認します。
     * @param player 利用者
     * @param action 固定の選択操作
     */
    public void select(Player player, BuilderSelectionAction action) {
        if (!checkAccess(player)) return;
        if (action.permission != null && !player.hasPermission(action.permission)) {
            message(player, PlayerMsgId.P_7702); return;
        }
        BuilderSettings options = settings(player);
        if (!valid(options)) { message(player, PlayerMsgId.P_7703); return; }
        String command = action.command;
        if (action == BuilderSelectionAction.EXPAND || action == BuilderSelectionAction.CONTRACT) {
            command += " " + options.amount + " " + options.direction.argument;
        }
        pending.remove(player.getUniqueId());
        player.closeInventory();
        dispatch(player, command);
    }

    /** @param playerId 退出・runtime破棄対象。設定と未実行要求を破棄します。 */
    public void clear(UUID playerId) { settings.remove(playerId); pending.remove(playerId); }

    /** プラグイン停止時に未実行要求と設定を破棄します。 */
    public void stop() { stopped = true; settings.clear(); pending.clear(); }

    /** 素材名はBukkit定数から作り、空白やWorldEdit式を受け入れません。 */
    private boolean valid(BuilderSettings options) {
        return options.material != null && options.material.isBlock() && options.replaceFrom != null
                && options.replaceFrom.isBlock() && options.direction != null && options.radius >= 1 && options.radius <= 32
                && options.height >= 1 && options.height <= 64 && options.amount >= 1 && options.amount <= 64;
    }

    /** 固定列挙の操作と検証済み数値・素材からWorldEditコマンドを作ります。 */
    private String command(BuilderOperation operation, BuilderSettings options) {
        String block = options.material == null ? Material.STONE.getKey().toString() : options.material.getKey().toString();
        String from = options.replaceFrom == null ? Material.DIRT.getKey().toString() : options.replaceFrom.getKey().toString();
        String direction = options.direction == null ? "north" : options.direction.argument;
        return switch (operation) {
            case SET -> "/set " + block;
            case REPLACE -> "/replace " + from + " " + block;
            case WALLS -> "/walls " + block;
            case FACES -> "/faces " + block;
            case DELETE -> "/set minecraft:air";
            case COPY -> "/copy";
            case PASTE -> options.ignoreAir ? "/paste -a" : "/paste";
            case ROTATE_RIGHT -> "/rotate 90";
            case ROTATE_LEFT -> "/rotate 270";
            case FLIP_EAST -> "/flip east";
            case FLIP_UP -> "/flip up";
            case UNDO -> "/undo 1";
            case REDO -> "/redo 1";
            case STACK -> "/stack " + (options.ignoreAir ? "-a " : "") + options.amount + " " + direction;
            case MOVE -> "/move -s " + (options.ignoreAir ? "-a " : "") + options.amount + " " + direction;
            case SPHERE -> "/sphere " + block + " " + options.radius;
            case HOLLOW_SPHERE -> "/hsphere " + block + " " + options.radius;
            case CYLINDER -> "/cyl " + block + " " + options.radius + " " + options.height;
            case HOLLOW_CYLINDER -> "/hcyl " + block + " " + options.radius + " " + options.height;
            case SMOOTH -> "/smooth 1";
        };
    }

    /** 確認用の保守的な対象規模を計算します。履歴操作はWorldEdit側へ任せます。 */
    private long estimate(BuilderOperation operation, BuilderSettings options, WorldEditSnapshot snapshot) {
        long side = 2L * options.radius + 1L;
        return switch (operation) {
            case SPHERE, HOLLOW_SPHERE -> side * side * side;
            case CYLINDER, HOLLOW_CYLINDER -> side * side * options.height;
            case UNDO, REDO -> -1L;
            case STACK -> saturatedMultiply(snapshot.selection().volume(), options.amount);
            case MOVE -> saturatedMultiply(snapshot.selection().volume(), 2L);
            default -> operation.selectionRequired ? snapshot.selection().volume() : snapshot.clipboard().volume();
        };
    }

    /** 乗算の桁あふれを拒否側の値へ変換します。 */
    private long saturatedMultiply(long value, long multiplier) {
        if (value <= 0 || value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return value * multiplier;
    }

    /** 現在の配置位置またはコピー原点に依存する操作を判定します。 */
    private boolean usesPlacement(BuilderOperation operation) {
        return switch (operation) {
            case COPY, PASTE, SPHERE, HOLLOW_SPHERE, CYLINDER, HOLLOW_CYLINDER -> true;
            default -> false;
        };
    }

    /** 実行可否と完了を混同せず、結果通知はWorldEditのものを確認するよう案内します。 */
    private void dispatch(Player player, String command) {
        try {
            if (!gateway.dispatch(player, command)) message(player, PlayerMsgId.P_7710);
            else message(player, PlayerMsgId.P_7711);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_7701, exception, player.getUniqueId());
            message(player, PlayerMsgId.P_7710);
        }
        reopen(player);
    }

    /** GUIを再開するクリック可能な通知を送ります。 */
    private void reopen(Player player) {
        PlayerMessageService.getInstance().sendClickable(player, PlayerMsgId.P_7712, "/builder");
    }

    /** 正本のメッセージサービスを経由して通知します。 */
    private void message(Player player, PlayerMsgId id, Object... arguments) {
        PlayerMessageService.getInstance().send(player, id, arguments);
    }
}
