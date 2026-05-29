package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤーと AstralRecord Mob の頭上に resource status テキストを表示するサービスです。
 *
 * <p>表示 entity はすべて {@link PacketDisplayService} を通じて packet として送信し、
 * 実体 Entity はワールドへ生成しません。</p>
 */
public class OverheadDisplayService {

    private static final long UPDATE_INTERVAL_TICKS = 5L;
    private static final double PLAYER_VIEW_DISTANCE = 64.0D;
    private static final double PLAYER_VIEW_DISTANCE_SQ = PLAYER_VIEW_DISTANCE * PLAYER_VIEW_DISTANCE;
    private static final double PLAYER_TEXT_OFFSET = 0.55D;
    private static final double MOB_TEXT_OFFSET = 2.2D;

    private final PacketDisplayService displayService;
    private final StatusService statusService;
    private final MobService mobService;
    private final Map<UUID, TrackedTextDisplay> playerDisplays = new HashMap<>();
    private final Map<UUID, TrackedTextDisplay> mobDisplays = new HashMap<>();

    private BukkitTask task;

    /**
     * サービスを初期化します。
     *
     * @param displayService packet display 送信用サービス
     * @param statusService  プレイヤーステータス参照サービス
     * @param mobService     Mob 管理サービス
     */
    public OverheadDisplayService(
            @NotNull PacketDisplayService displayService,
            @NotNull StatusService statusService,
            @NotNull MobService mobService
    ) {
        this.displayService = displayService;
        this.statusService = statusService;
        this.mobService = mobService;
    }

    /**
     * 頭上表示の定期更新を開始します。
     *
     * @param plugin scheduler を登録するプラグイン
     */
    public void start(@NotNull Plugin plugin) {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * 頭上表示の定期更新を停止し、表示済み packet entity を破棄します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        destroyAll(playerDisplays);
        destroyAll(mobDisplays);
    }

    private void tick() {
        updatePlayerDisplays();
        updateMobDisplays();
    }

    private void updatePlayerDisplays() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        Set<UUID> activeSubjects = new HashSet<>();

        for (Player subject : onlinePlayers) {
            UUID subjectId = subject.getUniqueId();
            activeSubjects.add(subjectId);

            TrackedTextDisplay display = playerDisplays.computeIfAbsent(
                    subjectId,
                    ignored -> new TrackedTextDisplay(displayService.allocateHandle())
            );

            String text = playerText(subject);
            Location location = subject.getLocation().add(0.0D, subject.getHeight() + PLAYER_TEXT_OFFSET, 0.0D);
            Set<UUID> desiredViewers = new HashSet<>();

            for (Player viewer : onlinePlayers) {
                if (canSeePlayerDisplay(viewer, location)) {
                    desiredViewers.add(viewer.getUniqueId());
                    showOrUpdate(viewer, display, location, text);
                }
            }

            hideRemovedViewers(display, desiredViewers);
        }

