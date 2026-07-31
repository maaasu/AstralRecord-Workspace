package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingEntry;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingModel;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerSettingServiceConcurrencyTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 5. 設定更新
     * 検証契約: 同一userの異なるkey更新を直列化し双方のcache entryを失わない。
     */
    @Test
    void concurrentDifferentKeyUpdatesAreSerializedAndPreserveBothCacheEntries() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID damageSettingId = UUID.randomUUID();
        UUID dropSettingId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(
            repository,
            new PlayerSettingDefaults(),
            cache
        );
        long sessionToken = service.beginSession(userId);
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.DAMAGE_LOG_DISPLAY,
            new PlayerSettingEntry(damageSettingId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, false, 1),
            PlayerSettingKey.DROP_LOG_DISPLAY,
            new PlayerSettingEntry(dropSettingId, PlayerSettingKey.DROP_LOG_DISPLAY, false, 1)
        )));

        CountDownLatch firstRepositoryCall = new CountDownLatch(1);
        CountDownLatch releaseFirstRepositoryCall = new CountDownLatch(1);
        CountDownLatch secondRepositoryCall = new CountDownLatch(1);
        when(repository.update(any(UUID.class), anyString(), anyInt(), any(UUID.class)))
            .thenAnswer(invocation -> {
                UUID settingId = invocation.getArgument(0);
                if (damageSettingId.equals(settingId)) {
                    firstRepositoryCall.countDown();
                    assertTrue(releaseFirstRepositoryCall.await(5, TimeUnit.SECONDS));
                    return model(userId, damageSettingId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, 2);
                }
                secondRepositoryCall.countDown();
                return model(userId, dropSettingId, PlayerSettingKey.DROP_LOG_DISPLAY, 2);
            });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PlayerSettingService.UpdateResult> damageUpdate = executor.submit(() ->
                service.updatePlayerSetting(
                    new PlayerSettingChangeRequest(
                        userId,
                        PlayerSettingKey.DAMAGE_LOG_DISPLAY,
                        true,
                        userId
                    ),
                    sessionToken
                )
            );
            assertTrue(firstRepositoryCall.await(5, TimeUnit.SECONDS));

            CountDownLatch secondTaskStarted = new CountDownLatch(1);
            Future<PlayerSettingService.UpdateResult> dropUpdate = executor.submit(() -> {
                secondTaskStarted.countDown();
                return service.updatePlayerSetting(
                    new PlayerSettingChangeRequest(
                        userId,
                        PlayerSettingKey.DROP_LOG_DISPLAY,
                        true,
                        userId
                    ),
                    sessionToken
                );
            });
            assertTrue(secondTaskStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondRepositoryCall.await(500, TimeUnit.MILLISECONDS));

            releaseFirstRepositoryCall.countDown();
            assertTrue(damageUpdate.get(5, TimeUnit.SECONDS).success());
            assertTrue(dropUpdate.get(5, TimeUnit.SECONDS).success());

            PlayerSettingSnapshot snapshot = cache.find(userId);
            assertTrue((Boolean) snapshot.getEntry(PlayerSettingKey.DAMAGE_LOG_DISPLAY).getValue());
            assertTrue((Boolean) snapshot.getEntry(PlayerSettingKey.DROP_LOG_DISPLAY).getValue());
        } finally {
            releaseFirstRepositoryCall.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 2. session 管理と warmup
     * 検証契約: warmup中clearでtokenが失効した場合stale snapshotをcache公開しない。
     */
    @Test
    void clearDuringWarmupPreventsStaleSnapshotFromBeingPublished() throws Exception {
        UUID userId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(
            repository,
            new PlayerSettingDefaults(),
            cache
        );
        CountDownLatch repositoryCall = new CountDownLatch(1);
        CountDownLatch releaseRepositoryCall = new CountDownLatch(1);
        when(repository.findByUserId(userId)).thenAnswer(ignored -> {
            repositoryCall.countDown();
            assertTrue(releaseRepositoryCall.await(5, TimeUnit.SECONDS));
            return List.of(model(userId, UUID.randomUUID(), PlayerSettingKey.DAMAGE_LOG_DISPLAY, 1));
        });

        long sessionToken = service.beginSession(userId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> warmup = executor.submit(() -> service.warmup(userId, sessionToken));
            assertTrue(repositoryCall.await(5, TimeUnit.SECONDS));

            service.clear(userId);
            assertNull(cache.find(userId));

            releaseRepositoryCall.countDown();
            warmup.get(5, TimeUnit.SECONDS);

            assertEquals(0L, service.captureSessionToken(userId));
            assertNull(cache.find(userId));
        } finally {
            releaseRepositoryCall.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 5. 設定更新
     * 検証契約: update中clearでtokenが失効した場合成功modelをstale cacheへ再公開しない。
     */
    @Test
    void clearDuringUpdatePreventsStaleUpdatedSnapshotFromBeingPublished() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID settingId = UUID.randomUUID();
        PlayerSettingRepository repository = mock(PlayerSettingRepository.class);
        PlayerSettingCache cache = new PlayerSettingCache();
        PlayerSettingService service = new PlayerSettingService(
            repository,
            new PlayerSettingDefaults(),
            cache
        );
        long sessionToken = service.beginSession(userId);
        cache.put(new PlayerSettingSnapshot(userId, Map.of(
            PlayerSettingKey.DAMAGE_LOG_DISPLAY,
            new PlayerSettingEntry(settingId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, false, 1)
        )));
        CountDownLatch repositoryCall = new CountDownLatch(1);
        CountDownLatch releaseRepositoryCall = new CountDownLatch(1);
        when(repository.update(any(UUID.class), anyString(), anyInt(), any(UUID.class)))
            .thenAnswer(ignored -> {
                repositoryCall.countDown();
                assertTrue(releaseRepositoryCall.await(5, TimeUnit.SECONDS));
                return model(userId, settingId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, 2);
            });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PlayerSettingService.UpdateResult> update = executor.submit(() ->
                service.updatePlayerSetting(
                    new PlayerSettingChangeRequest(
                        userId,
                        PlayerSettingKey.DAMAGE_LOG_DISPLAY,
                        true,
                        userId
                    ),
                    sessionToken
                )
            );
            assertTrue(repositoryCall.await(5, TimeUnit.SECONDS));

            service.clear(userId);
            releaseRepositoryCall.countDown();

            assertTrue(update.get(5, TimeUnit.SECONDS).success());
            assertNull(cache.find(userId));
        } finally {
            releaseRepositoryCall.countDown();
            executor.shutdownNow();
        }
    }

    private static PlayerSettingModel model(
        UUID userId,
        UUID settingId,
        PlayerSettingKey key,
        int version
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new PlayerSettingModel(
            settingId,
            userId,
            key.getCode(),
            "{\"enabled\":true}",
            version,
            now,
            now,
            userId,
            userId,
            false
        );
    }
}
