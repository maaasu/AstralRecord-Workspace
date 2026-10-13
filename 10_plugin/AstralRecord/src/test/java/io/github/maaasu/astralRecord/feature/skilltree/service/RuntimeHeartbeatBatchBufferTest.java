package io.github.maaasu.astralRecord.feature.skilltree.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeHeartbeatBatchBufferTest {
    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: master公開開始と停止を跨いだcaptureとACKは失効し、再起動後も古いepochを再利用しない。
     */
    @Test
    void publicationAndStopFencePreviouslyCapturedRequestsAndAcknowledgements() {
        RuntimeHeartbeatEpoch epoch = new RuntimeHeartbeatEpoch();
        long beforePublication = epoch.capture();
        assertTrue(epoch.isCurrent(beforePublication));
        epoch.invalidate();
        assertFalse(epoch.isCurrent(beforePublication));
        long afterPublication = epoch.capture();
        assertTrue(epoch.isCurrent(afterPublication));
        epoch.stop();
        assertFalse(epoch.isCurrent(afterPublication));
        long whileStopped = epoch.capture();
        assertFalse(epoch.isCurrent(whileStopped));
        epoch.start();
        assertFalse(epoch.isCurrent(whileStopped));
        assertTrue(epoch.isCurrent(epoch.capture()));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 32件超の同期要求は各batchの上限を守って入力順に一度だけ排出され、周期末で残件を失わない。
     */
    @Test
    void drainsAllAccountsExactlyOnceAcrossCapacityAndPeriodicFlushes() {
        RuntimeHeartbeatBatchBuffer<Integer> buffer = new RuntimeHeartbeatBatchBuffer<>();
        List<Integer> delivered = new ArrayList<>();
        for (int tick = 0; tick < 100; tick++) {
            for (int account = tick * 37; account < (tick + 1) * 37; account++) {
                List<Integer> batch = buffer.offer(account);
                assertTrue(batch.size() <= 32);
                delivered.addAll(batch);
            }
            if (tick % 10 == 9) {
                List<Integer> batch = buffer.drain();
                assertTrue(batch.size() <= 32);
                delivered.addAll(batch);
            }
        }
        assertEquals(IntStream.range(0, 3700).boxed().toList(), delivered);
        assertTrue(buffer.drain().isEmpty());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## スキルツリー世代を含むmaster公開 > ### スキルツリーruntime同期
     * 検証契約: 停止時の排出は未送信accountだけを返し、停止後の再排出・次周期で重複送信しない。
     */
    @Test
    void stopDrainReturnsAllHeldAccountsAndAllowsCleanRestart() {
        RuntimeHeartbeatBatchBuffer<Integer> buffer = new RuntimeHeartbeatBatchBuffer<>();
        for (int i = 0; i < 7; i++) assertTrue(buffer.offer(i).isEmpty());
        assertEquals(IntStream.range(0, 7).boxed().toList(), buffer.drain());
        assertTrue(buffer.drain().isEmpty());
        assertTrue(buffer.offer(99).isEmpty());
        assertEquals(List.of(99), buffer.drain());
    }
}
