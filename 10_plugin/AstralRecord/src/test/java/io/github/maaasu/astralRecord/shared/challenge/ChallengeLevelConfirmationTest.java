package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChallengeLevelConfirmationTest {
    /**
     * 推奨が設定され、通常時の現レベルまたは転生開始時レベルが推奨未満の場合だけ、その値の警告を返す。
     */
    @Test
    void warnsOnlyWhenApplicablePlayerLevelIsBelowRecommendation() {
        assertEquals(new ChallengeLevelConfirmation.Warning(2, false),
                ChallengeLevelConfirmation.findWarning(account(2, null), 5));
        assertNull(ChallengeLevelConfirmation.findWarning(account(5, null), 5));
        assertNull(ChallengeLevelConfirmation.findWarning(account(2, 10), 5));
        assertEquals(new ChallengeLevelConfirmation.Warning(4, true),
                ChallengeLevelConfirmation.findWarning(account(2, 4), 5));
        assertNull(ChallengeLevelConfirmation.findWarning(account(2, null), null));
    }

    /** 過去最高レベルの影響を受けない、テスト用アカウントを構築します。 */
    private AccountModel account(int level, Integer originalLevel) {
        UUID userId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        return new AccountModel(
                UUID.randomUUID(), userId, "challenge-test", 0, true, AccountMode.PLAYER, "{}",
                createdAt, createdAt, userId, userId, false, level, 0L, "adventurer", 1, 0L,
                List.of(), 0, 50, originalLevel, 0
        );
    }
}
