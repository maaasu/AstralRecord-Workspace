package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Mob・採集スポナーの管理表示を距離と件数で制限し、必要な差分だけ送信します。 */
public abstract class SpawnerVisualizer<T> implements Listener {
    private static final long INTERVAL_TICKS = 40L;
    private static final double BLOCK_DISTANCE_SQ = 32.0D * 32.0D;
    private static final double DETAIL_DISTANCE_SQ = 8.0D * 8.0D;
    private static final int MAX_BLOCKS_PER_VIEWER = 64;
    private static final int MAX_LABELS_PER_VIEWER = 8;
    private static final int MAX_PARTICLE_SOURCES_PER_VIEWER = 4;
    private static final float BLOCK_SCALE = 0.75F;
    private static final float TEXT_SCALE = 0.8F;

    private final Plugin plugin;
    private final ParticleDisplayService particleDisplayService;
    private final NamespacedKey visualModeKey;
    private final SpawnerPacketDisplay packetDisplay = new SpawnerPacketDisplay();
    private final Map<UUID, Map<String, SpawnerVisual>> displays = new HashMap<>();
    private BukkitTask task;

    /**
     * 同期スレッドで使用する表示管理を構築します。上限はスポナー種別ごとに適用します。
     *
     * @param plugin プラグイン本体
     * @param particleDisplayService 共有パーティクル送信サービス
     */
    protected SpawnerVisualizer(@NotNull Plugin plugin, @NotNull ParticleDisplayService particleDisplayService) {
        this.plugin = plugin;
        this.particleDisplayService = particleDisplayService;
        this.visualModeKey = MobSpawnerVisualMode.storageKey(plugin);
    }

    /** @return この種別の登録済み配置。同期スレッドから参照します。 */
    protected abstract @NotNull Collection<T> locations();

    /**
     * @param spawner 配置情報
     * @return ワールドが存在する場合の座標。存在しない場合は null
     */
    protected abstract @Nullable Location location(@NotNull T spawner);

    /**
     * @param spawner 配置情報
     * @return 座標と定義 ID を含む、この種別内で一意な表示キー
     */
    protected abstract @NotNull String key(@NotNull T spawner);

    /**
     * @param spawner 配置情報
     * @return 管理表示用のブロック材質
     */
    protected abstract @NotNull Material material(@NotNull T spawner);

    /**
     * @param spawner 配置情報
     * @return 出現対象名を含む管理ラベル
     */
    protected abstract @NotNull Component label(@NotNull T spawner);

