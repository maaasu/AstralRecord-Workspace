package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mob ドロップの個別表示、回収演出、インベントリ投入を扱います。
 */
public final class MobDropPresentationService {
    private static final double RESULT_HEIGHT = 1.9D;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemDropAnimationService itemDropAnimationService;

    /**
     * サービスを初期化します。
     *
     * @param plugin scheduler と entity 表示に使うプラグイン
     * @param itemService item ID の解決に使うサービス
     * @param inventoryService インベントリ投入に使うサービス
     * @param itemDropAnimationService 報酬アイテムの落下・回収演出サービス
     */
    public MobDropPresentationService(
            @NotNull Plugin plugin,
            @NotNull ItemService itemService,
            @NotNull InventoryService inventoryService,
            @NotNull ItemDropAnimationService itemDropAnimationService
    ) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.itemDropAnimationService = itemDropAnimationService;
    }

    /**
     * 指定プレイヤーだけに見える撃破リザルトとドロップ回収演出を開始します。
     *
     * @param recipient 表示・付与対象プレイヤー
     * @param deathLocation Mob 死亡位置
     * @param mobName Mob 表示名
     * @param result 個別抽選済みドロップ結果
     */
    public void presentAndGrant(
            @NotNull AstPlayer recipient,
            @NotNull Location deathLocation,
            @NotNull String mobName,
            @NotNull MobDropResult result
    ) {
        Player player = recipient.getBukkit();
        if (!player.isOnline()) {
            return;
        }

        List<ResolvedDropItem> resolvedItems = resolveItems(result);
        spawnResultText(player, deathLocation, mobName, result, resolvedItems);
        for (int index = 0; index < resolvedItems.size(); index++) {
            spawnCollectingItem(recipient, deathLocation, resolvedItems.get(index), index);
        }
    }

    private @NotNull List<ResolvedDropItem> resolveItems(@NotNull MobDropResult result) {
        List<ResolvedDropItem> resolved = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.items()) {
            ItemModel model = itemService.findLoadedById(entry.getKey());
            if (model == null) {
                model = itemService.loadItem(entry.getKey());
            }
            if (model == null) {
                continue;
            }
            int amount = Math.max(1, entry.getValue());
            resolved.add(new ResolvedDropItem(model, amount));
        }
        return resolved;
    }

    private void spawnResultText(
            @NotNull Player viewer,
            @NotNull Location deathLocation,
            @NotNull String mobName,
            @NotNull MobDropResult result,
            @NotNull List<ResolvedDropItem> items
    ) {
        World world = deathLocation.getWorld();
        if (world == null) {
            return;
        }

        Location location = deathLocation.clone().add(0.0D, RESULT_HEIGHT, 0.0D);
        TextDisplay display = world.spawn(location, TextDisplay.class, text -> {
            text.setPersistent(false);
            text.setGravity(false);
            text.setInvulnerable(true);
            text.setSilent(true);
            text.setVisibleByDefault(false);
            text.setBillboard(Display.Billboard.CENTER);
            text.setSeeThrough(true);
            text.setShadowed(true);
            text.setLineWidth(280);
            text.setViewRange(48.0F);
            text.setDefaultBackground(false);
            text.setBackgroundColor(Color.fromARGB(128, 8, 4, 18));
            text.text(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(formatResultText(mobName, result, items))));
            text.setTransformation(scale(0.8F));
        });
        viewer.showEntity(plugin, display);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> removeIfValid(display), 86L);
    }

    private @NotNull String formatResultText(
            @NotNull String mobName,
            @NotNull MobDropResult result,
            @NotNull List<ResolvedDropItem> items
    ) {
        StringBuilder text = new StringBuilder("&6&lRESULT &f").append(mobName);
        text.append("\n&eEXP &f+").append(result.exp());
        text.append("  &6Money &f+").append(result.money());
        text.append("\n&aDrop &f");
        if (items.isEmpty()) {
            text.append("なし");
            return text.toString();
        }
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                text.append("&7, &f");
            }
            ResolvedDropItem item = items.get(index);
            text.append(item.model().getName()).append(" x").append(item.amount());
        }
        return text.toString();
    }

    private void spawnCollectingItem(
            @NotNull AstPlayer recipient,
            @NotNull Location deathLocation,
            @NotNull ResolvedDropItem item,
            int index
    ) {
        Player player = recipient.getBukkit();
        World world = deathLocation.getWorld();
        if (world == null || !player.isOnline()) {
            return;
        }

        itemDropAnimationService.playCollectingDrop(
                player,
                deathLocation,
                item.model(),
                item.amount(),
                index,
                () -> grantItem(recipient, item)
        );
    }

    private void grantItem(@NotNull AstPlayer recipient, @NotNull ResolvedDropItem item) {
        if (!recipient.getBukkit().isOnline()) {
            return;
        }
        int granted = inventoryService.addItemToNormalInventory(recipient, item.model(), item.amount(), "mob_drop");
        if (granted < item.amount()) {
            recipient.sendMessage(PlayerMsgId.P_5241);
        }
    }

    private static @NotNull Transformation scale(float value) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(value, value, value),
                new Quaternionf()
        );
    }

    private void removeIfValid(@Nullable Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private record ResolvedDropItem(@NotNull ItemModel model, int amount) {
        private ResolvedDropItem {
            amount = Math.max(1, amount);
        }
    }
}
