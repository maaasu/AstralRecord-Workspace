package io.github.maaasu.astralRecord.feature.loot.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.repository.LootRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LootServiceTest {

    private Field pluginInstanceField;
    private AstralRecord previousPluginInstance;

    @BeforeEach
    void providePluginLogger() throws Exception {
        pluginInstanceField = AstralRecord.class.getDeclaredField("instance");
        pluginInstanceField.setAccessible(true);
        previousPluginInstance = (AstralRecord) pluginInstanceField.get(null);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        pluginInstanceField.set(null, plugin);
    }

    @AfterEach
    void restorePluginInstance() throws Exception {
        pluginInstanceField.set(null, previousPluginInstance);
    }

    @Test
    void loadAllPublishesOnlyACompleteImmutableSnapshot() throws Exception {
        LootRepository repository = mock(LootRepository.class);
        LootModel oldLoot = loot("old");
        LootModel firstNewLoot = loot("new_first");
        LootModel secondNewLoot = loot("new_second");
        when(repository.findById("old")).thenReturn(oldLoot);

        CountDownLatch secondEntryRequested = new CountDownLatch(1);
        CountDownLatch allowSecondEntry = new CountDownLatch(1);
        when(repository.findAll()).thenReturn(new BlockingLootList(
            List.of(firstNewLoot, secondNewLoot),
            secondEntryRequested,
            allowSecondEntry
        ));
        LootService service = new LootService(repository);
        assertSame(oldLoot, service.getLoadedOrFetch("old"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> loadResult = executor.submit(service::loadAll);
            assertTrue(secondEntryRequested.await(5, TimeUnit.SECONDS));

            assertSame(oldLoot, service.getLoaded("old"));
            assertNull(service.getLoaded("new_first"));

            allowSecondEntry.countDown();
            assertEquals(2, loadResult.get(5, TimeUnit.SECONDS));
        } finally {
            allowSecondEntry.countDown();
            executor.shutdownNow();
        }

        assertNull(service.getLoaded("old"));
        assertSame(firstNewLoot, service.getLoaded("new_first"));
        assertSame(secondNewLoot, service.getLoaded("new_second"));
        assertEquals(List.of(firstNewLoot, secondNewLoot), service.getLoadedLoots());
    }

    @Test
    void preparedSnapshotDoesNotPublishUntilExplicitReplace() {
        LootRepository repository = mock(LootRepository.class);
        LootModel oldLoot = loot("old");
        LootModel newLoot = loot("new");
        when(repository.findById("old")).thenReturn(oldLoot);
        when(repository.findAll()).thenReturn(List.of(newLoot));
        LootService service = new LootService(repository);
        assertSame(oldLoot, service.getLoadedOrFetch("old"));

        Map<String, LootModel> snapshot = service.loadSnapshot();

        assertSame(oldLoot, service.getLoaded("old"));
        assertNull(service.getLoaded("new"));

        service.replaceSnapshot(snapshot);

        assertNull(service.getLoaded("old"));
        assertSame(newLoot, service.getLoaded("new"));
    }

    private LootModel loot(String id) {
        return new LootModel(1, id, id, 1, List.of());
    }

    private static final class BlockingLootList extends AbstractList<LootModel> {
        private final List<LootModel> delegate;
        private final CountDownLatch secondEntryRequested;
        private final CountDownLatch allowSecondEntry;

        private BlockingLootList(
            List<LootModel> delegate,
            CountDownLatch secondEntryRequested,
            CountDownLatch allowSecondEntry
        ) {
            this.delegate = delegate;
            this.secondEntryRequested = secondEntryRequested;
            this.allowSecondEntry = allowSecondEntry;
        }

        @Override
        public LootModel get(int index) {
            if (index == 1) {
                secondEntryRequested.countDown();
                try {
                    if (!allowSecondEntry.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out while waiting to continue list iteration");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }
}
