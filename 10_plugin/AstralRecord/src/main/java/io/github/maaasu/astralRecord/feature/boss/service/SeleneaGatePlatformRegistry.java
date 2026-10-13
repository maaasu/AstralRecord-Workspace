package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 外周門の空白床だけを一時補完し、元の床と遭遇間の所有権を稼働中のメモリで管理します。 */
final class SeleneaGatePlatformRegistry {
    private final Map<World, WorldState> worlds = new HashMap<>();
    private final Map<UUID, Owner> owners = new HashMap<>();

    /**
     * 同期スレッドで四門の足元直下7×7だけを補完します。既存の地形と頭上は変更しません。
     * 元BlockDataをメモリに記録してから配置し、失敗時は今回の所有権を回収します。
     * DB・world PDCへは保存せず、強制終了・再起動後の残存床復旧は行いません。
     * @param owner ボスUUID。重複登録は何もしません
     * @param gates 同一専用worldの外周門。必須chunkの保持後に呼び出します
     */
    void acquire(@NotNull UUID owner, @NotNull Collection<Location> gates) {
        if (owners.containsKey(owner) || gates.isEmpty()) return;
        if (gates.size() > 4) throw new IllegalArgumentException("An encounter has at most four outer gates");
        World world = gates.iterator().next().getWorld();
        if (world == null || gates.stream().anyMatch(gate -> gate.getWorld() != world)) {
            throw new IllegalArgumentException("Gate platforms must belong to one world");
        }
        WorldState state = worlds.computeIfAbsent(world, WorldState::new);
        if (state.recovering) throw new IllegalStateException("Gate platform recovery was reentered");
        Owner lease = new Owner(state);
        owners.put(owner, lease);
        state.owners.add(owner);
        try {
            state.recovering = true;
            try { recoverUnowned(state); }
            finally { state.recovering = false; }
            requireOwner(owner, lease);
            BlockData placed = Bukkit.createBlockData(Material.DARK_OAK_PLANKS);
            String placedData = placed.getAsString();
            for (Location gate : gates) {
                if (!Double.isFinite(gate.getX()) || !Double.isFinite(gate.getY()) || !Double.isFinite(gate.getZ())
                        || Math.abs(gate.getX()) > 29_999_980.0D || Math.abs(gate.getZ()) > 29_999_980.0D) continue;
                int floorY = gate.getBlockY() - 1;
                if (floorY < world.getMinHeight() || floorY >= world.getMaxHeight()) continue;
                for (int x = gate.getBlockX() - 3; x <= gate.getBlockX() + 3; x++) {
                    for (int z = gate.getBlockZ() - 3; z <= gate.getBlockZ() + 3; z++) {
                        Cell key = new Cell(x, floorY, z);
                        Platform existing = state.platforms.get(key);
                        if (existing == null) {
                            BlockData original = world.getBlockAt(x, floorY, z).getBlockData();
                            requireOwner(owner, lease);
                            if (!replaceable(original.getMaterial())) continue;
                            existing = new Platform(original.getAsString(), placedData);
                            state.platforms.put(key, existing);
                        }
                        existing.owners.add(owner);
                        lease.cells.add(key);
                    }
                }
            }
            // 配置より先に全原状をメモリへ記録し、途中まで置かれた床も同じ遭遇で回収します。
            requireOwner(owner, lease);
            for (Cell key : Set.copyOf(lease.cells)) {
                Platform platform = state.platforms.get(key);
                Block block = world.getBlockAt(key.x, key.y, key.z);
                BlockData current = block.getBlockData();
                requireOwner(owner, lease);
                if (platform != null && current.getAsString().equals(platform.original)) {
                    block.setBlockData(placed.clone(), false);
                    requireOwner(owner, lease);
                }
            }
        } catch (RuntimeException failure) {
            try { if (owners.get(owner) == lease) release(owner); }
            catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        } finally {
            if (state.owners.isEmpty() && state.platforms.isEmpty()) worlds.remove(world, state);
        }
    }

