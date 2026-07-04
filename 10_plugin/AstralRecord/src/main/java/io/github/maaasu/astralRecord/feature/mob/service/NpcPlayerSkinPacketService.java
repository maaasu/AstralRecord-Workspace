package io.github.maaasu.astralRecord.feature.mob.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * `entityType=PLAYER` を指定した NPC を packet 上の擬似 Player として描画する。
 */
public final class NpcPlayerSkinPacketService {

    private static final AtomicInteger NEXT_FAKE_ENTITY_ID = new AtomicInteger(-2_000_000_000);
    private static final byte PLAYER_SKIN_PARTS_ALL = (byte) 0x7F;
    private static final EnumSet<EnumWrappers.PlayerInfoAction> PLAYER_INFO_ACTIONS = EnumSet.of(
            EnumWrappers.PlayerInfoAction.ADD_PLAYER,
            EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
            EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
            EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME,
            EnumWrappers.PlayerInfoAction.UPDATE_HAT
    );

    private final Plugin plugin;
    private final MobEntityController entityController;
    private final @Nullable ProtocolManager protocolManager;
    private final Map<UUID, SkinViewState> states = new LinkedHashMap<>();
    private final Map<Integer, UUID> instanceIdByFakeEntityId = new HashMap<>();

    public NpcPlayerSkinPacketService(@NotNull Plugin plugin, @NotNull MobEntityController entityController) {
        this.plugin = plugin;
        this.entityController = entityController;
        this.protocolManager = resolveProtocolManager();
        if (this.protocolManager != null) {
            registerUseEntityRemapper(this.protocolManager);
        }
    }

