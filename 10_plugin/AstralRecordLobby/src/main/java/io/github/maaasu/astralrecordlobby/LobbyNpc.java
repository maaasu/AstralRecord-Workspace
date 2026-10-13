package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;

/** ロビーNPCを種類ごとに1体だけ管理し、保存済みの旧個体も読込時に除去します。 */
final class LobbyNpc implements Listener {
    private final AstralRecordLobbyPlugin plugin;
    private final String configPath;
    private final String defaultName;
    private final boolean enabledByDefault;
    private final NamespacedKey key;
    private Entity entity;
    private org.bukkit.Chunk anchorChunk;
    private BukkitTask lookTask;
    private boolean started;

    LobbyNpc(AstralRecordLobbyPlugin plugin, String configPath, String key, String defaultName,
             boolean enabledByDefault) {
        this.plugin = plugin;
        this.configPath = configPath;
        this.defaultName = defaultName;
        this.enabledByDefault = enabledByDefault;
        this.key = new NamespacedKey("astralrecordlobby", key);
    }

    void start() {
        if (started) return;
        started = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        spawn();
        lookTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::lookAtNearbyPlayer, 5L, 5L);
    }

    /** 生成先チャンクを先に読み込み、残存個体を除去してから1体生成します。 */
    private void spawn() {
        World world = plugin.getServer().getWorld(
            plugin.getConfig().getString(configPath + ".world", "world"));
        Location location = world == null ? null : new Location(world,
            plugin.getConfig().getDouble(configPath + ".x", 0.5),
            plugin.getConfig().getDouble(configPath + ".y", 64.0),
            plugin.getConfig().getDouble(configPath + ".z", 0.5),
            (float) plugin.getConfig().getDouble(configPath + ".yaw", 0.0),
            (float) plugin.getConfig().getDouble(configPath + ".pitch", 0.0));
        if (enabled() && location != null) {
            anchorChunk = world.getChunkAt(location);
            anchorChunk.addPluginChunkTicket(plugin);
        }
        removeMarkedEntities();
        if (!enabled()) return;
        if (location == null) {
            LobbyLog.warning(plugin, LobbyLog.Id.NPC_WORLD_UNAVAILABLE, null, configPath,
                plugin.getConfig().getString(configPath + ".world", "world"));
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(plugin.getConfig().getString(configPath + ".type", "VILLAGER")
                .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            type = EntityType.VILLAGER;
        }
        if (!type.isSpawnable() || !type.isAlive()) type = EntityType.VILLAGER;
        entity = world.spawnEntity(location, type, false);
        entity.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        entity.setPersistent(false);
        entity.customName(Component.text(plugin.getConfig().getString(configPath + ".name", defaultName)));
        entity.setCustomNameVisible(true);
        entity.setInvulnerable(true);
        entity.setGravity(false);
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setSilent(true);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
        }
    }

    void stop() {
        started = false;
        if (lookTask != null) lookTask.cancel();
        removeMarkedEntities();
        entity = null;
        if (anchorChunk != null) anchorChunk.removePluginChunkTicket(plugin);
        anchorChunk = null;
    }

    private void removeMarkedEntities() {
        plugin.getServer().getWorlds().forEach(world -> world.getEntities().stream()
            .filter(this::matches).toList().forEach(Entity::remove));
    }

    boolean enabled() { return plugin.getConfig().getBoolean(configPath + ".enabled", enabledByDefault); }

    boolean matches(Entity candidate) {
        return candidate != null && candidate.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    double raycastDistance() {
        return Math.max(0.1, Math.min(6.0, plugin.getConfig().getDouble(configPath + ".raycastDistance", 6.0)));
    }

    /** 遅れてロードされたチャンクの旧NPCは、稼働中の個体以外を除去します。 */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().stream().filter(this::matches)
            .filter(candidate -> entity == null || !candidate.getUniqueId().equals(entity.getUniqueId()))
            .toList().forEach(Entity::remove);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (started && entity == null && event.getWorld().getName().equals(
            plugin.getConfig().getString(configPath + ".world", "world"))) spawn();
    }

    /** AIを有効化せず、最寄りの可視プレイヤーの目へ向きを更新します。 */
    void lookAtNearbyPlayer() {
        if (!(entity instanceof LivingEntity living) || !living.isValid()) return;
        double radius = Math.max(0.0, Math.min(32.0,
            plugin.getConfig().getDouble(configPath + ".lookDistance", 8.0)));
        Location eye = living.getEyeLocation();
        Player closest = null;
        double closestDistance = radius * radius;
        for (Player player : living.getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead()) continue;
            double distance = eye.distanceSquared(player.getEyeLocation());
            if (distance > closestDistance || !living.hasLineOfSight(player)) continue;
            closestDistance = distance;
            closest = player;
        }
        if (closest == null) return;
        var direction = closest.getEyeLocation().toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-6) return;
        Location rotation = eye.clone().setDirection(direction);
        living.setRotation(rotation.getYaw(), rotation.getPitch());
    }
}
