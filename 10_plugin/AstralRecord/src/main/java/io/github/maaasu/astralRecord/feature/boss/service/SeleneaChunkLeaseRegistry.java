package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** セレネアの必須chunkだけを遭遇間で共有し、既存のPlugin ticketを所有者へ返します。 */
final class SeleneaChunkLeaseRegistry {
    private final JavaPlugin plugin;
    private final Map<ChunkKey, Lease> leases = new HashMap<>();
    private final Map<UUID, Set<ChunkKey>> owners = new HashMap<>();

    /** 同期スレッドでticketを操作するPluginを保持します。 */
    SeleneaChunkLeaseRegistry(@NotNull JavaPlugin plugin) { this.plugin = plugin; }

    /**
     * 同期スレッドで必要座標のchunkを重複排除して保持します。失敗時は今回の所有権を回収します。
     * @param owner 遭遇のボスUUID。登録済みの場合は何もしません
     * @param locations 同一world内のボス・門・写本守の必須座標
     */
    void acquire(@NotNull UUID owner, @NotNull Collection<Location> locations) {
        if (owners.containsKey(owner)) return;
        Set<ChunkKey> keys = new HashSet<>();
        for (Location location : locations) {
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("Required chunk has no world");
            keys.add(new ChunkKey(world, location.getBlockX() >> 4, location.getBlockZ() >> 4));
        }
        Set<ChunkKey> acquired = new HashSet<>();
        owners.put(owner, acquired);
        try {
            for (ChunkKey key : keys) {
                Lease lease = leases.get(key);
                if (lease == null) {
                    lease = new Lease();
                    leases.put(key, lease);
                }
                lease.owners.add(owner);
                acquired.add(key);
                ensureTicket(key, lease);
                if (owners.get(owner) != acquired) {
                    throw new IllegalStateException("Required chunk owner was released during loading");
                }
            }
        } catch (RuntimeException failure) {
            try { if (owners.get(owner) == acquired) release(owner); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** 借用ticketが外部で解除された場合も、同期20tick走査で所有ticketへ切り替えます。 */
    void refresh(@NotNull UUID owner) {
        for (ChunkKey key : Set.copyOf(owners.getOrDefault(owner, Set.of()))) {
            Lease lease = leases.get(key);
            if (lease != null) ensureTicket(key, lease);
        }
    }

    /**
     * 最後の遭遇が終了したchunkから、自分で追加したticketだけを解除します。
     * 強制world解放後もメモリ所有権を先に捨て、一箇所の失敗で他chunkを残しません。
     * @param owner 回収するボスUUID。未登録の場合は何もしません
     */
    void release(@NotNull UUID owner) {
        Set<ChunkKey> keys = owners.remove(owner);
        if (keys == null) return;
        RuntimeException failure = null;
        for (ChunkKey key : keys) {
            Lease lease = leases.get(key);
            if (lease == null || !lease.owners.remove(owner) || !lease.owners.isEmpty()) continue;
            leases.remove(key);
            try {
                if (lease.owned && key.world.isChunkLoaded(key.x, key.z)) {
                    key.world.getChunkAt(key.x, key.z).removePluginChunkTicket(plugin);
                }
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    /** 既存ticketは借用し、ticketがない場合だけ追加した所有権を記録します。 */
    private void ensureTicket(ChunkKey key, Lease lease) {
        Chunk chunk = key.world.getChunkAt(key.x, key.z);
        // ChunkLoadEventから所有ボスが破棄された場合、解除済みのleaseへticketを追加しません。
        if (leases.get(key) != lease || lease.owners.isEmpty()) {
            throw new IllegalStateException("Required chunk lease was released during loading");
        }
        if (!chunk.getPluginChunkTickets().contains(plugin)) {
            if (!chunk.addPluginChunkTicket(plugin)) {
                throw new IllegalStateException("Required chunk ticket could not be retained");
            }
            lease.owned = true;
        }
    }

    /** Bukkit world参照とchunk座標を使い、別インスタンスを混同しません。 */
    private record ChunkKey(World world, int x, int z) { }

    /** 同じchunkを必要とする遭遇集合と、自分で追加したticketの所有権です。 */
    private static final class Lease {
        private final Set<UUID> owners = new HashSet<>();
        private boolean owned;
    }
}
