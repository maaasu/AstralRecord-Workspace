package io.github.maaasu.astralRecord.feature.market.model;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketListing(
    UUID listingId,
    UUID sellerAccountId,
    String sellerAccountName,
    int sellerAccountSlotIndex,
    @Nullable UUID buyerAccountId,
    @Nullable UUID sourceInventoryEntryId,
    String itemCategory,
    String itemId,
    @Nullable String instanceType,
    @Nullable UUID instanceId,
    @Nullable EquipmentInstance equipmentInstance,
    long quantity,
    long remainingQuantity,
    String currencyId,
    long unitPrice,
    long totalPrice,
    long priceFloor,
    @Nullable Long referenceUnitPrice,
    @Nullable Double priceDeviationRate,
    String priceConfidence,
    @Nullable String valuationSignature,
    @Nullable String valuationSnapshotJson,
    String status,
    @Nullable String statusReason,
    Instant listedAt,
    Instant expiresAt,
    @Nullable Instant soldAt,
    @Nullable Instant canceledAt,
    int version,
    Instant createdAt,
    Instant updatedAt,
    long pendingProceeds,
    List<UUID> sourceInventoryEntryIds,
    List<UUID> affectedInventoryEntryIds
) {
    /**
     * API の状態更新や一覧 cache より先に期限へ達した出品も期限切れとして扱います。
     *
     * @return 保存済み状態または現在時刻から期限切れと判断できる場合は true
     */
    public boolean isExpired() {
        return "EXPIRED".equalsIgnoreCase(status)
            || ("ACTIVE".equalsIgnoreCase(status) && expiresAt != null && !expiresAt.isAfter(Instant.now()));
    }
}