    public void sync(@NotNull MobInstance instance, @NotNull Set<UUID> viewerIds) {
        if (protocolManager == null) {
            return;
        }
        if (!instance.template().usesPlayerSkinPacketView()) {
            remove(instance);
            return;
        }

        Entity realEntity = entityController.getEntity(instance);
        if (realEntity == null) {
            remove(instance);
            return;
        }

        SkinViewState state = states.computeIfAbsent(instance.instanceId(), ignored -> createState(instance));
        state.realEntityId(instance.entityId());
        Set<UUID> staleViewerIds = new HashSet<>(state.viewerIds());
        for (UUID viewerId : viewerIds) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                continue;
            }
            staleViewerIds.remove(viewerId);
            if (state.viewerIds().add(viewerId)) {
                spawnForViewer(viewer, realEntity, state, instance.currentLocation());
                continue;
            }
            syncForViewer(viewer, state, instance.currentLocation());
        }

        for (UUID staleViewerId : staleViewerIds) {
            state.viewerIds().remove(staleViewerId);
            Player viewer = Bukkit.getPlayer(staleViewerId);
            if (viewer != null && viewer.isOnline()) {
                destroyForViewer(viewer, state);
            }
        }
    }

    public void remove(@NotNull MobInstance instance) {
        if (protocolManager == null) {
            return;
        }
        SkinViewState state = states.remove(instance.instanceId());
        if (state == null) {
            return;
        }
        instanceIdByFakeEntityId.remove(state.fakeEntityId());
        for (UUID viewerId : state.viewerIds()) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                destroyForViewer(viewer, state);
            }
        }
        state.viewerIds().clear();
    }

    public void removeAll() {
        if (protocolManager == null) {
            return;
        }
        for (SkinViewState state : List.copyOf(states.values())) {
            for (UUID viewerId : state.viewerIds()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    destroyForViewer(viewer, state);
                }
            }
        }
        states.clear();
        instanceIdByFakeEntityId.clear();
    }

    private @Nullable ProtocolManager resolveProtocolManager() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            return null;
        }
        try {
            return ProtocolLibrary.getProtocolManager();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void registerUseEntityRemapper(@NotNull ProtocolManager manager) {
        manager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Client.USE_ENTITY
        ) {
            @Override
            public void onPacketReceiving(@NotNull PacketEvent event) {
                Integer fakeEntityId = event.getPacket().getIntegers().readSafely(0);
                if (fakeEntityId == null) {
                    return;
                }
                UUID instanceId = instanceIdByFakeEntityId.get(fakeEntityId);
                if (instanceId == null) {
                    return;
                }
                SkinViewState state = states.get(instanceId);
                if (state == null || !state.viewerIds().contains(event.getPlayer().getUniqueId())) {
                    return;
                }
                if (state.realEntityId() < 0) {
                    return;
                }
                event.getPacket().getIntegers().write(0, state.realEntityId());
            }
        });
    }

    private @NotNull SkinViewState createState(@NotNull MobInstance instance) {
        UUID profileUuid = UUID.nameUUIDFromBytes(
                ("astralrecord:npc-player-skin:" + instance.instanceId()).getBytes(StandardCharsets.UTF_8)
        );
        String profileName = buildProfileName(instance.instanceId());
        WrappedGameProfile profile = new WrappedGameProfile(profileUuid, profileName);
        MobSkin skin = instance.template().skin();
        if (skin != null && skin.hasSignedTexture()) {
            profile.getProperties().put(
                    "textures",
                    new WrappedSignedProperty("textures", skin.texture(), skin.signature())
            );
        }
        SkinViewState state = new SkinViewState(
                NEXT_FAKE_ENTITY_ID.getAndIncrement(),
                profileUuid,
                profile
        );
        state.realEntityId(instance.entityId());
        instanceIdByFakeEntityId.put(state.fakeEntityId(), instance.instanceId());
        return state;
    }

    private void spawnForViewer(
            @NotNull Player viewer,
            @NotNull Entity realEntity,
            @NotNull SkinViewState state,
            @NotNull Location location
    ) {
        viewer.hideEntity(plugin, realEntity);
        sendPacket(viewer, createPlayerInfoPacket(state));
        sendPacket(viewer, createNamedEntitySpawnPacket(state, location));
        sendPacket(viewer, createEntityMetadataPacket(state));
        sendPacket(viewer, createEntityHeadRotationPacket(state.fakeEntityId(), location.getYaw()));
    }

    private void syncForViewer(@NotNull Player viewer, @NotNull SkinViewState state, @NotNull Location location) {
        sendPacket(viewer, createEntityTeleportPacket(state.fakeEntityId(), location));
        sendPacket(viewer, createEntityHeadRotationPacket(state.fakeEntityId(), location.getYaw()));
    }

    private void destroyForViewer(@NotNull Player viewer, @NotNull SkinViewState state) {
        sendPacket(viewer, createEntityDestroyPacket(state.fakeEntityId()));
        sendPacket(viewer, createPlayerInfoRemovePacket(state.profileUuid()));
    }

    private void sendPacket(@NotNull Player viewer, @NotNull PacketContainer packet) {
        if (protocolManager == null) {
            return;
        }
        try {
            protocolManager.sendServerPacket(viewer, packet);
        } catch (RuntimeException ignored) {
        }
    }

    private @NotNull PacketContainer createPlayerInfoPacket(@NotNull SkinViewState state) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, PLAYER_INFO_ACTIONS);
        packet.getPlayerInfoDataLists().write(
                0,
                List.of(new PlayerInfoData(
                        state.profileUuid(),
                        0,
                        false,
                        EnumWrappers.NativeGameMode.SURVIVAL,
                        state.profile(),
                        null,
                        true,
                        null
                ))
        );
        return packet;
    }

    private @NotNull PacketContainer createNamedEntitySpawnPacket(
            @NotNull SkinViewState state,
            @NotNull Location location
    ) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
        packet.getIntegers().write(0, state.fakeEntityId());
        packet.getUUIDs().write(0, state.profileUuid());
        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());
        packet.getBytes().write(0, angleToByte(location.getYaw()));
        packet.getBytes().write(1, angleToByte(location.getPitch()));
        return packet;
    }

    private @NotNull PacketContainer createEntityMetadataPacket(@NotNull SkinViewState state) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, state.fakeEntityId());
        packet.getDataValueCollectionModifier().write(
                0,
                List.of(
                        new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), (byte) 0),
                        new WrappedDataValue(17, WrappedDataWatcher.Registry.get(Byte.class), PLAYER_SKIN_PARTS_ALL)
                )
        );
        return packet;
    }

    private @NotNull PacketContainer createEntityTeleportPacket(int fakeEntityId, @NotNull Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, fakeEntityId);
        packet.getDoubles().write(0, location.getX());
        packet.getDoubles().write(1, location.getY());
        packet.getDoubles().write(2, location.getZ());
        packet.getBytes().write(0, angleToByte(location.getYaw()));
        packet.getBytes().write(1, angleToByte(location.getPitch()));
        packet.getBooleans().write(0, true);
        return packet;
    }

    private @NotNull PacketContainer createEntityHeadRotationPacket(int fakeEntityId, float yaw) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getIntegers().write(0, fakeEntityId);
        packet.getBytes().write(0, angleToByte(yaw));
        return packet;
    }

    private @NotNull PacketContainer createEntityDestroyPacket(int fakeEntityId) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, List.of(fakeEntityId));
        return packet;
    }

    private @NotNull PacketContainer createPlayerInfoRemovePacket(@NotNull UUID profileUuid) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        packet.getUUIDLists().write(0, List.of(profileUuid));
        return packet;
    }

    private byte angleToByte(float angle) {
        return (byte) Math.floorMod(Math.round(angle * 256.0F / 360.0F), 256);
    }

    private @NotNull String buildProfileName(@NotNull UUID instanceId) {
        String compact = instanceId.toString().replace("-", "");
        return "npc_" + compact.substring(0, 11);
    }

    private static final class SkinViewState {

        private final int fakeEntityId;
        private final UUID profileUuid;
        private final WrappedGameProfile profile;
        private final Set<UUID> viewerIds = new HashSet<>();
        private int realEntityId = -1;

        private SkinViewState(int fakeEntityId, @NotNull UUID profileUuid, @NotNull WrappedGameProfile profile) {
            this.fakeEntityId = fakeEntityId;
            this.profileUuid = profileUuid;
            this.profile = profile;
        }

        private int fakeEntityId() {
            return fakeEntityId;
        }

        private @NotNull UUID profileUuid() {
            return profileUuid;
        }

        private @NotNull WrappedGameProfile profile() {
            return profile;
        }

        private @NotNull Set<UUID> viewerIds() {
            return viewerIds;
        }

        private int realEntityId() {
            return realEntityId;
        }

        private void realEntityId(int realEntityId) {
            this.realEntityId = realEntityId;
        }
    }
}