        removeStaleDisplays(playerDisplays, activeSubjects);
    }

    private void updateMobDisplays() {
        Set<UUID> activeSubjects = new HashSet<>();

        for (MobInstance instance : mobService.getInstances()) {
            UUID instanceId = instance.instanceId();
            activeSubjects.add(instanceId);

            TrackedTextDisplay display = mobDisplays.computeIfAbsent(
                    instanceId,
                    ignored -> new TrackedTextDisplay(displayService.allocateHandle())
            );

            String text = mobText(instance);
            Location location = instance.currentLocation().add(0.0D, MOB_TEXT_OFFSET, 0.0D);
            Set<UUID> desiredViewers = new HashSet<>();

            for (UUID viewerId : mobService.getViewers(instanceId)) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !viewer.isOnline()) {
                    continue;
                }
                desiredViewers.add(viewerId);
                showOrUpdate(viewer, display, location, text);
            }

            hideRemovedViewers(display, desiredViewers);
        }

        removeStaleDisplays(mobDisplays, activeSubjects);
    }

    private boolean canSeePlayerDisplay(@NotNull Player viewer, @NotNull Location location) {
        if (viewer.getWorld() != location.getWorld()) {
            return false;
        }
        return viewer.getLocation().distanceSquared(location) <= PLAYER_VIEW_DISTANCE_SQ;
    }

    private void showOrUpdate(
            @NotNull Player viewer,
            @NotNull TrackedTextDisplay display,
            @NotNull Location location,
            @NotNull String text
    ) {
        UUID viewerId = viewer.getUniqueId();
        boolean alreadyShown = display.viewers.contains(viewerId);
        if (!alreadyShown) {
            displayService.spawnTextDisplay(viewer, display.handle, location, text);
            display.viewers.add(viewerId);
            display.lastTextByViewer.put(viewerId, text);
        } else {
            displayService.teleport(viewer, display.handle.entityId(), location);
            if (!text.equals(display.lastTextByViewer.get(viewerId))) {
                displayService.updateTextDisplay(viewer, display.handle.entityId(), text);
                display.lastTextByViewer.put(viewerId, text);
            }
        }
    }

    private void hideRemovedViewers(@NotNull TrackedTextDisplay display, @NotNull Set<UUID> desiredViewers) {
        Set<UUID> removed = new HashSet<>(display.viewers);
        removed.removeAll(desiredViewers);
        for (UUID viewerId : removed) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                displayService.destroy(viewer, display.handle.entityId());
            }
            display.viewers.remove(viewerId);
            display.lastTextByViewer.remove(viewerId);
        }
    }

    private void removeStaleDisplays(
            @NotNull Map<UUID, TrackedTextDisplay> displays,
            @NotNull Set<UUID> activeSubjects
    ) {
        Set<UUID> stale = new HashSet<>(displays.keySet());
        stale.removeAll(activeSubjects);
        for (UUID subjectId : stale) {
            TrackedTextDisplay display = displays.remove(subjectId);
            if (display != null) {
                destroyDisplay(display);
            }
        }
    }

    private void destroyAll(@NotNull Map<UUID, TrackedTextDisplay> displays) {
        for (TrackedTextDisplay display : displays.values()) {
            destroyDisplay(display);
        }
        displays.clear();
    }

    private void destroyDisplay(@NotNull TrackedTextDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && viewer.isOnline()) {
                displayService.destroy(viewer, display.handle.entityId());
            }
        }
        display.viewers.clear();
        display.lastTextByViewer.clear();
    }

    @NotNull
    private String playerText(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return "&f" + player.getName();
        }

        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        return String.format(
                Locale.ROOT,
                "&f%s%n&cHP %s/%s &9MP %s/%s &eEN %s/%s",
                player.getName(),
                number(snapshot.getCurrentHp()),
                number(snapshot.getMaxValue(StatusType.MAX_HEALTH)),
                number(snapshot.getCurrentMp()),
                number(snapshot.getMaxValue(StatusType.MAX_MANA)),
                number(snapshot.getCurrentEnergy()),
                number(snapshot.getMaxValue(StatusType.MAX_ENERGY))
        );
    }

    @NotNull
    private String mobText(@NotNull MobInstance instance) {
        double maxHealth = instance.template().statValue(StatusType.MAX_HEALTH.name(), 1.0D);
        return String.format(
                Locale.ROOT,
                "%s%n&cHP %s/%s",
                instance.template().displayName(),
                number(instance.currentHealth()),
                number(maxHealth)
        );
    }

    private String number(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, value));
    }

    private static final class TrackedTextDisplay {
        private final PacketDisplayService.DisplayHandle handle;
        private final Set<UUID> viewers = new HashSet<>();
        private final Map<UUID, String> lastTextByViewer = new HashMap<>();

        private TrackedTextDisplay(@NotNull PacketDisplayService.DisplayHandle handle) {
            this.handle = handle;
        }
    }
}
