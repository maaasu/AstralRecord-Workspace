package io.github.maaasu.astralRecord.feature.item.executor;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 通常攻撃の発動地点に、閲覧者専用の短いリソースパック演出を送信します。 */
public final class WeaponAttackPackDisplay {
    // Geyser 拡張の WeaponAttackEntity と共有するマーカー。UUID 下位 1 byte にスタイルを入れる。
    private static final long BEDROCK_MARKER = 0x4153545241544b31L;
    private static final Key FONT = Key.key("astralrecord", "weapon_attack");
    private static final String GLYPH_TRANSLATION = "astralrecord.weapon_attack.glyph";
    private static final int FIRST_GLYPH = 0xe100;
    private static final double VIEW_DISTANCE = 24.0D;
    private static final int LIFETIME_TICKS = 5;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int TEXT_INDEX = 23;
    private static final int BACKGROUND_INDEX = 25;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);

    private final Plugin plugin;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final ResourcePackService resourcePacks;
    private final PlayerSettingService playerSettings;
    private final ParticleDisplayService particles;
    private final Map<Integer, AttackDisplay> activeDisplays = new HashMap<>();
    private final Map<Integer, List<WrappedDataValue>> glyphMetadata = new HashMap<>();

    /**
     * 通常攻撃演出の送信を準備します。
     * @param plugin 演出の終了タスクを登録するプラグイン
     * @param resourcePacks 閲覧者の Java パック適用状態を管理するサービス
     * @param playerSettings 閲覧者の粒子密度を管理するサービス
     * @param particles Java パック未適用時の粒子を表示するサービス
     */
    public WeaponAttackPackDisplay(Plugin plugin, ResourcePackService resourcePacks,
                                   PlayerSettingService playerSettings, ParticleDisplayService particles) {
        this.plugin = plugin;
        this.resourcePacks = resourcePacks;
        this.playerSettings = playerSettings;
        this.particles = particles;
    }

    /** 組み込み武器の演出を受け持った場合は true を返します。 */
    boolean show(Player attacker, String skillId, Location location, Particle fallback) {
        int style = switch (skillId) {
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE -> 0;
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER -> 1;
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR -> 2;
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW,
                 BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW,
                 BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW -> 3;
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND -> 4;
            case BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC -> 5;
            default -> -1;
        };
        World world = location.getWorld();
        if (style < 0 || world == null) {
            return false;
        }
        try {
            showBurst(attacker, location, world, fallback, style);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9012, exception, world.getName());
            return false;
        }
        return true;
    }

    private void showBurst(Player attacker, Location location, World world, Particle fallback, int style) {
        List<Player> javaViewers = new ArrayList<>();
        List<Player> bedrockViewers = new ArrayList<>();
        for (Player viewer : world.getNearbyPlayers(location, VIEW_DISTANCE)) {
            if (!viewer.canSee(attacker) || viewer.getLocation().distanceSquared(location) > VIEW_DISTANCE * VIEW_DISTANCE) {
                continue;
            }
            var player = AstPlayerCache.get(viewer);
            double density = player == null ? ParticleDensity.NORMAL.getDensityScale()
                    : playerSettings.getCachedParticleDensityScale(player.getUser().getUuid());
            if (density <= 0.0D) {
                continue;
            }
            if (player == null ? resourcePacks.isBedrock(viewer.getName()) : player.isBedrock()) {
                if (shouldShowBurst(density)) bedrockViewers.add(viewer);
            } else if (resourcePacks.hasManagedJavaPack(viewer.getUniqueId())) {
                if (shouldShowBurst(density)) javaViewers.add(viewer);
            } else {
                particles.spawnForViewer(viewer, location, fallback, 1, 0, 0, 0, 0, density);
            }
        }
        if (javaViewers.isEmpty() && bedrockViewers.isEmpty()) {
            return;
        }

        int entityId = PacketEntityIdAllocator.nextEntityId();
        PacketContainer spawn = spawnPacket(entityId, style, location.clone().subtract(0, 0.18D, 0));
        PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        destroy.getIntLists().writeSafely(0, List.of(entityId));
        destroy.getIntegerArrays().writeSafely(0, new int[]{entityId});

        PacketContainer javaSpawn = null;
        if (!javaViewers.isEmpty()) {
            PacketContainer metadata = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            metadata.getIntegers().writeSafely(0, entityId);
            metadata.getDataValueCollectionModifier().write(0, glyphMetadata.computeIfAbsent(style, this::glyphMetadata));
            javaSpawn = protocol.createPacket(PacketType.Play.Server.BUNDLE);
            javaSpawn.getPacketBundles().write(0, List.of(spawn, metadata));
        }
        List<Player> viewers = new ArrayList<>(javaViewers);
        viewers.addAll(bedrockViewers);
        BukkitTask removeTask = plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> remove(activeDisplays.remove(entityId)), LIFETIME_TICKS);
        activeDisplays.put(entityId, new AttackDisplay(viewers, destroy, removeTask));
        for (Player viewer : javaViewers) {
            send(viewer, javaSpawn);
        }
        for (Player viewer : bedrockViewers) {
            send(viewer, spawn);
        }
    }

    private static boolean shouldShowBurst(double density) {
        return density >= 1.0D || ThreadLocalRandom.current().nextDouble() < density;
    }

    private PacketContainer spawnPacket(int entityId, int style, Location location) {
        long markerData = (ThreadLocalRandom.current().nextLong() & ~0xffL) | style;
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getUUIDs().writeSafely(0, new UUID(BEDROCK_MARKER, markerData));
        packet.getEntityTypeModifier().writeSafely(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        return packet;
    }

    private List<WrappedDataValue> glyphMetadata(int style) {
        Component glyph = Component.text(Character.toString(FIRST_GLYPH + style)).font(FONT);
        // 旧管理パックでは翻訳キーが未定義のため、バニラフォントの星を描画する。
        Component display = Component.translatable(GLYPH_TRANSLATION, "★", glyph);
        WrappedChatComponent text = WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(display));
        return List.of(
                scale(1.25f), value(DISPLAY_BILLBOARD_INDEX, Byte.class, (byte) Display.Billboard.CENTER.ordinal()),
                value(DISPLAY_BRIGHTNESS_INDEX, Integer.class, FULL_BRIGHT),
                WrappedDataValue.fromWrappedValue(TEXT_INDEX, WrappedDataWatcher.Registry.getChatComponentSerializer(false), text),
                value(BACKGROUND_INDEX, Integer.class, 0)
        );
    }

    private static WrappedDataValue scale(float size) {
        Vector3f scale = new Vector3f(size);
        try {
            return value(DISPLAY_SCALE_INDEX, Vector3f.class, scale);
        } catch (IllegalArgumentException ignored) {
            return value(DISPLAY_SCALE_INDEX, Vector3fc.class, scale);
        }
    }

    private static WrappedDataValue value(int index, Type type, Object value) {
        return WrappedDataValue.fromWrappedValue(index, WrappedDataWatcher.Registry.get(type), value);
    }

    void stop() {
        for (AttackDisplay display : activeDisplays.values()) {
            display.removeTask().cancel();
            remove(display);
        }
        activeDisplays.clear();
    }

    private void remove(AttackDisplay display) {
        for (Player viewer : display.viewers()) {
            if (viewer.isOnline()) send(viewer, display.destroy());
        }
    }

    private void send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9012, exception, viewer.getWorld().getName());
        }
    }

    private record AttackDisplay(List<Player> viewers, PacketContainer destroy, BukkitTask removeTask) {}
}
