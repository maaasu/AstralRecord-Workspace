package io.github.maaasu.astralrecordgeyser;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoadResourcePacksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.api.pack.exception.ResourcePackException;
import org.geysermc.geyser.api.pack.option.PriorityOption;

/** 接続には検証済みのパックを一度だけ選び、更新中も選んだファイルを維持する。 */
final class DamagePackDistribution {
    private final AstralRecordGeyserExtension extension;
    private final Map<GeyserConnection, SessionPack> sessions = new ConcurrentHashMap<>();
    private final List<DamagePack> retired = new ArrayList<>();
    private final Object publicationLock = new Object();
    private DamagePack current;
    // 配布を見送ったパックのSHAも保持し、同じアーカイブを再取得しない。
    private DamagePack lastVerified;
    private Set<ItemModel> registeredModels = Set.of();
    private boolean fetchFailed;

    DamagePackDistribution(AstralRecordGeyserExtension extension) {
        this.extension = extension;
    }

    void defineItems(GeyserDefineCustomItemsEvent event) {
        refresh(event);
    }

    private void registerModels(GeyserDefineCustomItemsEvent event, Set<ItemModel> models) {
        Set<ItemModel> registered = new HashSet<>();
        for (ItemModel model : models) {
            try {
                model.register(event);
                registered.add(model);
            } catch (RuntimeException exception) {
                extension.logger().error("Bedrock item model registration failed for " + model.id() + " ("
                    + exception.getClass().getSimpleName() + "). Check the catalog and restart the proxy.");
            }
        }
        registeredModels = Set.copyOf(registered);
        if (!registeredModels.isEmpty()) {
            extension.logger().info("Registered AstralRecord Bedrock item models: " + registeredModels.size());
        }
    }

    void start() {
        if (current == null && !fetchFailed) refresh(null);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("astralrecord-bedrock-pack").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            if (extension.isEnabled()) refresh(null);
            else scheduler.shutdown();
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void refresh(GeyserDefineCustomItemsEvent itemRegistration) {
        try {
            var folder = extension.dataFolder();
            var config = HeadApiConfig.load(folder);
            DamagePack next = DamagePack.refresh(config, folder.resolve("downloaded-packs"), lastVerified);
            if (next != lastVerified) {
                if (itemRegistration != null) registerModels(itemRegistration, next.itemModels());
                publish(next, itemRegistration == null);
                lastVerified = next;
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

    private void publish(DamagePack next, boolean warnAboutNewModels) throws IOException {
        // Geyserのアイテム登録は起動時のみ。既存の登録に必要な定義を含むパックだけを配布する。
        if (!next.itemModels().containsAll(registeredModels)) {
            Files.deleteIfExists(next.file());
            extension.logger().warning("Bedrock item model catalog is incompatible with registered mappings; "
                + "retaining the current pack until the proxy restarts or a compatible pack is published.");
            return;
        }
        if (warnAboutNewModels && !next.itemModels().equals(registeredModels)) {
            extension.logger().warning("Bedrock item model catalog has unregistered models; restart the proxy to use them.");
        }
        synchronized (publicationLock) {
            if (current != null) retired.add(current);
            current = next;
        }
        extension.logger().info("Bedrock damage resource pack ready: " + next.pack().uuid());
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
            boolean registered = selection.event().resourcePacks().stream()
                .anyMatch(pack -> pack.uuid().equals(selection.pack().pack().uuid()));
            return new SessionPack(selection.pack(), null, registered);
        });
    }

    boolean readyForDamageNumbers(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() && selection.pack().damageNumbers();
    }

    boolean actionRingReady(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() && selection.pack().actionRing();
    }

    boolean weaponAttackReady(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() && selection.pack().weaponAttack();
    }

    /** @return ログイン時に登録済みのパックが専用効果音に対応していればtrue */
    boolean gameplaySoundsReady(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() && selection.pack().gameplaySounds();
    }

    OptionalInt heldWeaponModel(GeyserConnection connection, int modelId) {
        SessionPack selection = sessions.get(connection);
        if (selection == null || !selection.ready() || selection.pack().heldWeaponModels().isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(selection.pack().heldWeaponModels().contains(modelId) ? modelId : 0);
    }

    /** @return 接続時に登録したパックに討伐リザルトの紋章が揃っていればtrue */
    boolean combatResultReady(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() && selection.pack().combatResult();
    }

    Set<Integer> actionRingSprites(GeyserConnection connection) {
        SessionPack selection = sessions.get(connection);
        return selection != null && selection.ready() ? selection.pack().actionRingSprites() : Set.of();
    }

    void disconnect(GeyserConnection connection) {
        sessions.remove(connection);
    }

    private record SessionPack(DamagePack pack, SessionLoadResourcePacksEvent event, boolean ready) {}
}
