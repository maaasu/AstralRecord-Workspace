package io.github.maaasu.astralRecord.feature.condition.task;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionTickService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConditionTickTaskTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-タスク・スケジューラ.md
     * 章・見出し: # 27_3-タスク・スケジューラ > ## 1. `ConditionTickTask`
     * 検証契約: 301件では一回300件まで処理し、次回runを最初の未処理conditionから再開する。
     */
    @Test
    void resumesAtFirstUnprocessedConditionOnNextRun() {
        ConditionService conditionService = mock(ConditionService.class);
        ConditionTickService tickService = mock(ConditionTickService.class);
        List<ActiveCondition> conditions = new ArrayList<>();
        for (int index = 0; index < 301; index++) {
            conditions.add(mock(ActiveCondition.class));
        }
        when(conditionService.snapshotAllActiveConditions()).thenReturn(conditions);
        ConditionTickTask task = new ConditionTickTask(conditionService, tickService);

        task.run();

        verify(tickService, never()).tickCondition(eq(conditions.get(300)), anyLong());

        task.run();

        verify(tickService, times(2)).tickCondition(eq(conditions.get(0)), anyLong());
        verify(tickService).tickCondition(eq(conditions.get(299)), anyLong());
        verify(tickService).tickCondition(eq(conditions.get(300)), anyLong());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/27-condition/3-メソッド仕様/27_3-タスク・スケジューラ.md
     * 章・見出し: # 27_3-タスク・スケジューラ > ## 1. `ConditionTickTask`
     * 検証契約: 一件のtick例外を隔離して後続conditionの処理を継続する。
     */
    @Test
    void isolatesOneConditionFailureAndContinuesProcessing() {
        ConditionService conditionService = mock(ConditionService.class);
        ConditionTickService tickService = mock(ConditionTickService.class);
        ActiveCondition failing = mock(ActiveCondition.class);
        ActiveCondition following = mock(ActiveCondition.class);
        when(failing.conditionId()).thenReturn(UUID.randomUUID());
        when(failing.targetId()).thenReturn(UUID.randomUUID());
        when(conditionService.snapshotAllActiveConditions()).thenReturn(List.of(failing, following));
        doThrow(new IllegalStateException("tick failed"))
            .when(tickService).tickCondition(eq(failing), anyLong());
        ConditionTickTask task = new ConditionTickTask(conditionService, tickService);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);

            task.run();
        }

        verify(tickService).tickCondition(eq(following), anyLong());
    }
}
