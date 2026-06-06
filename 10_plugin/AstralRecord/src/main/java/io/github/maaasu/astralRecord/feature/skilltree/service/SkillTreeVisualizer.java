package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double VIEW_DISTANCE = 96.0D;
    private static final double VIEW_DISTANCE_SQ = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final double EDGE_STEP = 0.45D;
    private static final double ITEM_Y_OFFSET = 0.15D;
    private static final double EDGE_Y_OFFSET = 0.65D;
    private static final double TEXT_Y_OFFSET = 1.2D;
    private static final float NODE_ITEM_SCALE = 0.72F;
    private static final float NODE_TEXT_SCALE = 0.85F;
    private static final float ADMIN_ITEM_SCALE = 0.72F;
    private static final float ADMIN_TEXT_SCALE = 0.72F;
    private static final float EDGE_TEXT_SCALE = 0.42F;
    private static final float VIEW_RANGE = 96.0F;

    private final Plugin plugin;
    private final SkillTreeService service;
    private final Map<String, NodeVisual> nodeVisuals = new HashMap<>();
    private final Map<String, AdminPositionVisual> adminPositionVisuals = new HashMap<>();
    private final Map<String, EdgeVisual> edgeVisuals = new HashMap<>();
    private BukkitTask task;

    SkillTreeVisualizer(@NotNull Plugin plugin, @NotNull SkillTreeService service) {
        this.plugin = plugin;
        this.service = service;
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

    private void tick() {
        syncVisuals();

        Set<UUID> onlineViewerIds = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            onlineViewerIds.add(playerId);

            RenderMode mode = resolveMode(player);
            for (AdminPositionVisual visual : adminPositionVisuals.values()) {
                boolean visible = mode == RenderMode.ADMIN && isVisibleTo(player, visual.baseLocation());
                visual.updateViewer(player, visible);
            }
            for (NodeVisual visual : nodeVisuals.values()) {
                boolean visible = mode == RenderMode.PLAYER && isVisibleTo(player, visual.baseLocation());
                boolean unlocked = visible && isUnlocked(player, visual.node());
                visual.updateViewer(player, visible, unlocked);
            }
            for (EdgeVisual visual : edgeVisuals.values()) {
                EdgeColor color = resolveEdgeColor(player, visual.edge(), mode);
                boolean visible = color != EdgeColor.HIDDEN && isVisibleTo(player, visual.midpoint());
                visual.updateViewer(player, visible ? color : EdgeColor.HIDDEN);
            }
        }

        nodeVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
        adminPositionVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
        edgeVisuals.values().forEach(visual -> visual.pruneViewers(onlineViewerIds));
    }

    private void syncVisuals() {
        Collection<SkillTreePosition> positions = service.getPositions();
        Set<String> activePositionIds = new HashSet<>();
        for (SkillTreePosition position : positions) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            activePositionIds.add(position.positionId());

            AdminPositionVisual adminVisual = adminPositionVisuals.get(position.positionId());
            if (adminVisual == null) {
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
            if (nodeVisual == null || !nodeVisual.node().id().equals(node.id())) {
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
                continue;
            }

            activeEdgeKeys.add(edge.key());
            EdgeVisual edgeVisual = edgeVisuals.get(edge.key());
            if (edgeVisual == null) {
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

    private @NotNull EdgeColor resolveEdgeColor(
            @NotNull Player player,
            @NotNull SkillTreeEdge edge,
            @NotNull RenderMode mode
    ) {
        if (mode == RenderMode.ADMIN) {
            return EdgeColor.ADMIN;
        }
        if (mode != RenderMode.PLAYER) {
            return EdgeColor.HIDDEN;
        }

        SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
        SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
        if (leftNode == null || rightNode == null) {
            return EdgeColor.HIDDEN;
        }

        boolean leftUnlocked = isUnlocked(player, leftNode);
        boolean rightUnlocked = isUnlocked(player, rightNode);
        if (leftUnlocked && rightUnlocked) {
            return EdgeColor.YELLOW;
        }
        if (leftUnlocked || rightUnlocked) {
            return EdgeColor.WHITE;
        }
        return EdgeColor.GRAY;
    }

    private boolean isUnlocked(@NotNull Player player, @NotNull SkillTreeNodeDefinition node) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }
        SkillTreePlayerState state = service.state(astPlayer);
        return state.isUnlocked(node.id());
    }

    private boolean isVisibleTo(@NotNull Player player, @Nullable Location location) {
        return location != null
                && location.getWorld() != null
                && player.getWorld() == location.getWorld()
                && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
    }

    private @NotNull Location itemLocation(@NotNull Location location) {
        return location.clone().add(0.0D, ITEM_Y_OFFSET, 0.0D);
    }

    private @NotNull Location textLocation(@NotNull Location location) {
        return location.clone().add(0.0D, TEXT_Y_OFFSET, 0.0D);
    }

    private @NotNull Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, EDGE_Y_OFFSET, 0.0D);
    }

    private int pointCount(@NotNull Location left, @NotNull Location right) {
        return Math.max(1, (int) Math.ceil(left.distance(right) / EDGE_STEP));
    }

    private @NotNull Transformation scaleTransformation(float scale) {
        return new Transformation(
                new Vector3f(),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        );
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private @NotNull ItemDisplay spawnItemDisplay(@NotNull Location location, @NotNull ItemStack itemStack, float scale) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location world is null");
        }
        ItemDisplay display = world.spawn(itemLocation(location), ItemDisplay.class, entity -> {
            entity.setItemStack(itemStack);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setSilent(true);
            entity.setViewRange(VIEW_RANGE);
            entity.setTransformation(scaleTransformation(scale));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        });
        hideForCurrentPlayers(display);
        return display;
    }

    private @NotNull TextDisplay spawnTextDisplay(
            @NotNull Location location,
            @NotNull Component text,
            float scale
    ) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("location world is null");
        }
        TextDisplay display = world.spawn(textLocation(location), TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setSilent(true);
            entity.setViewRange(VIEW_RANGE);
            entity.setLineWidth(160);
            entity.setSeeThrough(true);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setTransformation(scaleTransformation(scale));
            entity.text(text);
        });
        hideForCurrentPlayers(display);
        return display;
    }

    private enum RenderMode {
        HIDDEN,
        ADMIN,
        PLAYER
    }

    private enum NodeState {
        HIDDEN,
        LOCKED,
        UNLOCKED
    }

    private enum EdgeColor {
        HIDDEN(""),
        ADMIN("&d"),
        GRAY("&7"),
        WHITE("&f"),
        YELLOW("&e");

        private final String colorCode;

        EdgeColor(@NotNull String colorCode) {
            this.colorCode = colorCode;
        }
    }

    private final class AdminPositionVisual {
        private final Location baseLocation;
        private final ItemDisplay item;
        private final TextDisplay marker;
        private final TextDisplay label;
        private final Set<UUID> visibleViewers = new HashSet<>();

        private AdminPositionVisual(@NotNull String positionId, @NotNull Location location) {
            this.baseLocation = location.clone();
            this.item = spawnItemDisplay(location, new ItemStack(Material.ARMOR_STAND), ADMIN_ITEM_SCALE);
            this.marker = spawnTextDisplay(location, component("&d*"), ADMIN_TEXT_SCALE);
            Location labelLocation = location.clone().add(0.0D, 0.45D, 0.0D);
            this.label = spawnTextDisplay(labelLocation, component("&d" + positionId), ADMIN_TEXT_SCALE);
        }

        private @NotNull Location baseLocation() {
            return baseLocation.clone();
        }

        private void teleport(@NotNull Location location) {
            baseLocation.setWorld(location.getWorld());
            baseLocation.setX(location.getX());
            baseLocation.setY(location.getY());
            baseLocation.setZ(location.getZ());
            item.teleport(itemLocation(location));
            marker.teleport(textLocation(location));
            label.teleport(location.clone().add(0.0D, TEXT_Y_OFFSET + 0.45D, 0.0D));
        }

        private void updateViewer(@NotNull Player player, boolean visible) {
            UUID playerId = player.getUniqueId();
            if (!visible) {
                visibleViewers.remove(playerId);
                hideEntities(player, item, marker, label);
                return;
            }
            visibleViewers.add(playerId);
            showEntities(player, item, marker, label);
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            visibleViewers.removeIf(playerId -> !onlineViewerIds.contains(playerId));
        }

        private void remove() {
            visibleViewers.clear();
            removeEntities(item, marker, label);
        }
    }

    private final class NodeVisual {
        private final SkillTreeNodeDefinition node;
        private final Location baseLocation;
        private final ItemDisplay lockedItem;
        private final ItemDisplay unlockedItem;
        private final TextDisplay lockedLabel;
        private final TextDisplay unlockedLabel;
        private final Map<UUID, NodeState> viewerStates = new HashMap<>();

        private NodeVisual(@NotNull SkillTreeNodeDefinition node, @NotNull Location location) {
            this.node = node;
            this.baseLocation = location.clone();
            this.lockedItem = spawnItemDisplay(location, service.createNodeDisplayItem(node, false), NODE_ITEM_SCALE);
            this.unlockedItem = spawnItemDisplay(location, service.createNodeDisplayItem(node, true), NODE_ITEM_SCALE);
            this.lockedLabel = spawnTextDisplay(location, service.nodeName(node, false), NODE_TEXT_SCALE);
            this.unlockedLabel = spawnTextDisplay(location, service.nodeName(node, true), NODE_TEXT_SCALE);
        }

        private @NotNull SkillTreeNodeDefinition node() {
            return node;
        }

        private @NotNull Location baseLocation() {
            return baseLocation.clone();
        }

        private void teleport(@NotNull Location location) {
            baseLocation.setWorld(location.getWorld());
            baseLocation.setX(location.getX());
            baseLocation.setY(location.getY());
            baseLocation.setZ(location.getZ());
            Location itemLocation = itemLocation(location);
            Location textLocation = textLocation(location);
            lockedItem.teleport(itemLocation);
            unlockedItem.teleport(itemLocation);
            lockedLabel.teleport(textLocation);
            unlockedLabel.teleport(textLocation);
        }

        private void updateViewer(@NotNull Player player, boolean visible, boolean unlocked) {
            NodeState nextState = !visible ? NodeState.HIDDEN : unlocked ? NodeState.UNLOCKED : NodeState.LOCKED;
            UUID playerId = player.getUniqueId();

            if (nextState == NodeState.HIDDEN) {
                viewerStates.remove(playerId);
            } else {
                viewerStates.put(playerId, nextState);
            }

            hideEntities(player, lockedItem, unlockedItem, lockedLabel, unlockedLabel);
            showState(player, nextState);
        }

        private void hideState(@NotNull Player player, @NotNull NodeState state) {
            switch (state) {
                case LOCKED -> hideEntities(player, lockedItem, lockedLabel);
                case UNLOCKED -> hideEntities(player, unlockedItem, unlockedLabel);
                case HIDDEN -> {
                }
            }
        }

        private void showState(@NotNull Player player, @NotNull NodeState state) {
            switch (state) {
                case LOCKED -> showEntities(player, lockedItem, lockedLabel);
                case UNLOCKED -> showEntities(player, unlockedItem, unlockedLabel);
                case HIDDEN -> {
                }
            }
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            viewerStates.entrySet().removeIf(entry -> !onlineViewerIds.contains(entry.getKey()));
        }

        private void remove() {
            viewerStates.clear();
            removeEntities(lockedItem, unlockedItem, lockedLabel, unlockedLabel);
        }
    }

    private final class EdgeVisual {
        private final SkillTreeEdge edge;
        private final List<TextDisplay> adminDots;
        private final List<TextDisplay> grayDots;
        private final List<TextDisplay> whiteDots;
        private final List<TextDisplay> yellowDots;
        private final Map<UUID, EdgeColor> viewerStates = new HashMap<>();
        private Location midpoint;

        private EdgeVisual(@NotNull SkillTreeEdge edge, @NotNull Location left, @NotNull Location right) {
            this.edge = edge;
            this.adminDots = spawnEdgeDots(left, right, EdgeColor.ADMIN);
            this.grayDots = spawnEdgeDots(left, right, EdgeColor.GRAY);
            this.whiteDots = spawnEdgeDots(left, right, EdgeColor.WHITE);
            this.yellowDots = spawnEdgeDots(left, right, EdgeColor.YELLOW);
            this.midpoint = interpolate(left, right, 0.5D);
        }

        private @NotNull SkillTreeEdge edge() {
            return edge;
        }

        private @NotNull Location midpoint() {
            return midpoint.clone();
        }

        private void teleport(@NotNull Location left, @NotNull Location right) {
            teleportDots(adminDots, left, right);
            teleportDots(grayDots, left, right);
            teleportDots(whiteDots, left, right);
            teleportDots(yellowDots, left, right);
            midpoint = interpolate(left, right, 0.5D);
        }

        private void updateViewer(@NotNull Player player, @NotNull EdgeColor nextColor) {
            UUID playerId = player.getUniqueId();
            if (nextColor == EdgeColor.HIDDEN) {
                viewerStates.remove(playerId);
            } else {
                viewerStates.put(playerId, nextColor);
            }

            hideEntities(player, entitiesFor(EdgeColor.ADMIN));
            hideEntities(player, entitiesFor(EdgeColor.GRAY));
            hideEntities(player, entitiesFor(EdgeColor.WHITE));
            hideEntities(player, entitiesFor(EdgeColor.YELLOW));
            showColor(player, nextColor);
        }

        private void hideColor(@NotNull Player player, @NotNull EdgeColor color) {
            hideEntities(player, entitiesFor(color));
        }

        private void showColor(@NotNull Player player, @NotNull EdgeColor color) {
            showEntities(player, entitiesFor(color));
        }

        private @NotNull Entity[] entitiesFor(@NotNull EdgeColor color) {
            return switch (color) {
                case ADMIN -> adminDots.toArray(Entity[]::new);
                case GRAY -> grayDots.toArray(Entity[]::new);
                case WHITE -> whiteDots.toArray(Entity[]::new);
                case YELLOW -> yellowDots.toArray(Entity[]::new);
                case HIDDEN -> new Entity[0];
            };
        }

        private void pruneViewers(@NotNull Set<UUID> onlineViewerIds) {
            viewerStates.entrySet().removeIf(entry -> !onlineViewerIds.contains(entry.getKey()));
        }

        private void remove() {
            viewerStates.clear();
            removeEntities(adminDots);
            removeEntities(grayDots);
            removeEntities(whiteDots);
            removeEntities(yellowDots);
        }
    }

    private @NotNull List<TextDisplay> spawnEdgeDots(
            @NotNull Location left,
            @NotNull Location right,
            @NotNull EdgeColor color
    ) {
        int count = pointCount(left, right);
        List<TextDisplay> displays = new ArrayList<>();
        Component text = component(color.colorCode + "*");
        for (int i = 1; i < count; i++) {
            displays.add(spawnTextDisplay(interpolate(left, right, (double) i / count), text, EDGE_TEXT_SCALE));
        }
        return displays;
    }

    private void teleportDots(
            @NotNull List<TextDisplay> dots,
            @NotNull Location left,
            @NotNull Location right
    ) {
        int count = pointCount(left, right);
        int dotCount = Math.min(dots.size(), Math.max(0, count - 1));
        for (int i = 0; i < dotCount; i++) {
            dots.get(i).teleport(interpolate(left, right, (double) (i + 1) / count));
        }
    }

    private void showEntities(@NotNull Player player, @NotNull Entity... entities) {
        for (Entity entity : entities) {
            player.showEntity(plugin, entity);
        }
    }

    private void hideForCurrentPlayers(@NotNull Entity entity) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.hideEntity(plugin, entity);
        }
    }

    private void hideEntities(@NotNull Player player, @NotNull Entity... entities) {
        for (Entity entity : entities) {
            player.hideEntity(plugin, entity);
        }
    }

    private void removeEntities(@NotNull Entity... entities) {
        for (Entity entity : entities) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
    }

    private void removeEntities(@NotNull List<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (entity.isValid()) {
                entity.remove();
            }
        }
        entities.clear();
    }
}
