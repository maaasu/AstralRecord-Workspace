package io.github.maaasu.astralrecordgeyser;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.pack.exception.ResourcePackException;
import org.geysermc.geyser.api.pack.option.PriorityOption;

/** 接続には検証済みのパックを一度だけ選び、更新中も選んだファイルを維持する。 */
final class DamagePackDistribution {
    private final AstralRecordGeyserExtension extension;
    private final Map<GeyserConnection, SessionPack> sessions = new ConcurrentHashMap<>();
    private final List<DamagePack> retired = new ArrayList<>();
    private final Object publicationLock = new Object();
    private DamagePack current;
    private boolean fetchFailed;

    DamagePackDistribution(AstralRecordGeyserExtension extension) {
        this.extension = extension;
    }

    void start() {
        refresh();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("astralrecord-bedrock-pack").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            if (extension.isEnabled()) refresh();
            else scheduler.shutdown();
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void refresh() {
        try {
            var folder = extension.dataFolder();
            var config = HeadApiConfig.load(folder);
            DamagePack next = DamagePack.refresh(config, folder.resolve("downloaded-packs"), current);
            if (next != current) {
                synchronized (publicationLock) {
                    if (current != null) retired.add(current);
                    current = next;
                }
                extension.logger().info("Bedrock damage resource pack ready: " + next.pack().uuid());
            }
            fetchFailed = false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            if (!fetchFailed) {
                extension.logger().warning("Bedrock damage resource pack refresh failed (" + exception.getClass().getSimpleName()
                    + "); retaining any previously verified pack and retrying in one minute.");
            }
            fetchFailed = true;
        }
        removeRetiredPacks();
    }

    private void removeRetiredPacks() {
        List<DamagePack> unused;
        synchronized (publicationLock) {
            unused = retired.stream().filter(pack -> sessions.values().stream().noneMatch(session -> session.pack() == pack)).toList();
        }
        // Retired packs cannot be newly registered. Delete outside the publication lock.
        retired.removeIf(pack -> {
            if (!unused.contains(pack)) return false;
            try {
                Files.deleteIfExists(pack.file());
                return true;
            } catch (IOException exception) {
                return false; // Windows may still have the last chunk's file handle open.
            }
        });
    }

    void register(SessionLoadResourcePacksEvent event) {
        synchronized (publicationLock) {
            if (current == null) return;
            try {
                event.register(current.pack(), PriorityOption.NORMAL);
                sessions.put(event.connection(), new SessionPack(current, event, false));
            } catch (ResourcePackException exception) {
                extension.logger().warning("Bedrock damage pack registration failed: " + exception.cause());
            }
        }
    }

    void onLogin(GeyserConnection connection) {
        // SessionLogin follows Bedrock pack negotiation. There is no per-pack ACK in the public API.
        sessions.computeIfPresent(connection, (key, selection) -> {
            if (selection.event() == null) return selection;
            boolean registered = selection.event().resourcePacks().stream().anyMatch(pack -> pack == selection.pack().pack());
            return new SessionPack(selection.pack(), null, registered);
        });
    }

    boolean ready(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready();
    }

    void disconnect(GeyserConnection connection) {
        sessions.remove(connection);
    }

    private record SessionPack(DamagePack pack, SessionLoadResourcePacksEvent event, boolean ready) {}
}
