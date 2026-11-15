package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** ダンジョンとボスの推奨レベル未満の参加者に、挑戦前の確認を表示します。 */
public final class ChallengeLevelConfirmationGui implements Listener {
    private final Plugin plugin;
    private final Map<UUID, Holder> openConfirmations = new HashMap<>();

    /**
     * 挑戦確認画面の管理を初期化します。
     *
     * @param plugin 画面操作の完了処理をメインスレッドで実行するプラグイン
     */
    public ChallengeLevelConfirmationGui(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * メインスレッドから、共通リソースパックの確認・取消ボタンを使う画面を開きます。
     *
     * @param astPlayer 確認する参加者
     * @param displayName 挑戦対象の表示名
     * @param recommendedLevel 推奨プレイヤーレベル
     * @param basisLevel 比較に用いたプレイヤーレベル
     * @param rebirth 比較に転生開始時のレベルを用いた場合 true
     * @param response 本人の選択、または表示できない場合の結果を受け取る処理
     * @return 表示要求を受け付けた場合 true。false の場合に応答処理は呼び出しません
     */
    public boolean show(
            @NotNull AstPlayer astPlayer,
            @NotNull String displayName,
            int recommendedLevel,
            int basisLevel,
            boolean rebirth,
            @NotNull Consumer<Response> response
    ) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        if (!plugin.isEnabled() || !player.isOnline() || player.isDead()
                || player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                || openConfirmations.containsKey(playerId)) {
            return false;
        }
        PlayerMsgId titleId = rebirth ? PlayerMsgId.P_6716 : PlayerMsgId.P_6715;
        Holder holder = new Holder(player, response, PlayerMsgResource.formatComponent(
                titleId.getId(), recommendedLevel, basisLevel));
        ItemStack details = GuiResourcePackIcons.apply(GuiItems.create(
                Material.KNOWLEDGE_BOOK,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_6717.getId(), displayName),
                List.of()
        ), "guide");
        new ConfirmDialogView().render(
                holder.inventory,
                details,
                PlayerMsgResource.getComponent(PlayerMsgId.P_6718.getId()),
                PlayerMsgResource.getComponent(PlayerMsgId.P_6719.getId()),
                List.of()
        );
        openConfirmations.put(playerId, holder);
        GuiOpenSupport.open(player, holder.inventory, () -> {
            holder.opening = false;
            GuiSound.OPEN.play(player);
        }, () -> {
            holder.opening = false;
            scheduleResponse(holder, Response.UNAVAILABLE);
        });
        return true;
    }

    /**
     * メインスレッドから確認を無効化し、この画面だけを閉じます。応答処理は実行しません。
     *
     * @param playerId 確認を無効化する参加者 UUID
     */
    public void dismiss(@NotNull UUID playerId) {
        Holder holder = openConfirmations.remove(playerId);
        if (holder != null) {
            close(holder);
        }
    }

    /**
     * 所持品の操作を抑止し、確認・取消スロットへの通常クリックだけを受け付けます。
     *
     * @param event インベントリクリック
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled || event.getWhoClicked() != holder.player
                || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        if (event.getRawSlot() == ConfirmDialogView.CONFIRM_SLOT) {
            scheduleResponse(holder, Response.CONFIRMED);
        } else if (event.getRawSlot() == ConfirmDialogView.CANCEL_SLOT) {
            scheduleResponse(holder, Response.DECLINED);
        }
    }

    /**
     * 確認画面にアイテムが移動しないようにします。
     *
     * @param event インベントリ内のドラッグ操作
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /**
     * 本人が閉じた画面を取消とし、外部要因で閉じた場合は確認未完了として扱います。
     *
     * @param event インベントリの終了
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Holder holder) {
            Response response = event.getReason() == InventoryCloseEvent.Reason.PLAYER
                    ? Response.DECLINED
                    : Response.UNAVAILABLE;
            scheduleResponse(holder, response);
        }
    }

    /**
     * 接続終了時に確認を取り消します。
     *
     * @param event プレイヤーの退出
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        cancelForPlayer(event.getPlayer());
    }

    /**
     * 別ワールドへ移動した参加者の確認を取り消します。
     *
     * @param event プレイヤーのワールド移動
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        cancelForPlayer(event.getPlayer());
    }

    /**
     * 死亡した参加者の確認を取り消します。
     *
     * @param event プレイヤーの死亡
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        cancelForPlayer(event.getEntity());
    }

    /**
     * プレイヤーが場を離れた場合、未処理の確認クリックも取り消します。
     *
     * @param player 状態が変わった参加者
     */
    private void cancelForPlayer(@NotNull Player player) {
        Holder holder = openConfirmations.get(player.getUniqueId());
        if (holder == null) {
            return;
        }
        if (holder.result == null) {
            scheduleResponse(holder, Response.UNAVAILABLE);
        } else {
            holder.result = Response.UNAVAILABLE;
        }
    }

    /**
     * インベントリイベントの完了後に結果を一度だけ通知します。
     *
     * @param holder 応答する画面の holder
     * @param result プレイヤーの選択または画面の終了
     */
    private void scheduleResponse(@NotNull Holder holder, @NotNull Response result) {
        UUID playerId = holder.player.getUniqueId();
        if (!plugin.isEnabled() || openConfirmations.get(playerId) != holder || holder.result != null) {
            return;
        }
        holder.result = result;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!openConfirmations.remove(playerId, holder)) {
                return;
            }
            close(holder);
            if (!holder.player.isOnline() || holder.player.isDead()) {
                holder.result = Response.UNAVAILABLE;
            } else if (holder.result == Response.CONFIRMED) {
                GuiSound.CONFIRM.play(holder.player);
            } else if (holder.result == Response.DECLINED) {
                GuiSound.CLOSE.play(holder.player);
            }
            holder.response.accept(holder.result);
        });
    }

    /**
     * 対象の画面が現在表示されている場合だけ閉じ、表示待ちなら予約を取り消します。
     *
     * @param holder 終了する画面
     */
    private void close(@NotNull Holder holder) {
        Player player = holder.player;
        if (holder.opening) {
            GuiOpenSupport.cancelPending(player.getUniqueId());
        }
        if (player.isOnline() && player.getOpenInventory().getTopInventory() == holder.inventory) {
            player.closeInventory();
        }
    }

    /** 確認画面に対する本人の選択、または表示できない状態です。 */
    public enum Response {
        CONFIRMED,
        DECLINED,
        UNAVAILABLE
    }

    private static final class Holder implements InventoryHolder {
        private final Player player;
        private final Consumer<Response> response;
        private final Inventory inventory;
        private boolean opening = true;
        private Response result;

        /**
         * 一度の確認に対応した、専用のインベントリを作成します。
         *
         * @param player 確認する参加者
         * @param response 確認結果を受け取る処理
         * @param title 確認画面に出すレベル情報
         */
        private Holder(Player player, Consumer<Response> response, Component title) {
            this.player = player;
            this.response = response;
            this.inventory = Bukkit.createInventory(this, ConfirmDialogView.SIZE, title);
        }

        /**
         * @return この確認専用のインベントリ
         */
        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
