package io.github.maaasu.astralRecord.feature.player.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerServiceLogoutBoundaryTest {
    /**
     * core と pet の最終保存が両方成功するまで runtime session close を始めない。
     */
    @Test
    void closesRuntimeOnlyAfterCoreAndPetBothSucceed() {
        CompletableFuture<Boolean> core = new CompletableFuture<>();
        CompletableFuture<Void> pet = new CompletableFuture<>();
        AtomicInteger closes = new AtomicInteger();
        CompletableFuture<Boolean> result = PlayerService.finishLogoutAfterRelatedSaves(core, pet,
            closes::incrementAndGet);

        core.complete(true);
        assertFalse(result.isDone());
        assertEquals(0, closes.get());
        pet.complete(null);
        assertTrue(result.join());
        assertEquals(1, closes.get());
    }

    /**
     * core が保存失敗、または pet が例外終了したとき処理権限を閉じない。
     */
    @Test
    void failedCoreOrPetKeepsRuntimeAuthority() {
        AtomicInteger closes = new AtomicInteger();
        CompletableFuture<Boolean> failedCore = PlayerService.finishLogoutAfterRelatedSaves(
            CompletableFuture.completedFuture(false), CompletableFuture.completedFuture(null),
            closes::incrementAndGet);
        assertFalse(failedCore.join());

        CompletableFuture<Void> failedPet = CompletableFuture.failedFuture(new IllegalStateException("pet save"));
        CompletableFuture<Boolean> failedPetResult = PlayerService.finishLogoutAfterRelatedSaves(
            CompletableFuture.completedFuture(true), failedPet, closes::incrementAndGet);
        assertThrows(CompletionException.class, failedPetResult::join);
        assertEquals(0, closes.get());
    }
}
