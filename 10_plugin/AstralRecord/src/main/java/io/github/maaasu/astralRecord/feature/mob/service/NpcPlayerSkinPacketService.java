package io.github.maaasu.astralRecord.feature.mob.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
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
    private static final int PLAYER_SKIN_PARTS_METADATA_INDEX = 16;
    private static final byte PLAYER_SKIN_PARTS_ALL = (byte) 0x7F;
    /** クライアントがスキン付き GameProfile を解決するために tab list へ保持する時間。 */
    private static final long SKIN_PROFILE_RETENTION_TICKS = 20L;
    private static final EnumSet<EnumWrappers.PlayerInfoAction> PLAYER_INFO_ACTIONS = EnumSet.of(
            EnumWrappers.PlayerInfoAction.ADD_PLAYER,
            EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
            EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
            EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME,
            EnumWrappers.PlayerInfoAction.UPDATE_HAT
    );
    private static final EnumSet<EnumWrappers.PlayerInfoAction> PLAYER_INFO_HIDE_ACTIONS = EnumSet.of(
            EnumWrappers.PlayerInfoAction.UPDATE_LISTED
    );

    private final Plugin plugin;
    private final MobEntityController entityController;
    private @Nullable ProtocolManager protocolManager;
    private boolean useEntityRemapperRegistered;
    private final Map<UUID, SkinViewState> states = new LinkedHashMap<>();
    private final Map<UUID, SkinViewState> temporaryStates = new LinkedHashMap<>();
    private final Map<Integer, UUID> instanceIdByFakeEntityId = new HashMap<>();

    public NpcPlayerSkinPacketService(@NotNull Plugin plugin, @NotNull MobEntityController entityController) {
        this.plugin = plugin;
        this.entityController = entityController;
    }

    /**
     * オンラインプレイヤーの現在の署名付きスキンを、指定位置へ一時表示します。
     *
     * @param viewer        仮想 Player を表示するプレイヤー
     * @param skinSource    スキンを取得するオンラインプレイヤー
     * @param location      仮想 Player の表示位置
     * @param durationTicks 表示時間（tick）。正数で指定してください
     * @return 表示パケットの送信を開始できた場合は {@code true}、ProtocolLib またはスキン情報がない場合は {@code false}
     */
    public boolean showTemporaryPlayerSkin(
            @NotNull Player viewer,
            @NotNull Player skinSource,
            @NotNull Location location,
            long durationTicks
    ) {
        if (!skinSource.isOnline()) {
            return false;
        }

        MobSkin skin = resolveSignedSkin(skinSource);
        return skin != null && showTemporaryPlayerSkin(viewer, skin, location, durationTicks);
    }

    /**
     * 指定された署名付きプレイヤースキンを、指定位置へ一時表示します。
     *
     * @param viewer        仮想 Player を表示するプレイヤー
     * @param skin          Base64 テクスチャ値と署名値を持つスキン
     * @param location      仮想 Player の表示位置
     * @param durationTicks 表示時間（tick）。正数で指定してください
     * @return 表示パケットの送信を開始できた場合は {@code true}、入力または表示環境が不正な場合は {@code false}
     */
    public boolean showTemporaryPlayerSkin(
            @NotNull Player viewer,
            @NotNull MobSkin skin,
            @NotNull Location location,
            long durationTicks
    ) {
        if (ensureProtocolManager() == null
                || durationTicks <= 0L
                || !viewer.isOnline()
                || !skin.hasSignedTexture()
                || location.getWorld() == null) {
            return false;
        }

        UUID profileUuid = UUID.randomUUID();
        SkinViewState state = createState(
                profileUuid,
                buildTemporaryProfileName(profileUuid),
                skin
        );
        state.viewerIds().add(viewer.getUniqueId());
        spawnTemporaryForViewer(viewer, state, location);
        temporaryStates.put(state.profileUuid(), state);
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeTemporaryForViewer(viewer, state), durationTicks);
        return true;
    }

    public void sync(@NotNull MobInstance instance, @NotNull Set<UUID> viewerIds) {
        if (ensureProtocolManager() == null) {
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
        if (ensureProtocolManager() == null) {
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
        if (ensureProtocolManager() == null) {
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
        for (SkinViewState state : List.copyOf(temporaryStates.values())) {
            for (UUID viewerId : state.viewerIds()) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    destroyForViewer(viewer, state);
                }
            }
        }
        temporaryStates.clear();
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

    private @Nullable ProtocolManager ensureProtocolManager() {
        if (protocolManager == null) {
            protocolManager = resolveProtocolManager();
        }
        if (protocolManager != null && !useEntityRemapperRegistered) {
            registerUseEntityRemapper(protocolManager);
            useEntityRemapperRegistered = true;
        }
        return protocolManager;
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
        SkinViewState state = createState(profileUuid, buildProfileName(instance.instanceId()), instance.template().skin());
        state.realEntityId(instance.entityId());
        instanceIdByFakeEntityId.put(state.fakeEntityId(), instance.instanceId());
        return state;
    }

    private @NotNull SkinViewState createState(
            @NotNull UUID profileUuid,
            @NotNull String profileName,
            @Nullable MobSkin skin
    ) {
        WrappedGameProfile profile = new WrappedGameProfile(profileUuid, profileName);
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
        return state;
    }

    private @Nullable MobSkin resolveSignedSkin(@NotNull Player player) {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (!"textures".equals(property.getName())) {
                continue;
            }
            MobSkin skin = new MobSkin(property.getValue(), property.getSignature());
            if (skin.hasSignedTexture()) {
                return skin;
            }
        }
        return null;
    }

    private void spawnForViewer(
            @NotNull Player viewer,
            @NotNull Entity realEntity,
            @NotNull SkinViewState state,
            @NotNull Location location
    ) {
        viewer.hideEntity(plugin, realEntity);
        sendPacket(viewer, createPlayerInfoPacket(state));
        sendPacket(viewer, createPlayerSpawnPacket(state, location));
        sendPacket(viewer, createEntityMetadataPacket(state));
        sendPacket(viewer, createEntityHeadRotationPacket(state.fakeEntityId(), location.getYaw()));
        hideFromPlayerListNextTick(viewer, state);
    }

    private void spawnTemporaryForViewer(
            @NotNull Player viewer,
            @NotNull SkinViewState state,
            @NotNull Location location
    ) {
        sendPacket(viewer, createPlayerInfoPacket(state));
        sendPacket(viewer, createPlayerSpawnPacket(state, location));
        sendPacket(viewer, createEntityMetadataPacket(state));
        sendPacket(viewer, createEntityHeadRotationPacket(state.fakeEntityId(), location.getYaw()));
        hideFromPlayerListNextTick(viewer, state);
    }

    private void removeTemporaryForViewer(@NotNull Player viewer, @NotNull SkinViewState state) {
        if (!temporaryStates.remove(state.profileUuid(), state)) {
            return;
        }
        if (viewer.isOnline()) {
            destroyForViewer(viewer, state);
        }
        state.viewerIds().clear();
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
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_5706, exception, packet.getType().name(), viewer.getWorld().getName());
        }
    }

    /**
     * スキンを含む GameProfile がクライアントへ反映されるまで待機してから、NPC を tab list から除外します。
     *
     * @param viewer 表示先プレイヤー
     * @param state  対象 NPC の表示状態
     */
    private void hideFromPlayerListNextTick(@NotNull Player viewer, @NotNull SkinViewState state) {
        UUID viewerId = viewer.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline() || !state.viewerIds().contains(viewerId)) {
                return;
            }
            sendPacket(viewer, createPlayerInfoHidePacket(state));
        }, SKIN_PROFILE_RETENTION_TICKS);
    }

    private @NotNull PacketContainer createPlayerInfoPacket(@NotNull SkinViewState state) {
        return createPlayerInfoPacket(state, PLAYER_INFO_ACTIONS, true);
    }

    private @NotNull PacketContainer createPlayerInfoHidePacket(@NotNull SkinViewState state) {
        return createPlayerInfoPacket(state, PLAYER_INFO_HIDE_ACTIONS, false);
    }

    private @NotNull PacketContainer createPlayerInfoPacket(
            @NotNull SkinViewState state,
            @NotNull EnumSet<EnumWrappers.PlayerInfoAction> actions,
            boolean listed
    ) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.PLAYER_INFO);
        // PacketContainer の共有 StructureModifier は、EnumSet 用の変換器を再利用して ArrayList を生成する
        // ことがあるため、変換器を持たない modifier を新規作成して NMS EnumSet を直接設定します。
        StructureModifier<Object> rawModifier = new StructureModifier<>(
                packet.getHandle().getClass(),
                Object.class,
                false
        ).withTarget(packet.getHandle());
        rawModifier
                .withType(EnumSet.class)
                .write(0, toNativePlayerInfoActions(actions));
        packet.getPlayerInfoDataLists().write(
                0,
                List.of(new PlayerInfoData(
                        state.profileUuid(),
                        0,
                        listed,
                        EnumWrappers.NativeGameMode.SURVIVAL,
                        state.profile(),
                        null,
                        true,
                        null
                ))
        );
        return packet;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private @NotNull EnumSet<?> toNativePlayerInfoActions(
            @NotNull EnumSet<EnumWrappers.PlayerInfoAction> actions
    ) {
        EnumSet nativeActions = EnumWrappers.createEmptyEnumSet(
                EnumWrappers.getPlayerInfoActionClass()
        );
        for (EnumWrappers.PlayerInfoAction action : actions) {
            nativeActions.add(EnumWrappers.getPlayerInfoActionConverter().getGeneric(action));
        }
        return nativeActions;
    }

    private @NotNull PacketContainer createPlayerSpawnPacket(
            @NotNull SkinViewState state,
            @NotNull Location location
    ) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, state.fakeEntityId());
        packet.getUUIDs().writeSafely(0, state.profileUuid());
        packet.getEntityTypeModifier().writeSafely(0, EntityType.PLAYER);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        packet.getBytes().writeSafely(0, angleToByte(location.getPitch()));
        packet.getBytes().writeSafely(1, angleToByte(location.getYaw()));
        packet.getBytes().writeSafely(2, angleToByte(location.getYaw()));
        return packet;
    }

    private @NotNull PacketContainer createEntityMetadataPacket(@NotNull SkinViewState state) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, state.fakeEntityId());
        packet.getDataValueCollectionModifier().write(
                0,
                List.of(
                        metadataValue(0, Byte.class, (byte) 0),
                        metadataValue(PLAYER_SKIN_PARTS_METADATA_INDEX, Byte.class, PLAYER_SKIN_PARTS_ALL)
                )
        );
        return packet;
    }

    private @NotNull WrappedDataValue metadataValue(int index, @NotNull Type type, @Nullable Object value) {
        return WrappedDataValue.fromWrappedValue(index, WrappedDataWatcher.Registry.get(type), value);
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

    private @NotNull String buildTemporaryProfileName(@NotNull UUID profileUuid) {
        String compact = profileUuid.toString().replace("-", "");
        return "test_" + compact.substring(0, 11);
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
