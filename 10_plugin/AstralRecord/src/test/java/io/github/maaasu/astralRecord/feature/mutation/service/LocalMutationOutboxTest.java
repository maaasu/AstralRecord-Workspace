package io.github.maaasu.astralRecord.feature.mutation.service;

import io.github.maaasu.astralRecord.feature.mutation.model.LocalMutationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMutationOutboxTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 3. 所有インスタンス > ### ローカルmutation outbox
     * 検証契約: ENHANCEの最小確定レコードはバイナリへ保存され、再読込後のACKで未送信ファイルから削除される。
     */
    @Test
    void persistsMinimalBinaryCommandAndRemovesItAfterAck() throws Exception {
        UUID operationId = UUID.randomUUID();
        LocalMutationCommand.EquipmentOrb command = equipmentCommand(operationId);
        CompletableFuture<LocalMutationOutbox.Delivery> firstDelivery = new CompletableFuture<>();

        LocalMutationOutbox first = new LocalMutationOutbox(temporaryDirectory, Runnable::run);
        first.setDispatcher(ignored -> firstDelivery);
        first.enqueue(command);

        Path file = temporaryDirectory.resolve("pending-mutations.bin");
        assertTrue(Files.exists(file));
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length < 512);
        assertArrayEquals(
            new byte[] {0x41, 0x52, 0x4D, 0x4F},
            new byte[] {bytes[0], bytes[1], bytes[2], bytes[3]}
        );
        first.close();

        Files.copy(file, temporaryDirectory.resolve("pending-mutations.bin.tmp"));
        Files.delete(file);

        CompletableFuture<LocalMutationOutbox.Delivery> secondDelivery = new CompletableFuture<>();
        LocalMutationOutbox second = new LocalMutationOutbox(temporaryDirectory, Runnable::run);
        second.setDispatcher(ignored -> secondDelivery);
        assertEquals(1, second.pendingCount());

        secondDelivery.complete(LocalMutationOutbox.Delivery.ACK);

        assertEquals(0, second.pendingCount());
        assertFalse(Files.exists(file));
        second.close();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: ローカルレベルアップのoutboxはentry UUIDと数量を再起動後も同じ支払いとして保持する。
     */
    @Test
    void keepsSkillPaymentAmountsAcrossReload() throws Exception {
        UUID operationId = UUID.randomUUID();
        UUID paymentEntryId = UUID.randomUUID();
        LocalMutationCommand.SkillLevelUp command = new LocalMutationCommand.SkillLevelUp(
            operationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            3,
            4,
            7,
            8,
            List.of(new LocalMutationCommand.Payment(paymentEntryId, 3L))
        );
        CompletableFuture<LocalMutationOutbox.Delivery> firstDelivery = new CompletableFuture<>();
        LocalMutationOutbox first = new LocalMutationOutbox(temporaryDirectory, Runnable::run);
        first.setDispatcher(ignored -> firstDelivery);
        first.enqueue(command);
        first.close();

        CompletableFuture<LocalMutationOutbox.Delivery> secondDelivery = new CompletableFuture<>();
        LocalMutationCommand[] reloaded = new LocalMutationCommand[1];
        LocalMutationOutbox second = new LocalMutationOutbox(temporaryDirectory, Runnable::run);
        second.setDispatcher(loaded -> {
            reloaded[0] = loaded;
            return secondDelivery;
        });

        assertEquals(1, second.pendingCount());
        LocalMutationCommand.SkillLevelUp loaded = (LocalMutationCommand.SkillLevelUp) reloaded[0];
        assertEquals(3L, loaded.payments().getFirst().amount());
        assertEquals(paymentEntryId, loaded.payments().getFirst().inventoryEntryId());
        secondDelivery.complete(LocalMutationOutbox.Delivery.ACK);
        second.close();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### 共有更新とライフサイクルの境界
     * 検証契約: 旧形式移行の先頭が再送待ちの間、同accountの後続操作を送らない。
     */
    @Test
    void retryingLegacyHeadCannotBeOvertaken() {
        LocalMutationCommand.EquipmentOrb first = equipmentCommand(UUID.randomUUID());
        LocalMutationCommand.EquipmentOrb next = new LocalMutationCommand.EquipmentOrb(
            UUID.randomUUID(), first.accountId(), UUID.randomUUID(), UUID.randomUUID(), "orb.test", 3, 0, 4, true);
        List<UUID> dispatched = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompletableFuture<LocalMutationOutbox.Delivery> delivery = new CompletableFuture<>();
        try (LocalMutationOutbox outbox = new LocalMutationOutbox(temporaryDirectory, Runnable::run)) {
            outbox.setDispatcher(command -> {
                dispatched.add(command.operationId());
                return delivery;
            });
            outbox.enqueue(first);
            outbox.enqueue(next);
            outbox.dispatch();
            assertEquals(List.of(first.operationId()), dispatched);
            delivery.complete(LocalMutationOutbox.Delivery.RETRY);
            outbox.dispatch();
            assertEquals(List.of(first.operationId()), dispatched);
            assertEquals(2, outbox.pendingCount());
            assertTrue(outbox.hasPending(first.accountId()));
        }
    }

    private static LocalMutationCommand.EquipmentOrb equipmentCommand(UUID operationId) {
        return new LocalMutationCommand.EquipmentOrb(
            operationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "orb.test",
            2,
            0,
            3,
            true
        );
    }
}
