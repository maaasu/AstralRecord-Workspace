package io.github.maaasu.astralRecord.feature.skilltree.service;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** プレイヤー状態を変更しないWeb要求の拒否結果を、既存の重要保存境界で確定します。 */
public final class SkillTreeOperationReceiptService {
    private static final String SECTION = "skillTreeOperation";
    private static final Set<String> NON_APPLIED_STATUSES = Set.of(
        "RECONFIRMATION_REQUIRED", "FAILED", "CANCELED", "EXPIRED");
    private final InventoryService inventoryService;
    private final Map<UUID, JsonObject> pending = new ConcurrentHashMap<>();

    /**
     * 保存参加者を一度登録します。SkillTreeServiceと同じライフサイクルで構築してください。
     * @param inventoryService アカウント別の重要保存境界を管理するサービス
     */
    public SkillTreeOperationReceiptService(@NotNull InventoryService inventoryService) {
        this.inventoryService = Objects.requireNonNull(inventoryService);
        inventoryService.getPersistence().registerStateParticipant(this::capture);
    }

    /**
     * 課金・ノード変更を伴わない結果を登録し、同一snapshotのSQL ACK後だけ成功を返します。
     * 結果不明では既存保存coordinatorが同一snapshotを再送し、完了まで操作境界を保持します。
     * APPLIEDは解放状態と同じskillTree sectionに含めるため、この入口では拒否します。
     * @param accountId ロード済みの対象アカウント
     * @param receipt API契約のoperationId・lease・世代・更新番号・最終状態を持つ確定要求
     * @return SQL ACKを検証できた場合true。確定失敗・状態未ロードでは例外終了
     * @throws IllegalArgumentException 最終状態が拒否結果でない場合
     */
    public @NotNull CompletableFuture<Boolean> recordResult(@NotNull UUID accountId, @NotNull JsonObject receipt) {
        JsonObject captured = receipt.deepCopy();
        if (!captured.has("finalStatus") || !NON_APPLIED_STATUSES.contains(captured.get("finalStatus").getAsString())) {
            throw new IllegalArgumentException("A non-applied skill tree operation status is required");
        }
        UUID.fromString(captured.get("operationId").getAsString());
        return inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            if (pending.putIfAbsent(accountId, captured) != null) {
                throw new IllegalStateException("A skill tree operation acknowledgement is pending");
            }
            return new InventorySaveCoordinator.CriticalMutation<>(true, () -> pending.remove(accountId, captured));
        });
    }

    /** stateロック内で不変receiptを捕捉し、対応するACKの後だけ保留を解除します。 */
    private @Nullable PlayerStateSection capture(@NotNull UUID accountId) {
        JsonObject captured = pending.get(accountId);
        if (captured == null) return null;
        return new PlayerStateSection(SECTION, captured, ack -> {
            JsonObject result = ack.getAsJsonObject();
            if (!captured.get("operationId").equals(result.get("operationId"))
                || !captured.get("finalStatus").equals(result.get("status"))) {
                throw new IllegalStateException("Skill tree operation acknowledgement mismatch");
            }
            pending.remove(accountId, captured);
        });
    }
}
