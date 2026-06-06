package io.github.maaasu.astralRecord.temp.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketDisplayHandle;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketDisplayService;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketItemDisplayOptions;
import io.github.maaasu.astralRecord.shared.packetdisplay.PacketTextDisplayOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketDisplay の viewer 単位表示を切り分けて確認するための /test コマンドです。
 */
public final class TestCommand extends AstCommand {
    private static final long AUTO_CLEAR_TICKS = 20L * 30L;
    private static final double FORWARD_DISTANCE = 2.4D;
    private static final double LINE_STEP = 0.45D;
    private static final int DEFAULT_LINE_POINTS = 8;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    /**
     * TestCommand を生成します。
     */
    public TestCommand() {
        super("test", "PacketDisplay の表示テストを行います。", "/test packet <demo|item|text|line|clear>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 1, player.getBukkit())) {
            return;
        }
        if (!"packet".equalsIgnoreCase(args[0])) {
            sendUsage(player.getBukkit());
            return;
        }
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "demo" -> spawnDemo(player, args);
            case "item" -> spawnItem(player, args);
            case "text" -> spawnText(player, args);
            case "line" -> spawnLine(player, args);
            case "clear" -> clearSession(player.getBukkit().getUniqueId());
            default -> sendUsage(player.getBukkit());
        }
    }

    private void spawnDemo(@NotNull AstPlayer player, @NotNull String[] args) {
        Material material = args.length >= 3 ? resolveMaterial(args[2]) : Material.DIAMOND;
        if (material == null) {
            sendError(player.getBukkit(), "不明な Material です: " + args[2]);
            return;
        }

        var bukkit = player.getBukkit();
        PacketDisplayService displayService = new PacketDisplayService(bukkit);
        Session session = resetSession(bukkit.getUniqueId());

        Location base = forwardLocation(bukkit, FORWARD_DISTANCE);
        session.add(displayService.spawnItem(base.clone().add(0.0D, 0.15D, 0.0D), new PacketItemDisplayOptions(new ItemStack(material), 0.72F, 48.0F)));
        session.add(displayService.spawnText(base.clone().add(0.0D, 1.2D, 0.0D), new PacketTextDisplayOptions(
                component("&ePACKET DEMO"),
                1.0F,
                160,
                48.0F,
                Color.fromARGB(0, 0, 0, 0),
                true,
                true
        )));
        spawnLineDisplays(displayService, session, base.clone().add(-1.5D, 0.65D, 0.0D), DEFAULT_LINE_POINTS);
        scheduleAutoClear(bukkit.getUniqueId(), session);
        sendInfo(bukkit, "packet demo を表示しました。30 秒後に自動削除されます。");
    }

    private void spawnItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 3, player.getBukkit())) {
            return;
        }
        Material material = resolveMaterial(args[2]);
        if (material == null) {
            sendError(player.getBukkit(), "不明な Material です: " + args[2]);
            return;
        }

        var bukkit = player.getBukkit();
        PacketDisplayService displayService = new PacketDisplayService(bukkit);
        Session session = resetSession(bukkit.getUniqueId());
        Location base = forwardLocation(bukkit, FORWARD_DISTANCE);
        session.add(displayService.spawnItem(base.clone().add(0.0D, 0.15D, 0.0D), new PacketItemDisplayOptions(new ItemStack(material), 0.72F, 48.0F)));
        scheduleAutoClear(bukkit.getUniqueId(), session);
        sendInfo(bukkit, "packet item を表示しました: " + material.name());
    }

    private void spawnText(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 3, player.getBukkit())) {
            return;
        }

        var bukkit = player.getBukkit();
        PacketDisplayService displayService = new PacketDisplayService(bukkit);
        Session session = resetSession(bukkit.getUniqueId());
        Location base = forwardLocation(bukkit, FORWARD_DISTANCE).add(0.0D, 1.2D, 0.0D);
        session.add(displayService.spawnText(base, new PacketTextDisplayOptions(
                component(joinArgs(args, 2)),
                1.0F,
                160,
                48.0F,
                Color.fromARGB(0, 0, 0, 0),
                true,
                true
        )));
        scheduleAutoClear(bukkit.getUniqueId(), session);
        sendInfo(bukkit, "packet text を表示しました。");
    }

    private void spawnLine(@NotNull AstPlayer player, @NotNull String[] args) {
        int points = DEFAULT_LINE_POINTS;
        if (args.length >= 3) {
            try {
                points = Math.max(2, Math.min(32, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                sendError(player.getBukkit(), "line の点数は 2-32 の整数で指定してください。");
                return;
            }
        }

        var bukkit = player.getBukkit();
        PacketDisplayService displayService = new PacketDisplayService(bukkit);
        Session session = resetSession(bukkit.getUniqueId());
        spawnLineDisplays(displayService, session, forwardLocation(bukkit, FORWARD_DISTANCE), points);
        scheduleAutoClear(bukkit.getUniqueId(), session);
        sendInfo(bukkit, "packet line を表示しました。points=" + points);
    }

    private void spawnLineDisplays(
            @NotNull PacketDisplayService displayService,
            @NotNull Session session,
            @NotNull Location start,
            int points
    ) {
        Vector right = start.getDirection().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (right.lengthSquared() == 0.0D) {
            right = new Vector(1.0D, 0.0D, 0.0D);
        }
        right.normalize();
        for (int i = 0; i < points; i++) {
            Location point = start.clone().add(right.clone().multiply(i * LINE_STEP));
            session.add(displayService.spawnText(point, new PacketTextDisplayOptions(
                    component("&b*"),
                    0.75F,
                    80,
                    48.0F,
                    Color.fromARGB(0, 0, 0, 0),
                    false,
                    true
            )));
        }
    }

    private @NotNull Session resetSession(@NotNull UUID playerId) {
        clearSession(playerId);
        Session session = new Session();
        SESSIONS.put(playerId, session);
        return session;
    }

    private void scheduleAutoClear(@NotNull UUID playerId, @NotNull Session session) {
        BukkitTask task = AstralRecord.getInstance().getServer().getScheduler().runTaskLater(
                AstralRecord.getInstance(),
                () -> {
                    Session current = SESSIONS.get(playerId);
                    if (current == session) {
                        clearSession(playerId);
                    }
                },
                AUTO_CLEAR_TICKS
        );
        session.setCleanupTask(task);
    }

    private void clearSession(@NotNull UUID playerId) {
        Session session = SESSIONS.remove(playerId);
        if (session != null) {
            session.destroy();
        }
    }

    private @Nullable Material resolveMaterial(@NotNull String input) {
        Material material = Material.matchMaterial(input.trim(), true);
        if (material == null || material == Material.AIR) {
            return null;
        }
        return material;
    }

    private @NotNull Location forwardLocation(@NotNull org.bukkit.entity.Player player, double distance) {
        Location location = player.getEyeLocation().clone();
        location.add(location.getDirection().normalize().multiply(distance));
        location.setPitch(0.0F);
        return location;
    }

    private @NotNull Component component(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(ColorCodeUtil.translateAlternateColorCodes(text));
    }

    private static final class Session {
        private final List<PacketDisplayHandle> handles = new ArrayList<>();
        private @Nullable BukkitTask cleanupTask;

        private void add(@NotNull PacketDisplayHandle handle) {
            handles.add(handle);
        }

        private void setCleanupTask(@NotNull BukkitTask cleanupTask) {
            this.cleanupTask = cleanupTask;
        }

        private void destroy() {
            if (cleanupTask != null) {
                cleanupTask.cancel();
                cleanupTask = null;
            }
            for (PacketDisplayHandle handle : handles) {
                handle.destroy();
            }
            handles.clear();
        }
    }
}
