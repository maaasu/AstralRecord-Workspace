package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * スキルツリー構造とプレイヤー別ノード状態を Display Entity で描画します。
 */
final class SkillTreeVisualizer {
    private static final long INTERVAL_TICKS = 10L;
    private static final double VIEW_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double EDGE_STEP = 0.75D;

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
            PositionVisual visual = positions.computeIfAbsent(position.positionId(), ignored -> createPositionVisual(position, location));
            visual.teleport(location);
            updatePositionViewers(position, visual, location);
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
            SkillTreeNodeDefinition leftNode = service.getNodeByPositionId(edge.leftPositionId());
            SkillTreeNodeDefinition rightNode = service.getNodeByPositionId(edge.rightPositionId());
            if (left == null || right == null || leftNode == null || rightNode == null) {
                continue;
            }
            Location leftLocation = left.toLocation();
            Location rightLocation = right.toLocation();
            if (leftLocation == null || rightLocation == null || leftLocation.getWorld() != rightLocation.getWorld()) {
                continue;
            }
            activeEdges.add(edge.key());
            EdgeVisual visual = edges.computeIfAbsent(edge.key(), ignored -> createEdgeVisual(leftLocation, rightLocation));
            visual.teleport(leftLocation, rightLocation);
            updateEdgeViewers(edge, visual, leftLocation);
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
    private PositionVisual createPositionVisual(@NotNull SkillTreePosition position, @NotNull Location location) {
        ItemDisplay adminItem = spawnItem(location, new ItemStack(Material.ARMOR_STAND));
        TextDisplay adminText = spawnText(location.clone().add(0.0D, 1.2D, 0.0D), component("&d" + position.positionId()));
        return new PositionVisual(adminItem, adminText, null, null, null, null);
    }

    private void updatePositionViewers(@NotNull SkillTreePosition position, @NotNull PositionVisual visual, @NotNull Location location) {
        SkillTreeNodeDefinition node = service.getNodeByPositionId(position.positionId());
        if (node != null && visual.lockedItem == null) {
            visual.createPlayerDisplays(location, service.createNodeDisplayItem(node, false), service.createNodeDisplayItem(node, true),
                    service.nodeName(node, false), service.nodeName(node, true));
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean near = player.getWorld() == location.getWorld() && player.getLocation().distanceSquared(location) <= VIEW_DISTANCE_SQ;
            boolean adminVisible = near && service.shouldShowAdminPosition(player, location);
            visual.showAdmin(plugin, player, adminVisible);

            boolean nodeVisible = near && node != null && service.shouldShowPlayerNode(player, location);
            boolean unlocked = false;
            var astPlayer = AstPlayerCache.get(player);
            if (nodeVisible && astPlayer != null) {
                SkillTreePlayerState state = service.state(astPlayer);
                unlocked = state.isUnlocked(node.id());
            }
            visual.showPlayer(plugin, player, nodeVisible, unlocked);
        }
    }