    /** 同期スレッドで周期同期と viewer のライフサイクル監視を開始します。重複開始は無視します。 */
    public final void start() {
        if (task == null) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(true), 1L, INTERVAL_TICKS);
        }
    }

    /** 同期スレッドから表示設定の変更を即時反映します。停止中は何もしません。 */
    public final void refresh() {
        if (task != null) {
            tick(false);
        }
    }

    /** 同期スレッドで監視を停止し、送信済み表示をすべて破棄します。 */
    public final void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        for (UUID viewerId : Set.copyOf(displays.keySet())) {
            clearViewer(viewerId);
        }
    }

    /**
     * 管理者を先に絞り、近い配置から上限件数だけ選択して表示の差分を同期します。
     *
     * @param emitParticles 周期演出のパーティクルも送信する場合は true
     */
    private void tick(boolean emitParticles) {
        Set<UUID> activeViewers = new HashSet<>();
        List<VisibleSpawner<T>> candidates = null;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            var astPlayer = AstPlayerCache.get(player);
            MobSpawnerVisualMode mode = MobSpawnerVisualMode.fromStoredValue(
                player.getPersistentDataContainer().get(visualModeKey, PersistentDataType.BYTE)
            );
            if (astPlayer == null || astPlayer.getAccount().getMode() != AccountMode.ADMIN
                || mode == MobSpawnerVisualMode.OFF) {
                continue;
            }
            activeViewers.add(player.getUniqueId());
            if (candidates == null) {
                candidates = new ArrayList<>();
                for (T spawner : locations()) {
                    Location base = location(spawner);
                    if (base != null && base.getWorld() != null) {
                        candidates.add(new VisibleSpawner<>(spawner, base, key(spawner), 0.0D));
                    }
                }
            }
            updateViewer(player, mode, nearest(player, candidates), emitParticles);
        }
        for (UUID viewerId : Set.copyOf(displays.keySet())) {
            if (!activeViewers.contains(viewerId)) {
                clearViewer(viewerId);
            }
        }
    }

    /**
     * 未送信チャンクを参照・ロードせず、32 block 内の最寄り64配置を安定した順序で返します。
     *
     * @param player 表示対象プレイヤー
     * @param candidates 登録座標の候補
     * @return 距離、表示キー順に並んだ上限内の候補
     */
    private @NotNull List<VisibleSpawner<T>> nearest(
        @NotNull Player player, @NotNull List<VisibleSpawner<T>> candidates
    ) {
        Location origin = player.getLocation();
        Comparator<VisibleSpawner<T>> order = Comparator
            .comparingDouble((VisibleSpawner<T> candidate) -> candidate.distanceSquared())
            .thenComparing(VisibleSpawner::key);
        PriorityQueue<VisibleSpawner<T>> nearest = new PriorityQueue<>(MAX_BLOCKS_PER_VIEWER, order.reversed());
        for (VisibleSpawner<T> candidate : candidates) {
            Location base = candidate.location();
            if (base.getWorld() != origin.getWorld()) {
                continue;
            }
            double distanceSquared = origin.distanceSquared(base);
            if (distanceSquared > BLOCK_DISTANCE_SQ
                || !player.isChunkSent(Chunk.getChunkKey(base.getBlockX() >> 4, base.getBlockZ() >> 4))) {
                continue;
            }
            VisibleSpawner<T> visible = new VisibleSpawner<>(
                candidate.spawner(), base, candidate.key(), distanceSquared
            );
            if (nearest.size() < MAX_BLOCKS_PER_VIEWER) {
                nearest.add(visible);
            } else if (order.compare(visible, nearest.peek()) < 0) {
                nearest.poll();
                nearest.add(visible);
            }
        }
        List<VisibleSpawner<T>> result = new ArrayList<>(nearest);
        result.sort(order);
        return result;
    }

    /**
     * 上限から外れた表示を先に消し、ブロック・近距離ラベル・周期粒子を同期します。
     *
     * @param player 表示対象のオンライン viewer
     * @param mode 保存済みの表示モード
     * @param selected 距離・表示キー順で選択した上限内の配置
     * @param emitParticles 周期粒子も送信する場合は true
     */
    private void updateViewer(
        @NotNull Player player, @NotNull MobSpawnerVisualMode mode,
        @NotNull List<VisibleSpawner<T>> selected, boolean emitParticles
    ) {
        Map<String, SpawnerVisual> viewerDisplays = displays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Set<String> activeKeys = new HashSet<>();
        selected.forEach(candidate -> activeKeys.add(candidate.key()));
        viewerDisplays.entrySet().removeIf(entry -> {
            if (activeKeys.contains(entry.getKey())) {
                return false;
            }
            return entry.getValue().destroy(player);
        });

        Set<String> labelKeys = new HashSet<>();
        if (mode == MobSpawnerVisualMode.NORMAL) {
            for (VisibleSpawner<T> candidate : selected) {
                if (candidate.distanceSquared() <= DETAIL_DISTANCE_SQ && labelKeys.size() < MAX_LABELS_PER_VIEWER) {
                    labelKeys.add(candidate.key());
                }
            }
        }
        for (Map.Entry<String, SpawnerVisual> entry : viewerDisplays.entrySet()) {
            if (!entry.getValue().retiring && !labelKeys.contains(entry.getKey())) {
                entry.getValue().hideLabel(player);
            }
        }
        int availableLabels = MAX_LABELS_PER_VIEWER
            - (int) viewerDisplays.values().stream().filter(display -> display.text != null).count();
        int particles = 0;
        for (VisibleSpawner<T> candidate : selected) {
            Material blockMaterial = material(candidate.spawner());
            SpawnerVisual display = viewerDisplays.get(candidate.key());
            if (display != null && (display.retiring || display.material != blockMaterial
                || !display.location.equals(candidate.location()))) {
                boolean hadLabel = display.text != null;
                boolean destroyed = display.destroy(player);
                if (hadLabel && display.text == null) {
                    availableLabels++;
                }
                if (!destroyed) {
                    continue;
                }
                viewerDisplays.remove(candidate.key());
                display = null;
            }
            if (display == null) {
                if (viewerDisplays.size() >= MAX_BLOCKS_PER_VIEWER) {
                    continue;
                }
                display = new SpawnerVisual(candidate.location(), blockMaterial);
                viewerDisplays.put(candidate.key(), display);
            }
            boolean detail = mode == MobSpawnerVisualMode.NORMAL && candidate.distanceSquared() <= DETAIL_DISTANCE_SQ;
            boolean hadLabel = display.text != null;
            boolean showLabel = labelKeys.contains(candidate.key()) && (hadLabel || availableLabels > 0);
            display.show(player, showLabel ? label(candidate.spawner()) : null);
            if (hadLabel != (display.text != null)) {
                availableLabels += hadLabel ? 1 : -1;
            }
            if (detail && emitParticles && particles++ < MAX_PARTICLE_SOURCES_PER_VIEWER) {
                particleDisplayService.spawnForViewer(player, candidate.location().clone().add(0.0D, 0.75D, 0.0D),
                    SharedParticleDefinitions.SPAWNER_VISUAL_ENCHANT);
            }
        }
    }

    /** @param viewerId 表示を破棄する viewer UUID。送信失敗は保持し、切断済みなら追跡状態を削除します。 */
    private void clearViewer(@NotNull UUID viewerId) {
        Map<String, SpawnerVisual> viewerDisplays = displays.get(viewerId);
        if (viewerDisplays != null) {
            Player player = plugin.getServer().getPlayer(viewerId);
            viewerDisplays.entrySet().removeIf(entry -> entry.getValue().destroy(player));
            if (viewerDisplays.isEmpty()) {
                displays.remove(viewerId);
            }
        }
    }

    /** @param event 切断イベント。再接続時に古い表示状態を引き継がないよう破棄します。 */
    @EventHandler
    public final void onQuit(@NotNull PlayerQuitEvent event) {
        clearViewer(event.getPlayer().getUniqueId());
        // 切断でクライアント状態は失われるため、旧セッションの失敗状態を再接続へ持ち越しません。
        displays.remove(event.getPlayer().getUniqueId());
    }

    /** @param event 成功予定のテレポート。移動先では次の周期で必要な表示だけを再送します。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public final void onTeleport(@NotNull PlayerTeleportEvent event) {
        clearViewer(event.getPlayer().getUniqueId());
    }

    /** @param event ワールド変更イベント。クライアントの entity リセットに追跡状態を合わせます。 */
    @EventHandler
    public final void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        clearViewer(event.getPlayer().getUniqueId());
    }

    /** @param event リスポーンイベント。クライアントの entity リセット後に表示を再送できるようにします。 */
    @EventHandler
    public final void onRespawn(@NotNull PlayerRespawnEvent event) {
        clearViewer(event.getPlayer().getUniqueId());
    }

    /** @param event チャンク解除 packet のイベント。そのチャンクに送信した表示だけを破棄します。 */
    @EventHandler
    public final void onChunkUnload(@NotNull PlayerChunkUnloadEvent event) {
        Map<String, SpawnerVisual> viewerDisplays = displays.get(event.getPlayer().getUniqueId());
        if (viewerDisplays != null) {
            viewerDisplays.entrySet().removeIf(entry -> {
                Location base = entry.getValue().location;
                if (base.getWorld() != event.getWorld() || base.getBlockX() >> 4 != event.getChunk().getX()
                    || base.getBlockZ() >> 4 != event.getChunk().getZ()) {
                    return false;
                }
                return entry.getValue().destroy(event.getPlayer());
            });
        }
    }

    private record VisibleSpawner<T>(T spawner, Location location, String key, double distanceSquared) { }

    private final class SpawnerVisual {
        private final Location location;
        private final Material material;
        private final SpawnerPacketDisplay.PacketEntity block;
        private SpawnerPacketDisplay.PacketEntity text;
        private Component lastLabel;
        private boolean spawned;
        private boolean textSpawned;
        private boolean retiring;

        /**
         * 選択された配置のブロックだけを準備します。ラベルは必要になるまで生成しません。
         *
         * @param location ワールドが存在する配置座標
         * @param material 管理表示のブロック材質
         */
        private SpawnerVisual(@NotNull Location location, @NotNull Material material) {
            this.location = location.clone();
            this.material = material;
            this.block = packetDisplay.block(location.clone().add(0.0D, 0.05D, 0.0D), material,
                new Vector3f(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE));
        }

        /**
         * ブロック送信失敗時は次周期で再試行し、ラベルの有無・内容が変わった場合だけ差分を送ります。
         *
         * @param player 送信先のオンライン viewer
         * @param label 表示するラベル。null の場合はラベルを破棄します。
         */
        private void show(@NotNull Player player, @Nullable Component label) {
            if (!spawned) {
                spawned = block.spawn(player);
            }
            if (!spawned) {
                return;
            }
            if (!java.util.Objects.equals(lastLabel, label) && !hideLabel(player)) {
                return;
            }
            if (label != null && text == null) {
                text = packetDisplay.text(location.clone().add(0.0D, 1.35D, 0.0D), label, TEXT_SCALE);
                lastLabel = label;
            }
            if (text != null && !textSpawned) {
                textSpawned = text.spawn(player);
            }
        }

        /**
         * ラベルを破棄し、送信成功時だけ追跡を解除します。
         *
         * @param player 送信先のオンライン viewer
         * @return ラベルがない、または破棄 packet を送信できた場合は true
         */
        private boolean hideLabel(@NotNull Player player) {
            if (text != null && !text.destroy(player)) {
                return false;
            }
            text = null;
            textSpawned = false;
            lastLabel = null;
            return true;
        }

        /**
         * オンライン viewer の表示を破棄します。失敗した entity ID は再試行と上限計算のため保持します。
         *
         * @param player 送信先。切断済みで取得不能な場合は null
         * @return 全表示の破棄に成功、または切断済みで追跡が不要なら true
         */
        private boolean destroy(@Nullable Player player) {
            retiring = true;
            if (player != null && player.isOnline()) {
                // spawn 途中失敗時の部分表示も同じ ID で破棄します。
                boolean blockDestroyed = block.destroy(player);
                if (blockDestroyed) {
                    spawned = false;
                }
                boolean labelDestroyed = hideLabel(player);
                return blockDestroyed && labelDestroyed;
            }
            spawned = false;
            text = null;
            textSpawned = false;
            lastLabel = null;
            return true;
        }
    }
}
