package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob ドロップの個別表示、回収演出、インベントリ投入を扱います。
 */
public final class MobDropPresentationService {
    private static final long SCATTER_TICKS = 40L;
    private static final long COLLECT_TICKS = 8L;
    private static final long GRANT_DELAY_TICKS = 4L;
    private static final double RESULT_HEIGHT = 1.9D;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final ItemStackFactory itemStackFactory;

    /**
     * サービスを初期化します。
     *
     * @param plugin scheduler と entity 表示に使うプラグイン
     * @param itemService item ID の解決に使うサービス
     * @param inventoryService インベントリ投入に使うサービス
     * @param itemStackFactory 表示用 ItemStack の生成に使うファクトリ
     */
    public MobDropPresentationService(
            @NotNull Plugin plugin,
            @NotNull ItemService itemService,
            @NotNull InventoryService inventoryService,
            @NotNull ItemStackFactory itemStackFactory
    ) {
        this.plugin = plugin;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.itemStackFactory = itemStackFactory;
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

        ItemStack displayStack = itemStackFactory.create(item.model(), Math.min(item.amount(), item.model().getMaxStack()));
        Location start = deathLocation.clone().add(0.0D, 0.85D, 0.0D);
        ItemDisplay display = world.spawn(start, ItemDisplay.class, entity -> {
            entity.setItemStack(displayStack);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setVisibleByDefault(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setViewRange(48.0F);
            entity.setTransformation(scale(0.55F));
        });
        player.showEntity(plugin, display);

        Vector scatter = randomScatterVector(index);
        Location scatterTarget = start.clone().add(scatter);
        new BukkitRunnable() {
            private long ageTicks;

            @Override
            public void run() {
                if (!display.isValid() || !player.isOnline()) {
                    removeIfValid(display);
                    cancel();
                    return;
                }

                ageTicks++;
                if (ageTicks <= SCATTER_TICKS) {
                    double progress = ageTicks / (double) SCATTER_TICKS;
                    Location next = interpolate(start, scatterTarget, easeOut(progress));
                    next.add(0.0D, Math.sin(progress * Math.PI) * 0.45D, 0.0D);
                    display.teleport(next);
                    return;
                }

                long collectAge = ageTicks - SCATTER_TICKS;
                Location target = player.getLocation().clone().add(0.0D, 1.05D, 0.0D);
                double progress = Math.min(1.0D, collectAge / (double) COLLECT_TICKS);
                display.teleport(interpolate(scatterTarget, target, easeIn(progress)));
                if (collectAge < COLLECT_TICKS) {
                    return;
                }

                player.spawnParticle(Particle.END_ROD, target, 8, 0.16D, 0.22D, 0.16D, 0.01D);
                player.playSound(target, Sound.ENTITY_ITEM_PICKUP, 0.45F, 1.35F);
                removeIfValid(display);
                plugin.getServer().getScheduler().runTaskLater(
                        plugin,
                        () -> grantItem(recipient, item),
                        GRANT_DELAY_TICKS
                );
                cancel();
            }
        }.runTaskTimer(plugin, Math.max(0L, index * 2L), 1L);
    }

    private void grantItem(@NotNull AstPlayer recipient, @NotNull ResolvedDropItem item) {
        if (!recipient.getBukkit().isOnline()) {
            return;
        }
        inventoryService.addItemToNormalInventory(recipient, item.model(), item.amount(), "mob_drop");
    }

    private @NotNull Vector randomScatterVector(int index) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble(0.0D, Math.PI * 2.0D) + index * 0.73D;
        double radius = rng.nextDouble(0.85D, 1.45D);
        double y = rng.nextDouble(0.35D, 0.9D);
        return new Vector(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    private @NotNull Location interpolate(@NotNull Location from, @NotNull Location to, double progress) {
        double p = Math.max(0.0D, Math.min(1.0D, progress));
        return from.clone().add(
                (to.getX() - from.getX()) * p,
                (to.getY() - from.getY()) * p,
                (to.getZ() - from.getZ()) * p
        );
    }

    private double easeOut(double progress) {
        return 1.0D - Math.pow(1.0D - progress, 3.0D);
    }

    private double easeIn(double progress) {
        return progress * progress;
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
