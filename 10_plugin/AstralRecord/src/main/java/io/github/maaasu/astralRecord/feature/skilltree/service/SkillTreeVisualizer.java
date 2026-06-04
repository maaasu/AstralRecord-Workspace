package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * スキルツリーのノードと接続線の表示を Entity で管理します。
 */
final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double VIEW_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double EDGE_STEP = 0.45D;

    private final Plugin plugin;
    private final SkillTreeService service;
    private final Map<String, PositionVisual> positions = new HashMap<>();
    private final Map<String, EdgeVisual> edges = new HashMap<>();
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
        positions.values().forEach(PositionVisual::remove);
        edges.values().forEach(EdgeVisual::remove);
        positions.clear();
        edges.clear();
    }

    private void tick() {
        Set<String> activePositions = new HashSet<>();
        for (SkillTreePosition position : service.getPositions()) {
            Location location = position.toLocation();
            if (location == null || location.getWorld() == null) {
                continue;
            }
            activePositions.add(position.positionId());
            SkillTreeNodeDefinition node = service.getNodeByPositionId(position.positionId());
            PositionVisual visual = positions.get(position.positionId());
            if (visual == null || !visual.isUsable(location)) {
                if (visual != null) {
                    visual.remove();
                }
                visual = createPositionVisual(location);
                positions.put(position.positionId(), visual);
            }
            visual.ensureNodeDisplays(location, node);
            visual.teleport(location);
            updatePositionViewers(visual, position.positionId(), location, node);
        }
        positions.entrySet().removeIf(entry -> {
            if (activePositions.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });

        Set<String> activeEdges = new HashSet<>();
        for (SkillTreeEdge edge : service.getEdges()) {
            SkillTreePosition left = service.getPosition(edge.leftPositionId());
            SkillTreePosition right = service.getPosition(edge.rightPositionId());
            if (left == null || right == null) {
                continue;
            }
            Location leftLocation = left.toLocation();
            Location rightLocation = right.toLocation();
            if (leftLocation == null || rightLocation == null || leftLocation.getWorld() != rightLocation.getWorld()) {
                continue;
            }
            activeEdges.add(edge.key());
            EdgeVisual visual = edges.get(edge.key());
            if (visual == null || !visual.isUsable(leftLocation, rightLocation)) {
                if (visual != null) {
                    visual.remove();
                }
                visual = createEdgeVisual(leftLocation, rightLocation);
                edges.put(edge.key(), visual);
            }
            visual.teleport(leftLocation, rightLocation);
            updateEdgeViewers(edge, visual, leftLocation, rightLocation);
        }
        edges.entrySet().removeIf(entry -> {
            if (activeEdges.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().remove();
            return true;
        });
    }

    @NotNull
    private PositionVisual createPositionVisual(@NotNull Location location) {
        return new PositionVisual(
                spawnItem(location, new ItemStack(Material.ARMOR_STAND)),
                spawnText(textLocation(location), component("&d*")),
                spawnText(location.clone().add(0.0D, 1.2D, 0.0D), component("&dposition")),
                null,
                null,
                null,
                null
        );
    }

    private void updatePositionViewers(
            @NotNull PositionVisual visual,
            @NotNull String positionId,
            @NotNull Location location,
            @Nullable SkillTreeNodeDefinition node
    ) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean near = player.getWorld() == location.getWorld()
                    && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
            boolean adminVisible = near && service.shouldShowAdminPosition(player, location);
            boolean playerVisible = near && node != null && service.shouldShowPlayerNode(player, location);
            boolean unlocked = false;
            if (playerVisible) {
                var astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    SkillTreePlayerState state = service.state(astPlayer);
                    unlocked = state.isUnlocked(node.id());
                }
            }

            PositionViewerState state = PositionViewerState.HIDDEN;
            if (adminVisible) {
                state = node == null ? PositionViewerState.ADMIN_ONLY : PositionViewerState.ADMIN_PREVIEW;
            } else if (playerVisible) {
                state = unlocked ? PositionViewerState.PLAYER_UNLOCKED : PositionViewerState.PLAYER_LOCKED;
            }
            visual.updateViewer(plugin, player, state, positionId);
        }
    }

    @NotNull
    private EdgeVisual createEdgeVisual(@NotNull Location left, @NotNull Location right) {
        int count = pointCount(left, right);
        List<TextDisplay> purple = new ArrayList<>();
        List<TextDisplay> gray = new ArrayList<>();
        List<TextDisplay> white = new ArrayList<>();
        List<TextDisplay> yellow = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            Location location = interpolate(left, right, (double) i / count);
            purple.add(spawnText(location, component("&d*")));
            gray.add(spawnText(location, component("&7*")));
            white.add(spawnText(location, component("&f*")));
            yellow.add(spawnText(location, component("&e*")));
        }
        return new EdgeVisual(purple, gray, white, yellow);
    }

    private void updateEdgeViewers(
            @NotNull SkillTreeEdge edge,
            @NotNull EdgeVisual visual,
            @NotNull Location left,
            @NotNull Location right
    ) {
        Location midpoint = interpolate(left, right, 0.5D);
        SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
        SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean near = player.getWorld() == midpoint.getWorld()
                    && player.getLocation().distanceSquared(midpoint) <= VIEW_DISTANCE_SQ;
            boolean adminVisible = near && service.shouldShowAdminPosition(player, midpoint);
            EdgeViewerState state = EdgeViewerState.HIDDEN;
            if (adminVisible) {
                state = EdgeViewerState.ADMIN;
            } else if (near && leftNode != null && rightNode != null && service.shouldShowPlayerNode(player, midpoint)) {
                state = switch (service.edgeState(player, edge)) {
                    case 2 -> EdgeViewerState.YELLOW;
                    case 1 -> EdgeViewerState.WHITE;
                    default -> EdgeViewerState.GRAY;
                };
            }
            visual.updateViewer(plugin, player, state);
        }
    }

    @NotNull
    private Item spawnItem(@NotNull Location location, @NotNull ItemStack itemStack) {
        return location.getWorld().spawn(itemLocation(location), Item.class, item -> {
            item.setPersistent(false);
            item.setItemStack(itemStack);
            item.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            item.setGravity(false);
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setCanMobPickup(false);
            item.setUnlimitedLifetime(true);
            item.setInvulnerable(true);
            item.setSilent(true);
            item.setVisibleByDefault(false);
        });
    }

    @NotNull
    private TextDisplay spawnText(@NotNull Location location, @NotNull Component text) {
        return location.getWorld().spawn(location, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.text(text);
        });
    }

    @NotNull
    private Location itemLocation(@NotNull Location location) {
        return location.clone().add(0.0D, 0.15D, 0.0D);
    }

    @NotNull
    private Location textLocation(@NotNull Location location) {
        return location.clone().add(0.0D, 1.2D, 0.0D);
    }

    @NotNull
    private Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, 0.65D, 0.0D);
    }

    private int pointCount(@NotNull Location left, @NotNull Location right) {
        return Math.max(1, (int) Math.ceil(left.distance(right) / EDGE_STEP));
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private boolean isAlive(@Nullable Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    private enum PositionViewerState {
        HIDDEN,
        ADMIN_ONLY,
        ADMIN_PREVIEW,
        PLAYER_LOCKED,
        PLAYER_UNLOCKED
    }

    private enum EdgeViewerState {
        HIDDEN,
        ADMIN,
        GRAY,
        WHITE,
        YELLOW
    }

    private final class EdgeVisual {
        private final List<TextDisplay> purple;
        private final List<TextDisplay> gray;
        private final List<TextDisplay> white;
        private final List<TextDisplay> yellow;
        private final Map<UUID, EdgeViewerState> viewerStates = new HashMap<>();

        private EdgeVisual(
                @NotNull List<TextDisplay> purple,
                @NotNull List<TextDisplay> gray,
                @NotNull List<TextDisplay> white,
                @NotNull List<TextDisplay> yellow
        ) {
            this.purple = purple;
            this.gray = gray;
            this.white = white;
            this.yellow = yellow;
        }

        private boolean isUsable(@NotNull Location left, @NotNull Location right) {
            int expected = pointCount(left, right) - 1;
            return purple.size() == expected
                    && allAlive(purple)
                    && allAlive(gray)
                    && allAlive(white)
                    && allAlive(yellow);
        }

        private void teleport(@NotNull Location left, @NotNull Location right) {
            int count = purple.size() + 1;
            for (int i = 0; i < purple.size(); i++) {
                double t = (double) (i + 1) / count;
                Location target = interpolate(left, right, t);
                teleportAll(target, purple.get(i), gray.get(i), white.get(i), yellow.get(i));
            }
        }

        private void updateViewer(@NotNull Plugin plugin, @NotNull Player player, @NotNull EdgeViewerState nextState) {
            UUID playerId = player.getUniqueId();
            if (viewerStates.get(playerId) == nextState) {
                return;
            }
            show(plugin, player, purple, nextState == EdgeViewerState.ADMIN);
            show(plugin, player, gray, nextState == EdgeViewerState.GRAY);
            show(plugin, player, white, nextState == EdgeViewerState.WHITE);
            show(plugin, player, yellow, nextState == EdgeViewerState.YELLOW);
            viewerStates.put(playerId, nextState);
        }

        private void remove() {
            removeAll(purple);
            removeAll(gray);
            removeAll(white);
            removeAll(yellow);
            viewerStates.clear();
        }
    }

    private final class PositionVisual {
        private Item adminItem;
        private TextDisplay adminMarker;
        private TextDisplay adminText;
        private Item lockedItem;
        private TextDisplay lockedText;
        private Item unlockedItem;
        private TextDisplay unlockedText;
        private final Map<UUID, PositionViewerState> viewerStates = new HashMap<>();

        private PositionVisual(
                @NotNull Item adminItem,
                @NotNull TextDisplay adminMarker,
                @NotNull TextDisplay adminText,
                @Nullable Item lockedItem,
                @Nullable TextDisplay lockedText,
                @Nullable Item unlockedItem,
                @Nullable TextDisplay unlockedText
        ) {
            this.adminItem = adminItem;
            this.adminMarker = adminMarker;
            this.adminText = adminText;
            this.lockedItem = lockedItem;
            this.lockedText = lockedText;
            this.unlockedItem = unlockedItem;
            this.unlockedText = unlockedText;
        }

        private boolean isUsable(@NotNull Location location) {
            return isAlive(adminItem) && isAlive(adminMarker) && isAlive(adminText)
                    && adminItem.getWorld() == location.getWorld()
                    && adminMarker.getWorld() == location.getWorld()
                    && adminText.getWorld() == location.getWorld();
        }

        private void ensureNodeDisplays(@NotNull Location location, @Nullable SkillTreeNodeDefinition node) {
            if (node == null) {
                removePlayerDisplays();
                return;
            }
            if (hasPlayerDisplays()) {
                return;
            }
            removePlayerDisplays();
            lockedItem = spawnItem(location, service.createNodeDisplayItem(node, false));
            unlockedItem = spawnItem(location, service.createNodeDisplayItem(node, true));
            lockedText = spawnText(textLocation(location), service.nodeName(node, false));
            unlockedText = spawnText(textLocation(location), service.nodeName(node, true));
            viewerStates.clear();
        }

        private void teleport(@NotNull Location location) {
            teleportAll(itemLocation(location), adminItem);
            teleportAll(textLocation(location), adminMarker, adminText);
            if (hasPlayerDisplays()) {
                teleportAll(itemLocation(location), lockedItem, unlockedItem);
                teleportAll(textLocation(location), lockedText, unlockedText);
            }
        }

        private void updateViewer(
                @NotNull Plugin plugin,
                @NotNull Player player,
                @NotNull PositionViewerState nextState,
                @Nullable String positionId
        ) {
            UUID playerId = player.getUniqueId();
            if (viewerStates.get(playerId) == nextState) {
                return;
            }
            if (positionId != null && isAlive(adminText)) {
                adminText.text(component("&d" + positionId));
            }
            show(plugin, player, List.of(adminItem), nextState == PositionViewerState.ADMIN_ONLY || nextState == PositionViewerState.ADMIN_PREVIEW);
            show(plugin, player, List.of(adminMarker, adminText), nextState == PositionViewerState.ADMIN_ONLY || nextState == PositionViewerState.ADMIN_PREVIEW);
            if (hasPlayerDisplays()) {
                boolean preview = nextState == PositionViewerState.ADMIN_PREVIEW || nextState == PositionViewerState.PLAYER_LOCKED;
                boolean unlocked = nextState == PositionViewerState.PLAYER_UNLOCKED;
                show(plugin, player, List.of(lockedItem, lockedText), preview);
                show(plugin, player, List.of(unlockedItem, unlockedText), unlocked);
            }
            viewerStates.put(playerId, nextState);
        }

        private boolean hasPlayerDisplays() {
            return isAlive(lockedItem) && isAlive(lockedText) && isAlive(unlockedItem) && isAlive(unlockedText);
        }

        private void removePlayerDisplays() {
            removeEntity(lockedItem);
            removeEntity(lockedText);
            removeEntity(unlockedItem);
            removeEntity(unlockedText);
            lockedItem = null;
            lockedText = null;
            unlockedItem = null;
            unlockedText = null;
        }

        private void remove() {
            removeEntity(adminItem);
            removeEntity(adminMarker);
            removeEntity(adminText);
            removePlayerDisplays();
            viewerStates.clear();
        }
    }

    private boolean allAlive(@NotNull List<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (!isAlive(entity)) {
                return false;
            }
        }
        return true;
    }

    private void show(@NotNull Plugin plugin, @NotNull Player player, @NotNull List<? extends Entity> entities, boolean visible) {
        for (Entity entity : entities) {
            if (!isAlive(entity)) {
                continue;
            }
            if (visible) {
                player.showEntity(plugin, entity);
            } else {
                player.hideEntity(plugin, entity);
            }
        }
    }

    private void removeAll(@NotNull List<? extends Entity> entities) {
        for (Entity entity : entities) {
            removeEntity(entity);
        }
    }

    private void removeEntity(@Nullable Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    private void teleportAll(@NotNull Location location, @NotNull Entity... entities) {
        for (Entity entity : entities) {
            if (isAlive(entity)) {
                entity.teleport(location);
            }
        }
    }

}
