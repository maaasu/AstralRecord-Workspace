package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 報酬アイテムの ItemDisplay 落下・回収アニメーションを扱います。
 */
public final class ItemDropAnimationService {

    private static final long FALL_TICKS = 12L;
    private static final long REST_TICKS = 4L;
    private static final long COLLECT_TICKS = 10L;
    private static final double START_HEIGHT = 1.85D;
    private static final double LAND_HEIGHT = 0.18D;
    private static final ItemDisplay.ItemDisplayTransform DROP_DISPLAY_TRANSFORM =
            ItemDisplay.ItemDisplayTransform.GROUND;

    private final Plugin plugin;
    private final ItemStackFactory itemStackFactory;
    private final ParticleDisplayService particleDisplayService;

    /**
     * サービスを初期化します。
     *
     * @param plugin scheduler とエンティティ表示に使うプラグイン
     * @param itemStackFactory 表示用 ItemStack 生成ファクトリ
     */
    public ItemDropAnimationService(
            @NotNull Plugin plugin,
            @NotNull ItemStackFactory itemStackFactory,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.itemStackFactory = itemStackFactory;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * アイテムを上から落としてからプレイヤーへ回収する演出を再生します。
     *
     * @param viewer 表示対象プレイヤー
     * @param origin 演出の基準位置
     * @param model 表示するアイテム定義
     * @param amount 表示個数
     * @param index 複数同時表示時のずらし番号
     * @param onCollected 回収演出完了時に実行する処理
     */
    public void playCollectingDrop(
            @NotNull Player viewer,
            @NotNull Location origin,
            @NotNull ItemModel model,
            int amount,
            int index,
            @Nullable Runnable onCollected
    ) {
        playCollectingDrop(
            viewer,
            origin,
            model,
            amount,
            index,
            CompletableFuture.completedFuture(null),
            onCollected,
            null
        );
    }

    public void playCollectingDrop(
            @NotNull Player viewer,
            @NotNull Location origin,
            @NotNull ItemModel model,
            int amount,
            int index,
            @NotNull CompletableFuture<?> readyFuture,
            @Nullable Runnable onCollected,
            @Nullable Runnable onCancelled
    ) {
        World world = origin.getWorld();
        if (world == null || !viewer.isOnline()) {
            if (onCancelled != null) {
                onCancelled.run();
            }
            return;
        }

        Location start = origin.clone().add(0.0D, START_HEIGHT, 0.0D);
        Location landing = origin.clone().add(scatterVector(index));
        landing.setY(origin.getY() + LAND_HEIGHT);

        ItemDisplay display = world.spawn(start, ItemDisplay.class, entity -> {
            entity.setItemStack(itemStackFactory.createDisplay(model, Math.min(amount, model.getMaxStack())));
            entity.setItemDisplayTransform(DROP_DISPLAY_TRANSFORM);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setVisibleByDefault(false);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setViewRange(48.0F);
            entity.setInterpolationDuration(1);
            entity.setTeleportDuration(1);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(scale(0.58F));
        });
        viewer.showEntity(plugin, display);

        new BukkitRunnable() {
            private long ageTicks;
            private boolean landed;

            @Override
            public void run() {
                if (!display.isValid() || !viewer.isOnline()) {
                    removeIfValid(display);
                    if (onCancelled != null) {
                        onCancelled.run();
                    }
                    cancel();
                    return;
                }

                ageTicks++;
                if (ageTicks <= FALL_TICKS) {
                    double progress = ageTicks / (double) FALL_TICKS;
                    display.teleport(interpolate(start, landing, easeIn(progress)));
                    return;
                }

                if (!landed) {
                    particleDisplayService.spawnForViewer(
                        viewer,
                        landing,
                        SharedParticleDefinitions.ITEM_DROP_LAND_CRIT
                    );
                    landed = true;
                }

                long restAge = ageTicks - FALL_TICKS;
                if (restAge <= REST_TICKS) {
                    double bounce = Math.sin((restAge / (double) REST_TICKS) * Math.PI) * 0.08D;
                    display.teleport(landing.clone().add(0.0D, bounce, 0.0D));
                    return;
                }

                long collectAge = restAge - REST_TICKS;
                Location target = viewer.getLocation().clone().add(0.0D, 1.05D, 0.0D);
                double progress = Math.min(1.0D, collectAge / (double) COLLECT_TICKS);
                display.teleport(interpolate(landing, target, easeInOut(progress)));
                if (collectAge < COLLECT_TICKS) {
                    return;
                }
                if (!readyFuture.isDone()) {
                    return;
                }
                if (readyFuture.isCancelled() || readyFuture.isCompletedExceptionally()) {
                    removeIfValid(display);
                    if (onCancelled != null) {
                        onCancelled.run();
                    }
                    cancel();
                    return;
                }

                particleDisplayService.spawnForViewer(
                    viewer,
                    target,
                    SharedParticleDefinitions.ITEM_DROP_COLLECT_END_ROD
                );
                viewer.playSound(target, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.45F, 1.35F);
                removeIfValid(display);
                if (onCollected != null) {
                    onCollected.run();
                }
                cancel();
            }
        }.runTaskTimer(plugin, Math.max(0L, index * 2L), 1L);
    }

    private @NotNull Vector scatterVector(int index) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = rng.nextDouble(0.0D, Math.PI * 2.0D) + index * 0.73D;
        double radius = rng.nextDouble(0.55D, 1.05D);
        return new Vector(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
    }

    private @NotNull Location interpolate(@NotNull Location from, @NotNull Location to, double progress) {
        double p = Math.max(0.0D, Math.min(1.0D, progress));
        return from.clone().add(
                (to.getX() - from.getX()) * p,
                (to.getY() - from.getY()) * p,
                (to.getZ() - from.getZ()) * p
        );
    }

    private double easeIn(double progress) {
        return progress * progress;
    }

    private double easeInOut(double progress) {
        return progress < 0.5D
                ? 2.0D * progress * progress
                : 1.0D - Math.pow(-2.0D * progress + 2.0D, 2.0D) / 2.0D;
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
}
