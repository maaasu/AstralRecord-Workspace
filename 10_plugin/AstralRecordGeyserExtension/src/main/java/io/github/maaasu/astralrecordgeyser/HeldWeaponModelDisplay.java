package io.github.maaasu.astralrecordgeyser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.entity.property.type.GeyserFloatEntityProperty;

/** 更新通知が途切れた接続をバニラ表示に戻します。 */
final class HeldWeaponModelDisplay {
    private static final long LEASE_NANOS = TimeUnit.SECONDS.toNanos(8);

    private final GeyserFloatEntityProperty property;
    private final Map<GeyserConnection, Lease> leases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("astralrecord-held-weapon").factory());

    HeldWeaponModelDisplay(GeyserFloatEntityProperty property) {
        this.property = property;
    }

    void start() {
        scheduler.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    void update(GeyserConnection connection, int modelId) {
        Lease lease = modelId == 0 ? leases.get(connection) : leases.computeIfAbsent(connection, key -> new Lease());
        if (lease == null) return;
        lease.renew(modelId, System.nanoTime());

        // Proxy がサーバー切り替えで解除した表示を、同じモデル番号の通知でも復元する。
        connection.playerEntity().updatePropertiesBatched(batch -> {
            if (leases.get(connection) == lease) lease.apply(value -> batch.update(property, (float) value));
        }, true);
    }

    private void expire() {
        long now = System.nanoTime();
        leases.forEach((connection, lease) -> {
            if (!lease.expired(now)) return;
            try {
                connection.playerEntity().updatePropertiesBatched(batch -> {
                    // 更新が先に届いている可能性があるので、接続のイベントループでも期限を確認する。
                    if (leases.get(connection) == lease) {
                        lease.expire(System.nanoTime(), value -> batch.update(property, (float) value));
                    }
                }, true);
            } catch (RejectedExecutionException ignored) {
                // 終了したイベントループには送れないため、この接続を監視対象から外す。
                leases.remove(connection, lease);
            }
        });
    }

    void disconnect(GeyserConnection connection) {
        leases.remove(connection);
    }

    void shutdown() {
        scheduler.shutdownNow();
        leases.clear();
    }

    static final class Lease {
        private int modelId;
        private long expiresAt;

        synchronized void renew(int value, long now) {
            modelId = value;
            expiresAt = now + LEASE_NANOS;
        }

        synchronized boolean expired(long now) {
            return modelId != 0 && now - expiresAt >= 0;
        }

        synchronized void expire(long now, IntConsumer applyModel) {
            if (!expired(now)) return;
            modelId = 0;
            apply(applyModel);
        }

        synchronized void apply(IntConsumer applyModel) {
            applyModel.accept(modelId);
        }
    }
}
