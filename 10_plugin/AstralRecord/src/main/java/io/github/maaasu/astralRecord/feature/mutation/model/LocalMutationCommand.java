package io.github.maaasu.astralRecord.feature.mutation.model;

import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * APIへ後送するローカル確定済みmutationの最小データです。
 * <p>
 * この型はJSONやJava serializationを使用せず、{@code LocalMutationOutbox} が専用の
 * バイナリ形式へ変換します。master、表示用文字列、API応答全体は保存しません。
 */
public sealed interface LocalMutationCommand
    permits LocalMutationCommand.EquipmentOrb, LocalMutationCommand.SkillLevelUp {

    @NotNull UUID operationId();

    @NotNull UUID accountId();

    /** 装備オーブ操作のローカル確定結果です。 */
    record EquipmentOrb(
        @NotNull UUID operationId,
        @NotNull UUID accountId,
        @NotNull UUID equipmentInstanceId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull String orbItemId,
        int baseEnhanceLevel,
        int baseTranscendenceRank,
        int enhanceLevel,
        boolean enhancementSucceeded
    ) implements LocalMutationCommand {
        public EquipmentOrb {
            orbItemId = orbItemId.trim();
            if (orbItemId.isBlank()) {
                throw new IllegalArgumentException("Orb item ID is blank.");
            }
        }
    }

    /** スキルレベルアップのローカル確定結果です。 */
    record SkillLevelUp(
        @NotNull UUID operationId,
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        int expectedLevel,
        int targetLevel,
        int expectedVersion,
        int targetVersion,
        @NotNull List<Payment> payments
    ) implements LocalMutationCommand {
        public SkillLevelUp {
            payments = List.copyOf(payments);
        }
    }

    /** スキルレベルアップで消費するentryと数量です。 */
    record Payment(
        @NotNull UUID inventoryEntryId,
        long amount
    ) {
        public Payment {
            if (amount <= 0L) {
                throw new IllegalArgumentException("Payment amount must be positive.");
            }
        }
    }
}
