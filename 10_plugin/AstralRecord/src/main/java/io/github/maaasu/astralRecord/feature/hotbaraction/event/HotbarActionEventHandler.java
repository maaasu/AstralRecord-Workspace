package io.github.maaasu.astralRecord.feature.hotbaraction.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ホットバーのアイテム操作（左クリック・右クリック）を検知するイベントハンドラ。
 * <p>
 * 現在はデバッグ用メッセージ表示のみを行います。
 * 将来的には武器通常攻撃・ポーション使用・バンドル開封などのアクションに委譲します。
 */
public class HotbarActionEventHandler extends AbstractEventHandler {

    /**
     * プレイヤーのアイテム操作を検知し、左クリック・右クリックを識別してデバッグメッセージを送信します。
     * <p>
     * Paper はインタラクト可能なブロックへの操作時に {@code useItemInHand = DENY} を事前セットするため、
     * {@code ignoreCancelled = true} にすると本ハンドラが呼ばれなくなります。
     * そのため {@code ignoreCancelled} はデフォルト（false）のまま使用し、キャンセル済みイベントでも必ず実行します。
     * バニラ動作の抑止は {@link io.github.maaasu.astralRecord.feature.item.event.ItemInteractionBlockEventHandler}
     * が {@link EventPriority#HIGHEST} で担います。
     *
     * @param event Bukkit のアイテム操作イベント
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            var action = event.getAction();
            if (action == Action.PHYSICAL) {
                return;
            }

            var astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null || astPlayer.getAccount().getMode() != AccountMode.PLAYER) {
                return;
            }

            var itemId = resolveAstralItemId(event.getItem());
            if (itemId == null) {
                return;
            }

            if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                astPlayer.sendMessage(PlayerMsgId.P_6000, itemId);
            } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                astPlayer.sendMessage(PlayerMsgId.P_6001, itemId);
            }
        }, LogId.E_6000, event.getPlayer().getName());
    }

    @Nullable
    private static String resolveAstralItemId(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        return ItemStackFactory.getAstralItemId(item);
    }
}