    @NotNull
    private EdgeVisual createEdgeVisual(@NotNull Location left, @NotNull Location right) {
        int count = Math.max(1, (int) Math.floor(left.distance(right) / EDGE_STEP));
        List<TextDisplay> gray = new ArrayList<>();
        List<TextDisplay> white = new ArrayList<>();
        List<TextDisplay> yellow = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            Location location = interpolate(left, right, (double) i / count);
            gray.add(spawnText(location, component("&7*")));
            white.add(spawnText(location, component("&f*")));
            yellow.add(spawnText(location, component("&e*")));
        }
        return new EdgeVisual(gray, white, yellow);
    }

    private void updateEdgeViewers(@NotNull SkillTreeEdge edge, @NotNull EdgeVisual visual, @NotNull Location origin) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean visible = player.getWorld() == origin.getWorld()
                    && player.getLocation().distanceSquared(origin) <= VIEW_DISTANCE_SQ
                    && service.shouldShowPlayerNode(player, origin);
            visual.show(plugin, player, visible, service.edgeState(player, edge));
        }
    }

    @NotNull
    private ItemDisplay spawnItem(@NotNull Location location, @NotNull ItemStack itemStack) {
        return location.getWorld().spawn(location.clone().add(0.0D, 0.15D, 0.0D), ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setItemStack(itemStack);
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
    private Location interpolate(@NotNull Location left, @NotNull Location right, double t) {
        Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
        return vector.toLocation(left.getWorld()).add(0.0D, 0.65D, 0.0D);
    }

    @NotNull
    private Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private record EdgeVisual(
            @NotNull List<TextDisplay> gray,
            @NotNull List<TextDisplay> white,
            @NotNull List<TextDisplay> yellow
    ) {
        private void teleport(@NotNull Location left, @NotNull Location right) {
            int count = gray.size() + 1;
            for (int i = 0; i < gray.size(); i++) {
                double t = (double) (i + 1) / count;
                Vector vector = left.toVector().multiply(1.0D - t).add(right.toVector().multiply(t));
                Location target = vector.toLocation(left.getWorld()).add(0.0D, 0.65D, 0.0D);
                gray.get(i).teleport(target);
                white.get(i).teleport(target);
                yellow.get(i).teleport(target);
            }
        }

        private void show(@NotNull Plugin plugin, @NotNull Player player, boolean visible, int state) {
            show(plugin, player, visible && state == 0, gray);
            show(plugin, player, visible && state == 1, white);
            show(plugin, player, visible && state == 2, yellow);
        }

        private void show(@NotNull Plugin plugin, @NotNull Player player, boolean visible, @NotNull List<TextDisplay> points) {
            points.forEach(point -> {
                if (visible) player.showEntity(plugin, point);
                else player.hideEntity(plugin, point);
            });
        }

        private void remove() {
            gray.forEach(Entity::remove);
            white.forEach(Entity::remove);
            yellow.forEach(Entity::remove);
        }
    }

    private static final class PositionVisual {
        private final ItemDisplay adminItem;
        private final TextDisplay adminText;
        private ItemDisplay lockedItem;
        private TextDisplay lockedText;
        private ItemDisplay unlockedItem;
        private TextDisplay unlockedText;

        private PositionVisual(
                @NotNull ItemDisplay adminItem,
                @NotNull TextDisplay adminText,
                ItemDisplay lockedItem,
                TextDisplay lockedText,
                ItemDisplay unlockedItem,
                TextDisplay unlockedText
        ) {
            this.adminItem = adminItem;
            this.adminText = adminText;
            this.lockedItem = lockedItem;
            this.lockedText = lockedText;
            this.unlockedItem = unlockedItem;
            this.unlockedText = unlockedText;
        }

        private void createPlayerDisplays(@NotNull Location location, @NotNull ItemStack locked, @NotNull ItemStack unlocked,
                                          @NotNull Component lockedName, @NotNull Component unlockedName) {
            lockedItem = location.getWorld().spawn(location.clone().add(0.0D, 0.15D, 0.0D), ItemDisplay.class, display -> setupItem(display, locked));
            unlockedItem = location.getWorld().spawn(location.clone().add(0.0D, 0.15D, 0.0D), ItemDisplay.class, display -> setupItem(display, unlocked));
            lockedText = location.getWorld().spawn(location.clone().add(0.0D, 1.2D, 0.0D), TextDisplay.class, display -> setupText(display, lockedName));
            unlockedText = location.getWorld().spawn(location.clone().add(0.0D, 1.2D, 0.0D), TextDisplay.class, display -> setupText(display, unlockedName));
        }

        private static void setupItem(@NotNull ItemDisplay display, @NotNull ItemStack itemStack) {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setItemStack(itemStack);
        }

        private static void setupText(@NotNull TextDisplay display, @NotNull Component text) {
            display.setPersistent(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.text(text);
        }

        private void teleport(@NotNull Location location) {
            adminItem.teleport(location.clone().add(0.0D, 0.15D, 0.0D));
            adminText.teleport(location.clone().add(0.0D, 1.2D, 0.0D));
            if (lockedItem != null) {
                lockedItem.teleport(location.clone().add(0.0D, 0.15D, 0.0D));
                lockedText.teleport(location.clone().add(0.0D, 1.2D, 0.0D));
                unlockedItem.teleport(location.clone().add(0.0D, 0.15D, 0.0D));
                unlockedText.teleport(location.clone().add(0.0D, 1.2D, 0.0D));
            }
        }

        private void showAdmin(@NotNull Plugin plugin, @NotNull Player player, boolean visible) {
            show(plugin, player, visible, adminItem, adminText);
        }

        private void showPlayer(@NotNull Plugin plugin, @NotNull Player player, boolean visible, boolean unlocked) {
            if (lockedItem == null) {
                return;
            }
            show(plugin, player, visible && !unlocked, lockedItem, lockedText);
            show(plugin, player, visible && unlocked, unlockedItem, unlockedText);
        }

        private void show(@NotNull Plugin plugin, @NotNull Player player, boolean visible, @NotNull Entity... entities) {
            for (Entity entity : entities) {
                if (visible) {
                    player.showEntity(plugin, entity);
                } else {
                    player.hideEntity(plugin, entity);
                }
            }
        }

        private void remove() {
            for (Entity entity : new Entity[]{adminItem, adminText, lockedItem, lockedText, unlockedItem, unlockedText}) {
                if (entity != null) {
                    entity.remove();
                }
            }
        }
    }
}