    /**
     * 最後の所有者だけが元BlockDataを戻します。外部で変更された床は上書きしません。
     * 回収失敗のセルはメモリへ残し、同じ稼働中のworldで次の遭遇が始まるときに再試行します。
     * @param owner 終了するボスUUID。未登録なら何もしません
     */
    void release(@NotNull UUID owner) {
        Owner lease = owners.remove(owner);
        if (lease == null) return;
        WorldState state = lease.state;
        state.owners.remove(owner);
        RuntimeException failure = null;
        for (Cell key : lease.cells) {
            Platform platform = state.platforms.get(key);
            if (platform == null || !platform.owners.remove(owner) || !platform.owners.isEmpty()) continue;
            try {
                Block block = state.world.getBlockAt(key.x, key.y, key.z);
                BlockData current = block.getBlockData();
                // chunk読込callback中に別遭遇が合流した場合は、その所有床を残します。
                if (!platform.owners.isEmpty()) continue;
                if (current.getAsString().equals(platform.placed)) {
                    block.setBlockData(Bukkit.createBlockData(platform.original), false);
                }
                state.platforms.remove(key, platform);
            } catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        if (state.owners.isEmpty() && state.platforms.isEmpty() && !state.recovering) worlds.remove(state.world, state);
        if (failure != null) throw failure;
    }

    /** 足元の体幅と頭上2mを検査し、固定座標へ安全に着地できる場合だけtrueを返します。 */
    static boolean safeLanding(@NotNull Location target) {
        World world = target.getWorld();
        if (world == null || !Double.isFinite(target.getX()) || !Double.isFinite(target.getY())
                || !Double.isFinite(target.getZ()) || target.getY() - 0.05D < world.getMinHeight()
                || target.getY() + 1.9D >= world.getMaxHeight()) return false;
        for (double x : new double[]{-0.31D, 0.31D}) {
            for (double z : new double[]{-0.31D, 0.31D}) {
                Location feet = target.clone().add(x, 0.0D, z);
                if (!world.isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4)) return false;
                Block floor = world.getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() - 0.05D), feet.getBlockZ());
                Material material = floor.getType();
                if (!material.isSolid() || floor.isPassable() || floor.isLiquid() || harmful(material)
                        || Math.abs(floor.getBoundingBox().getMaxY() - target.getY()) > 0.01D) return false;
                for (double y : new double[]{0.1D, 1.0D, 1.9D}) {
                    Block body = world.getBlockAt(feet.getBlockX(), (int) Math.floor(feet.getY() + y), feet.getBlockZ());
                    if (!body.isPassable() || body.isLiquid() || harmful(body.getType())) return false;
                }
            }
        }
        return true;
    }

    /** AIR三種と建築用LIGHTだけを床の補完対象にします。 */
    private static boolean replaceable(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR || material == Material.LIGHT;
    }

    /** 既存の接触ダメージ床・炎・茂みを着地候補から除外します。 */
    private static boolean harmful(Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
                || material == Material.CACTUS || material == Material.FIRE || material == Material.SOUL_FIRE
                || material == Material.SWEET_BERRY_BUSH || material == Material.POWDER_SNOW;
    }

    /** 稼働中に回収できなかった所有者なしの床だけを再試行し、他遭遇の所有床には触れません。 */
    private void recoverUnowned(WorldState state) {
        RuntimeException failure = null;
        for (Cell key : Set.copyOf(state.platforms.keySet())) {
            Platform platform = state.platforms.get(key);
            if (platform == null || !platform.owners.isEmpty()) continue;
            try {
                Block block = state.world.getBlockAt(key.x, key.y, key.z);
                BlockData current = block.getBlockData();
                // chunk読込callback中に別遭遇が所有した場合は、その床を残します。
                if (!platform.owners.isEmpty()) continue;
                if (current.getAsString().equals(platform.placed)) {
                    block.setBlockData(Bukkit.createBlockData(platform.original), false);
                }
                state.platforms.remove(key, platform);
            } catch (RuntimeException recoveryFailure) {
                if (failure == null) failure = recoveryFailure;
                else if (failure != recoveryFailure) failure.addSuppressed(recoveryFailure);
            }
        }
        if (failure != null) throw failure;
    }

    /** 読込・配置callbackで遭遇が終了した場合、後続の配置を止めます。 */
    private void requireOwner(UUID owner, Owner lease) {
        if (owners.get(owner) != lease) throw new IllegalStateException("Gate platform owner was released during preparation");
    }

    /** 一worldの現在の所有床です。最後の遭遇の終了後、床の回収完了時にworld参照も解放します。 */
    private static final class WorldState {
        private final World world;
        private final Map<Cell, Platform> platforms = new HashMap<>();
        private final Set<UUID> owners = new HashSet<>();
        private boolean recovering;
        private WorldState(World world) { this.world = world; }
    }

    /** 一遭遇のworldと重複排除済みの所有セルです。 */
    private static final class Owner {
        private final WorldState state;
        private final Set<Cell> cells = new HashSet<>();
        private Owner(WorldState state) { this.state = state; }
    }

    /** 元データ、配置データ、共有する遭遇UUIDを保持します。 */
    private static final class Platform {
        private final String original;
        private final String placed;
        private final Set<UUID> owners = new HashSet<>();
        private Platform(String original, String placed) { this.original = original; this.placed = placed; }
    }

    private record Cell(int x, int y, int z) { }
}
