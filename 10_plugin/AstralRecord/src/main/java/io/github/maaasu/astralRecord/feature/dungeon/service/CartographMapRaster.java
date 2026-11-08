package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/** Java 版と統合版で一致する地図色を使う、北を上としたカルトグラフの地図面です。 */
final class CartographMapRaster {
    static final int SIZE = 128;
    private static final int BORDER = 3;
    private static final int MAP_SPAN = 112;
    private static final int MARKER_RADIUS = 5;
    private static final int DIRECTIONS = 16;
    private static final Color PARCHMENT = new Color(0xd5c98c);
    private static final Color UNEXPLORED = new Color(0xaea473);
    private static final Color CLOSED_EDGE = new Color(0x827b56);
    private static final Color OPEN_EDGE = new Color(0x0b4246);
    private static final Color CLEARED = new Color(0x7fa796);
    private static final Color AVAILABLE = new Color(0xf7e9a3);
    private static final Color AVAILABLE_EDGE = new Color(0x835d19);
    private static final Color ACTIVE = new Color(0xba8524);
    private static final Color CURRENT = new Color(0x0f585e);
    private static final Color WHITE = Color.WHITE;

    private final DungeonLayout layout;
    private final Map<Integer, DungeonMapRoomState> states;
    private final @Nullable Integer currentRoom;
    private final double scale;
    private final double left;
    private final double top;
    private final BufferedImage background;

    CartographMapRaster(
            @NotNull DungeonLayout layout,
            @NotNull Map<Integer, DungeonMapRoomState> states,
            @Nullable Integer currentRoom
    ) {
        this.layout = layout;
        this.states = Map.copyOf(states);
        this.currentRoom = currentRoom;
        scale = (double) MAP_SPAN / Math.max(layout.areaWidth(), layout.areaDepth());
        left = (SIZE - layout.areaWidth() * scale) / 2;
        top = (SIZE - layout.areaDepth() * scale) / 2;
        background = drawBackground();
    }

    boolean matches(DungeonLayout candidate, Map<Integer, DungeonMapRoomState> nextStates, Integer nextRoom) {
        return layout == candidate && states.equals(nextStates) && java.util.Objects.equals(currentRoom, nextRoom);
    }

    @NotNull BufferedImage background() {
        return background;
    }

    @NotNull Marker marker(double x, double z, float yaw) {
        int direction = Math.floorMod(Math.round(yaw / (360.0F / DIRECTIONS)), DIRECTIONS);
        return new Marker(
                Math.clamp(pixelX(x), MARKER_RADIUS, SIZE - MARKER_RADIUS - 1),
                Math.clamp(pixelZ(z), MARKER_RADIUS, SIZE - MARKER_RADIUS - 1),
                direction
        );
    }

    private int pixelX(double x) {
        return (int) Math.floor(left + x * scale);
    }

    private int pixelZ(double z) {
        return (int) Math.floor(top + z * scale);
    }

