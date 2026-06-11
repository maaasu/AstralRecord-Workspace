package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double ADMIN_ITEM_Y_OFFSET = 0.15D;
    private static final double NODE_ITEM_Y_OFFSET = 1.15D;
    private static final float EDGE_THICKNESS = 0.045F;
    private static final double EDGE_Y_OFFSET = 0.02D;
    private static final double TEXT_Y_OFFSET = 1.2D;
    private static final float NODE_ITEM_SCALE = 0.72F;
    private static final float NODE_TEXT_SCALE = 0.85F;
    private static final float NODE_TEXT_COMPACT_SCALE = 0.72F;
    private static final float ADMIN_ITEM_SCALE = 0.72F;
    private static final float ADMIN_TEXT_SCALE = 0.72F;
    private static final int NODE_LIGHT_LEVEL = 15;

    private final Plugin plugin;
    private final SkillTreeService service;
    private final SkillTreePacketDisplay packetDisplay;
    private final Map<String, NodeVisual> nodeVisuals = new HashMap<>();
    private final Map<String, AdminPositionVisual> adminPositionVisuals = new HashMap<>();
    private final Map<String, EdgeVisual> edgeVisuals = new HashMap<>();
    private final Set<String> loggedInvalidPositions = new HashSet<>();
    private final Set<String> loggedInvalidEdges = new HashSet<>();
    private final Set<UUID> dirtyViewers = new HashSet<>();
    private final Map<UUID, Set<String>> dirtyNodeStatePositionIds = new HashMap<>();
    private boolean structureDirty = true;
    private BukkitTask task;

    SkillTreeVisualizer(@NotNull Plugin plugin, @NotNull SkillTreeService service) {
        this.plugin = plugin;
        this.service = service;
        this.packetDisplay = new SkillTreePacketDisplay(plugin);
    }

    void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, INTERVAL_TICKS);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nodeVisuals.values().forEach(NodeVisual::remove);
        adminPositionVisuals.values().forEach(AdminPositionVisual::remove);
        edgeVisuals.values().forEach(EdgeVisual::remove);
        nodeVisuals.clear();
        adminPositionVisuals.clear();
        edgeVisuals.clear();
    }

    void markStructureDirty() {
        structureDirty = true;
        dirtyViewers.addAll(currentOnlineViewerIds());
    }

    void markViewerDirty(@NotNull UUID viewerId) {
        dirtyViewers.add(viewerId);
    }

    void markNodeStateDirty(@NotNull UUID viewerId, @NotNull Set<String> positionIds) {
        dirtyViewers.add(viewerId);
        dirtyNodeStatePositionIds.computeIfAbsent(viewerId, ignored -> new HashSet<>()).addAll(positionIds);
    }

    private void tick() {
        if (structureDirty || !dirtyViewers.isEmpty()) {
            syncVisuals();
            structureDirty = false;
        }

        Set<UUID> onlineViewerIds = currentOnlineViewerIds();
        Set<UUID> viewersToRefresh = new HashSet<>(dirtyViewers);
        viewersToRefresh.retainAll(onlineViewerIds);

        for (UUID viewerId : viewersToRefresh) {
            Player player = plugin.getServer().getPlayer(viewerId);
            if (player == null) {
                continue;
            }
            refreshViewer(player, dirtyNodeStatePositionIds.remove(viewerId));
        }

        dirtyViewers.removeAll(viewersToRefresh);
        dirtyNodeStatePositionIds.keySet().removeIf(viewerId -> !onlineViewerIds.contains(viewerId));
        nodeVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
        adminPositionVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
        edgeVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
    }

    private @NotNull Set<UUID> currentOnlineViewerIds() {
        Set<UUID> onlineViewerIds = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            onlineViewerIds.add(player.getUniqueId());
        }
        return onlineViewerIds;
    }

    private void refreshViewer(@NotNull Player player, @Nullable Set<String> dirtyPositions) {
        RenderMode mode = resolveMode(player);
        boolean partialNodeRefresh = dirtyPositions != null && !dirtyPositions.isEmpty() && mode == RenderMode.PLAYER;

        for (AdminPositionVisual visual : adminPositionVisuals.values()) {
            boolean visible = mode == RenderMode.ADMIN && isVisibleTo(player, visual.baseLocation());
            visual.updateViewer(player, visible);
        }
        for (NodeVisual visual : nodeVisuals.values()) {
            if (partialNodeRefresh && !dirtyPositions.contains(visual.node().positionId())) {
                continue;
            }
            boolean visible = mode == RenderMode.PLAYER && isVisibleTo(player, visual.baseLocation());
            SkillTreeService.NodePresentationState nodeState = visible
                    ? resolveNodeState(player, visual.node())
                    : SkillTreeService.NodePresentationState.BLOCKED;
            SkillTreeService.NodeLabelDetail labelDetail = visible
                    ? service.nodeLabelDetail(player, visual.baseLocation())
                    : SkillTreeService.NodeLabelDetail.HIDDEN;
            visual.updateViewer(player, visible, nodeState, labelDetail);
        }
        for (EdgeVisual visual : edgeVisuals.values()) {
            if (partialNodeRefresh
                    && !dirtyPositions.contains(visual.edge().leftPositionId())
                    && !dirtyPositions.contains(visual.edge().rightPositionId())) {
                continue;
            }
            EdgeState state = resolveEdgeState(player, visual.edge(), mode);
            boolean visible = state != EdgeState.HIDDEN && isVisibleTo(player, visual.midpoint());
            visual.updateViewer(player, visible ? state : EdgeState.HIDDEN);
        }
    }

    private void syncVisuals() {
        Collection<SkillTreePosition> positions = service.getPositions();
        Set<String> activePositionIds = new HashSet<>();
        for (SkillTreePosition position : positions) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                if (loggedInvalidPositions.add(position.positionId())) {
                    Logger.log(LogId.W_9000, position.positionId(), position.worldName(), "location_resolve_failed");
                }
                continue;
            }
            if (!isChunkLoaded(location)) {
                removeAdminPositionVisual(position.positionId());
                removeNodeVisual(position.positionId());
                continue;
            }
            ensureNodeLight(location);
            loggedInvalidPositions.remove(position.positionId());
            activePositionIds.add(position.positionId());

            AdminPositionVisual adminVisual = adminPositionVisuals.get(position.positionId());
            if (adminVisual == null || !adminVisual.isValid()) {
                if (adminVisual != null) {
                    adminVisual.remove();
                }
                adminPositionVisuals.put(position.positionId(), new AdminPositionVisual(position.positionId(), location));
            } else {
                adminVisual.teleport(location);
            }

            SkillTreeNodeDefinition node = service.getNodeByPositionId(position.positionId());
            if (node == null) {
                removeNodeVisual(position.positionId());
                continue;
            }

            NodeVisual nodeVisual = nodeVisuals.get(position.positionId());
            if (nodeVisual == null || !nodeVisual.isValid() || !nodeVisual.node().id().equals(node.id())) {
                removeNodeVisual(position.positionId());
                nodeVisuals.put(position.positionId(), new NodeVisual(node, location));
            } else {
                nodeVisual.teleport(location);
            }
        }

        adminPositionVisuals.entrySet().removeIf(entry -> {
            if (activePositionIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
        nodeVisuals.entrySet().removeIf(entry -> {
            if (activePositionIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });

        Set<String> activeEdgeKeys = new HashSet<>();
        for (SkillTreeEdge edge : service.getEdges()) {
            SkillTreePosition left = service.getPosition(edge.leftPositionId());
            SkillTreePosition right = service.getPosition(edge.rightPositionId());
            if (left == null || right == null) {
                continue;
            }
            Location leftLocation = left.toLocation();
            Location rightLocation = right.toLocation();
            if (leftLocation == null
                    || rightLocation == null
                    || leftLocation.getWorld() == null
                    || leftLocation.getWorld() != rightLocation.getWorld()) {
                if (loggedInvalidEdges.add(edge.key())) {
                    Logger.log(
                            LogId.W_9000,
                            edge.key(),
                            left == null ? "null" : left.worldName(),
                            "edge_location_resolve_failed"
                    );
                }
                continue;
            }
            if (!isChunkLoaded(leftLocation) || !isChunkLoaded(rightLocation)) {
                removeEdgeVisual(edge.key());
                continue;
            }
            loggedInvalidEdges.remove(edge.key());

            activeEdgeKeys.add(edge.key());
            EdgeVisual edgeVisual = edgeVisuals.get(edge.key());
            if (edgeVisual == null || !edgeVisual.isValid()) {
                if (edgeVisual != null) {
                    edgeVisual.remove();
                }
                edgeVisuals.put(edge.key(), new EdgeVisual(edge, leftLocation, rightLocation));
            } else {
                edgeVisual.teleport(leftLocation, rightLocation);
            }
        }

        edgeVisuals.entrySet().removeIf(entry -> {
            if (activeEdgeKeys.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    private void removeNodeVisual(@NotNull String positionId) {
        NodeVisual removed = nodeVisuals.remove(positionId);
        if (removed != null) {
            removed.remove();
        }
    }

    private void removeAdminPositionVisual(@NotNull String positionId) {
        AdminPositionVisual removed = adminPositionVisuals.remove(positionId);
        if (removed != null) {
            removed.remove();
        }
    }

    private void removeEdgeVisual(@NotNull String edgeKey) {
        EdgeVisual removed = edgeVisuals.remove(edgeKey);
        if (removed != null) {
            removed.remove();
        }
    }

    private @NotNull RenderMode resolveMode(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (service.isAdminMode(astPlayer)) {
            return RenderMode.ADMIN;
        }
        if (service.isPlayerModeSkillTree(player) && service.isSkillTreeVisualReady(player)) {
            return RenderMode.PLAYER;
        }
        return RenderMode.HIDDEN;
    }

    private @NotNull EdgeState resolveEdgeState(
            @NotNull Player player,
            @NotNull SkillTreeEdge edge,
            @NotNull RenderMode mode
    ) {
        if (mode == RenderMode.ADMIN) {
            return EdgeState.ADMIN;
        }
        if (mode != RenderMode.PLAYER) {
            return EdgeState.HIDDEN;
        }

        SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
        SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
        if (leftNode == null || rightNode == null) {
            return EdgeState.HIDDEN;
        }

        boolean leftUnlocked = resolveNodeState(player, leftNode) == SkillTreeService.NodePresentationState.UNLOCKED;
        boolean rightUnlocked = resolveNodeState(player, rightNode) == SkillTreeService.NodePresentationState.UNLOCKED;
        if (leftUnlocked && rightUnlocked) {
            return EdgeState.UNLOCKED;
        }
        if (leftUnlocked || rightUnlocked) {
            return EdgeState.CONNECTED;
        }
        return service.viewOptions(player).edgeDisplayMode() == SkillTreeService.SkillTreeEdgeDisplayMode.ALL
                ? EdgeState.LOCKED
                : EdgeState.HIDDEN;
    }

    private @NotNull SkillTreeService.NodePresentationState resolveNodeState(
            @NotNull Player player,
            @NotNull SkillTreeNodeDefinition node
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return SkillTreeService.NodePresentationState.BLOCKED;
        }
        return service.nodePresentationState(astPlayer, node);
    }

    private boolean isVisibleTo(@NotNull Player player, @Nullable Location location) {
        double viewDistance = service.viewOptions(player).viewDistance();
        return location != null
                && location.getWorld() != null
                && player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= viewDistance * viewDistance;
    }

    private boolean isChunkLoaded(@NotNull Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private void ensureNodeLight(@NotNull Location location) {
        if (location.getWorld() == null) {
            return;
        }
        BlockData desired = Material.LIGHT.createBlockData();
        if (desired instanceof Levelled levelled) {
            levelled.setLevel(NODE_LIGHT_LEVEL);
        }
        BlockData current = location.getBlock().getBlockData();
        if (current.getMaterial() == Material.LIGHT
                && current instanceof Levelled levelled
                && levelled.getLevel() == NODE_LIGHT_LEVEL) {
            return;
        }
        location.getBlock().setBlockData(desired, false);
    }

    private @NotNull Location itemLocation(@NotNull Location location, double yOffset) {
        return location.clone().add(0.0D, yOffset, 0.0D);
    }

    private @NotNull Location textLocation(@NotNull Location location) {
        return location.clone().add(0.0D, TEXT_Y_OFFSET, 0.0D);
    }

    private @NotNull Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, EDGE_Y_OFFSET, 0.0D);
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private @NotNull SkillTreePacketDisplay.PacketEntity packetItemDisplay(
            @NotNull Location location,
            @NotNull ItemStack itemStack,
            float scale,
            double yOffset,
            boolean glowing
    ) {
        return packetDisplay.item(itemLocation(location, yOffset), itemStack, scale, ItemDisplay.ItemDisplayTransform.FIXED, glowing);
    }

    private @NotNull SkillTreePacketDisplay.PacketEntity packetTextDisplay(
            @NotNull Location location,
            @NotNull Component text,
            float scale
    ) {
        return packetDisplay.text(textLocation(location), text, scale);
    }

    private @NotNull SkillTreePacketDisplay.PacketEntity packetEdgeDisplay(
            @NotNull Location left,
            @NotNull Location right,
            @NotNull Material material
    ) {
        Location start = interpolate(left, right, 0.0D);
        Location end = interpolate(left, right, 1.0D);
        return packetDisplay.block(start, material, edgeTransform(start, end));
    }

    private @NotNull SkillTreePacketDisplay.EdgeTransform edgeTransform(@NotNull Location start, @NotNull Location end) {
        Vector direction = end.toVector().subtract(start.toVector());
        float length = (float) Math.max(0.01D, direction.length());
        Vector normalized = direction.normalize();
        Quaternionf rotation = new Quaternionf().rotationTo(
                new Vector3f(1.0F, 0.0F, 0.0F),
                new Vector3f((float) normalized.getX(), (float) normalized.getY(), (float) normalized.getZ())
        );
        return new SkillTreePacketDisplay.EdgeTransform(
                new Vector3f(0.0F, -EDGE_THICKNESS * 0.5F, -EDGE_THICKNESS * 0.5F),
                new Vector3f(length, EDGE_THICKNESS, EDGE_THICKNESS),
                rotation
        );
    }

    private enum RenderMode {
        HIDDEN,
        ADMIN,
        PLAYER
    }

    private enum EdgeState {
        HIDDEN(Material.AIR),
        ADMIN(Material.YELLOW_STAINED_GLASS),
        LOCKED(Material.RED_STAINED_GLASS),
        CONNECTED(Material.BLUE_STAINED_GLASS),
        UNLOCKED(Material.LIME_STAINED_GLASS);

        private final Material material;

        EdgeState(@NotNull Material material) {
            this.material = material;
        }
    }

    private final class AdminPositionVisual {
        private final Location baseLocation;
        private final SkillTreePacketDisplay.PacketEntity item;
        private final SkillTreePacketDisplay.PacketEntity marker;
        private final SkillTreePacketDisplay.PacketEntity label;
        private final Set<UUID> visibleViewers = new HashSet<>();

        private AdminPositionVisual(@NotNull String positionId, @NotNull Location location) {
            this.baseLocation = location.clone();
            this.item = packetItemDisplay(location, new ItemStack(Material.ARMOR_STAND), ADMIN_ITEM_SCALE, ADMIN_ITEM_Y_OFFSET, false);
            this.marker = packetTextDisplay(location, component("&d*"), ADMIN_TEXT_SCALE);
            Location labelLocation = location.clone().add(0.0D, 0.45D, 0.0D);
            this.label = packetTextDisplay(labelLocation, component("&d" + positionId), ADMIN_TEXT_SCALE);
            Logger.log(
                    LogId.I_9002,
                    "admin",
                    positionId,
                    location.getWorld() == null ? "null" : location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ()
            );
        }

        private @NotNull Location baseLocation() {
            return baseLocation.clone();
        }

        private void teleport(@NotNull Location location) {
            if (sameLocation(baseLocation, location)) {
                return;
            }
            hideCurrentViewers();
            baseLocation.setWorld(location.getWorld());
            baseLocation.setX(location.getX());
            baseLocation.setY(location.getY());
            baseLocation.setZ(location.getZ());
            item.move(itemLocation(location, ADMIN_ITEM_Y_OFFSET));
            marker.move(textLocation(location));
            label.move(textLocation(location.clone().add(0.0D, 0.45D, 0.0D)));
            showCurrentViewers();
        }

        private void updateViewer(@NotNull Player player, boolean visible) {
            UUID playerId = player.getUniqueId();
            if (!visible) {
                if (!visibleViewers.remove(playerId)) {
                    return;
                }
                destroy(player, item, marker, label);
                return;
            }
            if (!visibleViewers.add(playerId)) {
                return;
            }
            spawn(player, item, marker, label);
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            visibleViewers.removeIf(playerId -> !onlineViewerIds.contains(playerId));
        }

        private boolean isValid() {
            return true;
        }

        private void remove() {
            hideCurrentViewers();
            visibleViewers.clear();
        }

        private void hideCurrentViewers() {
            for (UUID playerId : visibleViewers) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    destroy(player, item, marker, label);
                }
            }
        }

        private void showCurrentViewers() {
            for (UUID playerId : visibleViewers) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    spawn(player, item, marker, label);
                }
            }
        }
    }

    private final class NodeVisual {
        private final SkillTreeNodeDefinition node;
        private final Location baseLocation;
        private final SkillTreePacketDisplay.PacketEntity lockedItem;
        private final SkillTreePacketDisplay.PacketEntity unlockedItem;
        private final Map<NodeLabelKey, SkillTreePacketDisplay.PacketEntity> labels = new HashMap<>();
        private final Map<UUID, NodeRenderState> viewerStates = new HashMap<>();

        private NodeVisual(@NotNull SkillTreeNodeDefinition node, @NotNull Location location) {
            this.node = node;
            this.baseLocation = location.clone();
            this.lockedItem = packetItemDisplay(location, service.createNodeDisplayItem(node, false), NODE_ITEM_SCALE, NODE_ITEM_Y_OFFSET, false);
            this.unlockedItem = packetItemDisplay(location, service.createNodeDisplayItem(node, true), NODE_ITEM_SCALE, NODE_ITEM_Y_OFFSET, true);
            registerLabel(location, node, SkillTreeService.NodePresentationState.BLOCKED, SkillTreeService.NodeLabelDetail.DETAILED, NODE_TEXT_SCALE);
            registerLabel(location, node, SkillTreeService.NodePresentationState.BLOCKED, SkillTreeService.NodeLabelDetail.COMPACT, NODE_TEXT_COMPACT_SCALE);
            registerLabel(location, node, SkillTreeService.NodePresentationState.AVAILABLE, SkillTreeService.NodeLabelDetail.DETAILED, NODE_TEXT_SCALE);
            registerLabel(location, node, SkillTreeService.NodePresentationState.AVAILABLE, SkillTreeService.NodeLabelDetail.COMPACT, NODE_TEXT_COMPACT_SCALE);
            registerLabel(location, node, SkillTreeService.NodePresentationState.UNLOCKED, SkillTreeService.NodeLabelDetail.DETAILED, NODE_TEXT_SCALE);
            registerLabel(location, node, SkillTreeService.NodePresentationState.UNLOCKED, SkillTreeService.NodeLabelDetail.COMPACT, NODE_TEXT_COMPACT_SCALE);
            Logger.log(
                    LogId.I_9002,
                    "node",
                    node.id(),
                    location.getWorld() == null ? "null" : location.getWorld().getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ()
            );
        }

        private @NotNull SkillTreeNodeDefinition node() {
            return node;
        }

        private @NotNull Location baseLocation() {
            return baseLocation.clone();
        }

        private void teleport(@NotNull Location location) {
            if (sameLocation(baseLocation, location)) {
                return;
            }
            hideCurrentViewers();
            baseLocation.setWorld(location.getWorld());
            baseLocation.setX(location.getX());
            baseLocation.setY(location.getY());
            baseLocation.setZ(location.getZ());
            Location itemLocation = itemLocation(location, NODE_ITEM_Y_OFFSET);
            Location textLocation = textLocation(location);
            lockedItem.move(itemLocation);
            unlockedItem.move(itemLocation);
            labels.values().forEach(label -> label.move(textLocation));
            showCurrentViewers();
        }

        private void updateViewer(
                @NotNull Player player,
                boolean visible,
                @NotNull SkillTreeService.NodePresentationState nodePresentationState,
                @NotNull SkillTreeService.NodeLabelDetail labelDetail
        ) {
            NodeRenderState nextState = visible
                    ? new NodeRenderState(nodePresentationState, labelDetail)
                    : NodeRenderState.HIDDEN;
            UUID playerId = player.getUniqueId();
            NodeRenderState previousState = viewerStates.getOrDefault(playerId, NodeRenderState.HIDDEN);

            if (previousState.equals(nextState)) {
                return;
            }

            if (nextState.hidden()) {
                viewerStates.remove(playerId);
            } else {
                viewerStates.put(playerId, nextState);
            }

            hideState(player, previousState);
            showState(player, nextState);
        }

        private void registerLabel(
                @NotNull Location location,
                @NotNull SkillTreeNodeDefinition node,
                @NotNull SkillTreeService.NodePresentationState presentationState,
                @NotNull SkillTreeService.NodeLabelDetail labelDetail,
                float scale
        ) {
            labels.put(
                    new NodeLabelKey(presentationState, labelDetail),
                    packetTextDisplay(location, service.nodeFieldLabel(node, presentationState, labelDetail), scale)
            );
        }

        private void hideState(@NotNull Player player, @NotNull NodeRenderState state) {
            if (state.hidden()) {
                return;
            }
            SkillTreePacketDisplay.PacketEntity item = resolveItem(state.presentationState());
            SkillTreePacketDisplay.PacketEntity label = resolveLabel(state);
            if (label == null) {
                destroy(player, item);
                return;
            }
            destroy(player, item, label);
        }

        private void showState(@NotNull Player player, @NotNull NodeRenderState state) {
            if (state.hidden()) {
                return;
            }
            SkillTreePacketDisplay.PacketEntity item = resolveItem(state.presentationState());
            SkillTreePacketDisplay.PacketEntity label = resolveLabel(state);
            if (label == null) {
                spawn(player, item);
                return;
            }
            spawn(player, item, label);
        }

        private @NotNull SkillTreePacketDisplay.PacketEntity resolveItem(
                @NotNull SkillTreeService.NodePresentationState presentationState
        ) {
            return presentationState == SkillTreeService.NodePresentationState.UNLOCKED ? unlockedItem : lockedItem;
        }

        private @Nullable SkillTreePacketDisplay.PacketEntity resolveLabel(@NotNull NodeRenderState state) {
            if (state.labelDetail() == SkillTreeService.NodeLabelDetail.HIDDEN) {
                return null;
            }
            return labels.get(new NodeLabelKey(state.presentationState(), state.labelDetail()));
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            viewerStates.entrySet().removeIf(entry -> !onlineViewerIds.contains(entry.getKey()));
        }

        private boolean isValid() {
            return true;
        }

        private void remove() {
            hideCurrentViewers();
            viewerStates.clear();
        }

        private void hideCurrentViewers() {
            for (Map.Entry<UUID, NodeRenderState> entry : viewerStates.entrySet()) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    hideState(player, entry.getValue());
                }
            }
        }

        private void showCurrentViewers() {
            for (Map.Entry<UUID, NodeRenderState> entry : viewerStates.entrySet()) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null) {
                    showState(player, entry.getValue());
                }
            }
        }
    }

    private record NodeLabelKey(
            @NotNull SkillTreeService.NodePresentationState presentationState,
            @NotNull SkillTreeService.NodeLabelDetail labelDetail
    ) {
    }

    private record NodeRenderState(
            @NotNull SkillTreeService.NodePresentationState presentationState,
            @NotNull SkillTreeService.NodeLabelDetail labelDetail
    ) {
        private static final NodeRenderState HIDDEN =
                new NodeRenderState(SkillTreeService.NodePresentationState.BLOCKED, SkillTreeService.NodeLabelDetail.HIDDEN);

        private boolean hidden() {
            return labelDetail == SkillTreeService.NodeLabelDetail.HIDDEN
                    && presentationState == SkillTreeService.NodePresentationState.BLOCKED;
        }
    }

    private final class EdgeVisual {
        private final SkillTreeEdge edge;
        private final SkillTreePacketDisplay.PacketEntity block;
        private final Map<UUID, EdgeState> viewerStates = new HashMap<>();
        private Location leftLocation;
        private Location rightLocation;
        private Location midpoint;

        private EdgeVisual(@NotNull SkillTreeEdge edge, @NotNull Location left, @NotNull Location right) {
            this.edge = edge;
            this.block = packetEdgeDisplay(left, right, EdgeState.LOCKED.material);
            this.leftLocation = left.clone();
            this.rightLocation = right.clone();
            this.midpoint = interpolate(left, right, 0.5D);
            Logger.log(
                    LogId.I_9002,
                    "edge",
                    edge.key(),
                    midpoint.getWorld() == null ? "null" : midpoint.getWorld().getName(),
                    midpoint.getX(),
                    midpoint.getY(),
                    midpoint.getZ()
            );
        }

        private @NotNull SkillTreeEdge edge() {
            return edge;
        }

        private @NotNull Location midpoint() {
            return midpoint.clone();
        }

        private void teleport(@NotNull Location left, @NotNull Location right) {
            if (sameLocation(leftLocation, left) && sameLocation(rightLocation, right)) {
                return;
            }
            hideCurrentViewers();
            leftLocation = left.clone();
            rightLocation = right.clone();
            Location start = interpolate(left, right, 0.0D);
            Location end = interpolate(left, right, 1.0D);
            packetDisplay.moveBlock(block, start, EdgeState.LOCKED.material, edgeTransform(start, end));
            midpoint = interpolate(left, right, 0.5D);
            showCurrentViewers();
        }

        private void updateViewer(@NotNull Player player, @NotNull EdgeState nextState) {
            UUID playerId = player.getUniqueId();
            EdgeState previousState = viewerStates.getOrDefault(playerId, EdgeState.HIDDEN);

            if (previousState == nextState) {
                return;
            }

            if (nextState == EdgeState.HIDDEN) {
                viewerStates.remove(playerId);
            } else {
                viewerStates.put(playerId, nextState);
            }

            if (previousState == EdgeState.HIDDEN) {
                block.spawn(player);
            }
            if (nextState == EdgeState.HIDDEN) {
                block.destroy(player);
            } else {
                packetDisplay.updateBlock(player, block, nextState.material);
            }
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            viewerStates.entrySet().removeIf(entry -> !onlineViewerIds.contains(entry.getKey()));
        }

        private boolean isValid() {
            return true;
        }

        private void remove() {
            hideCurrentViewers();
            viewerStates.clear();
        }

        private void hideCurrentViewers() {
            for (UUID playerId : viewerStates.keySet()) {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    block.destroy(player);
                }
            }
        }

        private void showCurrentViewers() {
            for (Map.Entry<UUID, EdgeState> entry : viewerStates.entrySet()) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && entry.getValue() != EdgeState.HIDDEN) {
                    block.spawn(player);
                    packetDisplay.updateBlock(player, block, entry.getValue().material);
                }
            }
        }
    }

    private boolean sameLocation(@NotNull Location left, @NotNull Location right) {
        return left.getWorld() == right.getWorld()
                && Double.compare(left.getX(), right.getX()) == 0
                && Double.compare(left.getY(), right.getY()) == 0
                && Double.compare(left.getZ(), right.getZ()) == 0;
    }

    private void spawn(@NotNull Player player, @NotNull SkillTreePacketDisplay.PacketEntity... entities) {
        for (SkillTreePacketDisplay.PacketEntity entity : entities) {
            entity.spawn(player);
        }
    }

    private void destroy(@NotNull Player player, @NotNull SkillTreePacketDisplay.PacketEntity... entities) {
        for (SkillTreePacketDisplay.PacketEntity entity : entities) {
            entity.destroy(player);
        }
    }
}