    private @NotNull BufferedImage drawBackground() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(PARCHMENT);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.setColor(CLOSED_EDGE);
            graphics.drawRect(BORDER, BORDER, SIZE - BORDER * 2 - 1, SIZE - BORDER * 2 - 1);
            graphics.setColor(UNEXPLORED);
            for (int z = 8; z <= 120; z += 8) {
                for (int x = 8; x <= 120; x += 8) {
                    graphics.fillRect(x, z, 1, 1);
                }
            }
            for (DungeonLayout.Connection connection : layout.connections()) {
                drawCorridor(graphics, connection);
            }
            graphics.setStroke(new BasicStroke(1));
            for (DungeonLayout.Room room : layout.rooms()) {
                drawRoom(graphics, room);
            }
            graphics.setColor(OPEN_EDGE);
            graphics.drawPolyline(new int[] {7, 7, 10, 10}, new int[] {11, 6, 11, 6}, 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawCorridor(Graphics2D graphics, DungeonLayout.Connection connection) {
        if (connection.centerLine().isEmpty()) {
            return;
        }
        Path2D path = new Path2D.Double();
        DungeonLayout.Point start = connection.centerLine().getFirst();
        path.moveTo(pixelX(start.x()), pixelZ(start.z()));
        for (DungeonLayout.Point point : connection.centerLine()) {
            path.lineTo(pixelX(point.x()), pixelZ(point.z()));
        }
        boolean open = state(connection.fromRoomId()) == DungeonMapRoomState.CLEARED
                && state(connection.toRoomId()) != DungeonMapRoomState.LOCKED;
        graphics.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(open ? OPEN_EDGE : CLOSED_EDGE);
        graphics.draw(path);
        graphics.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(open ? CLEARED : UNEXPLORED);
        graphics.draw(path);
    }

    private void drawRoom(Graphics2D graphics, DungeonLayout.Room room) {
        DungeonMapRoomState state = state(room.id());
        boolean current = currentRoom != null && currentRoom == room.id();
        Color fill = current ? CURRENT : switch (state) {
            case LOCKED -> UNEXPLORED;
            case AVAILABLE -> AVAILABLE;
            case ACTIVE -> ACTIVE;
            case CLEARED -> CLEARED;
        };
        Color outline = current || state == DungeonMapRoomState.CLEARED ? OPEN_EDGE
                : state == DungeonMapRoomState.LOCKED ? CLOSED_EDGE : AVAILABLE_EDGE;
        DungeonLayout.Rect bounds = room.bounds();
        int x = pixelX(bounds.minX());
        int z = pixelZ(bounds.minZ());
        int width = pixelX(bounds.maxX()) - x;
        int height = pixelZ(bounds.maxZ()) - z;
        graphics.setColor(fill);
        if (room.shape() == DungeonRoomShape.CYLINDER) {
            graphics.fillOval(x, z, width + 1, height + 1);
            graphics.setColor(outline);
            graphics.drawOval(x, z, width, height);
        } else {
            graphics.fillRect(x, z, width + 1, height + 1);
            graphics.setColor(outline);
            graphics.drawRect(x, z, width, height);
        }
        if (!current && state == DungeonMapRoomState.AVAILABLE) {
            int centerX = x + width / 2;
            int centerZ = z + height / 2;
            graphics.fillPolygon(new int[] {centerX, centerX + 2, centerX, centerX - 2},
                    new int[] {centerZ - 2, centerZ, centerZ + 2, centerZ}, 4);
        }
    }

    private DungeonMapRoomState state(int room) {
        return states.getOrDefault(room, DungeonMapRoomState.LOCKED);
    }

    record Marker(int x, int z, int direction) {
        int left() { return x - MARKER_RADIUS; }
        int top() { return z - MARKER_RADIUS; }

        @NotNull BufferedImage backgroundPatch(BufferedImage background) {
            int span = MARKER_RADIUS * 2 + 1;
            return background.getSubimage(left(), top(), span, span);
        }

        @NotNull BufferedImage paintedPatch(BufferedImage background) {
            int span = MARKER_RADIUS * 2 + 1;
            BufferedImage patch = new BufferedImage(span, span, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = patch.createGraphics();
            try {
                graphics.drawImage(backgroundPatch(background), 0, 0, null);
                // Bukkit yaw 0 は南、90 は西。地図は北を上、東を右に固定します。
                double angle = direction * (Math.PI * 2 / DIRECTIONS);
                graphics.translate(MARKER_RADIUS, MARKER_RADIUS);
                graphics.rotate(angle);
                Polygon arrow = new Polygon(new int[] {0, -4, 0, 4}, new int[] {4, -4, -2, -4}, 4);
                graphics.setColor(OPEN_EDGE);
                graphics.drawPolygon(arrow);
                graphics.setColor(WHITE);
                graphics.fillPolygon(arrow);
            } finally {
                graphics.dispose();
            }
            return patch;
        }
    }
}
