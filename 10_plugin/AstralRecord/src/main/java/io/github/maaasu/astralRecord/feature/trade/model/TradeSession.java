package io.github.maaasu.astralRecord.feature.trade.model;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TradeSession {
    private final UUID sessionId;
    private final UUID playerAUuid;
    private final UUID playerBUuid;
    private final UUID playerAAccountId;
    private final UUID playerBAccountId;
    private final String playerAName;
    private final String playerBName;
    private List<ItemStack> playerAItems;
    private List<ItemStack> playerBItems;
    private List<UUID> playerAItemSourceEntryIds;
    private List<UUID> playerBItemSourceEntryIds;
    private long playerAGoldAmount;
    private long playerBGoldAmount;
    private boolean playerAReady;
    private boolean playerBReady;
    private TradeSessionStatus status;
    private final Instant openedAt;
    private Instant updatedAt;

    public TradeSession(
        @NotNull UUID sessionId,
        @NotNull UUID playerAUuid,
        @NotNull UUID playerAAccountId,
        @NotNull String playerAName,
        @NotNull UUID playerBUuid,
        @NotNull UUID playerBAccountId,
        @NotNull String playerBName,
        @NotNull Instant openedAt
    ) {
        this.sessionId = sessionId;
        this.playerAUuid = playerAUuid;
        this.playerBUuid = playerBUuid;
        this.playerAAccountId = playerAAccountId;
        this.playerBAccountId = playerBAccountId;
        this.playerAName = playerAName;
        this.playerBName = playerBName;
        this.playerAItems = List.of();
        this.playerBItems = List.of();
        this.playerAItemSourceEntryIds = List.of();
        this.playerBItemSourceEntryIds = List.of();
        this.status = TradeSessionStatus.OPEN;
        this.openedAt = openedAt;
        this.updatedAt = openedAt;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getPlayerAUuid() { return playerAUuid; }
    public UUID getPlayerBUuid() { return playerBUuid; }
    public UUID getPlayerAAccountId() { return playerAAccountId; }
    public UUID getPlayerBAccountId() { return playerBAccountId; }
    public String getPlayerAName() { return playerAName; }
    public String getPlayerBName() { return playerBName; }
    public boolean isPlayerAReady() { return playerAReady; }
    public boolean isPlayerBReady() { return playerBReady; }
    public TradeSessionStatus getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public boolean contains(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) || playerBUuid.equals(playerUuid);
    }

    public UUID getPartnerUuid(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerBUuid : playerAUuid;
    }

    public UUID getAccountId(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerAAccountId : playerBAccountId;
    }

    public String getPartnerName(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerBName : playerAName;
    }

    public List<ItemStack> getItems(@NotNull UUID playerUuid) {
        return cloneItems(playerAUuid.equals(playerUuid) ? playerAItems : playerBItems);
    }

    public List<ItemStack> getPartnerItems(@NotNull UUID playerUuid) {
        return getItems(getPartnerUuid(playerUuid));
    }

    public long getGoldAmount(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerAGoldAmount : playerBGoldAmount;
    }

    public long getPartnerGoldAmount(@NotNull UUID playerUuid) {
        return getGoldAmount(getPartnerUuid(playerUuid));
    }

    public void setItems(@NotNull UUID playerUuid, @NotNull List<ItemStack> items) {
        setItems(playerUuid, items, null);
    }

    /**
     * 提示 item と API 確定で使う元 inventory entry ID を同時に更新します。
     *
     * @param playerUuid 提示者 UUID
     * @param items 提示 item
     * @param sourceEntryIds item と同順の source entry ID。テスト用など未指定時は null
     */
    public void setItems(
        @NotNull UUID playerUuid,
        @NotNull List<ItemStack> items,
        @Nullable List<UUID> sourceEntryIds
    ) {
        List<ItemStack> clones = cloneItems(items);
        List<ItemStack> current = playerAUuid.equals(playerUuid) ? playerAItems : playerBItems;
        List<UUID> currentSourceIds = playerAUuid.equals(playerUuid)
            ? playerAItemSourceEntryIds : playerBItemSourceEntryIds;
        List<UUID> normalizedSourceIds = normalizeSourceEntryIds(clones.size(), sourceEntryIds);
        if (sameItems(current, clones) && currentSourceIds.equals(normalizedSourceIds)) {
            return;
        }
        if (playerAUuid.equals(playerUuid)) {
            playerAItems = clones;
            playerAItemSourceEntryIds = normalizedSourceIds;
        } else {
            playerBItems = clones;
            playerBItemSourceEntryIds = normalizedSourceIds;
        }
        playerAReady = false;
        playerBReady = false;
        touch();
    }

    /**
     * 現在の提示を API 確定用 entry ID・数量へ変換します。
     *
     * @param playerUuid 提示者 UUID
     * @return source entry が確定している提示明細。未解決明細があれば空のリスト
     */
    public @NotNull List<TradeCommitItem> getCommitItems(@NotNull UUID playerUuid) {
        List<ItemStack> items = playerAUuid.equals(playerUuid) ? playerAItems : playerBItems;
        List<UUID> sourceIds = playerAUuid.equals(playerUuid)
            ? playerAItemSourceEntryIds : playerBItemSourceEntryIds;
        if (items.size() != sourceIds.size() || sourceIds.stream().anyMatch(java.util.Objects::isNull)) {
            return List.of();
        }
        List<TradeCommitItem> result = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            result.add(new TradeCommitItem(sourceIds.get(index), items.get(index).getAmount()));
        }
        return List.copyOf(result);
    }

    public @Nullable UUID getItemSourceEntryId(@NotNull UUID playerUuid, int offerIndex) {
        List<UUID> sourceIds = playerAUuid.equals(playerUuid)
            ? playerAItemSourceEntryIds : playerBItemSourceEntryIds;
        return offerIndex < 0 || offerIndex >= sourceIds.size() ? null : sourceIds.get(offerIndex);
    }

    public void setGoldAmount(@NotNull UUID playerUuid, long amount) {
        long normalized = Math.max(0L, amount);
        if (playerAUuid.equals(playerUuid)) {
            if (playerAGoldAmount == normalized) {
                return;
            }
            playerAGoldAmount = normalized;
        } else {
            if (playerBGoldAmount == normalized) {
                return;
            }
            playerBGoldAmount = normalized;
        }
        playerAReady = false;
        playerBReady = false;
        touch();
    }

    public boolean isReady(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerAReady : playerBReady;
    }

    public boolean isPartnerReady(@NotNull UUID playerUuid) {
        return isReady(getPartnerUuid(playerUuid));
    }

    public void setReady(@NotNull UUID playerUuid, boolean ready) {
        if (playerAUuid.equals(playerUuid)) {
            playerAReady = ready;
        } else {
            playerBReady = ready;
        }
        touch();
    }

    public void resetReady() {
        playerAReady = false;
        playerBReady = false;
        touch();
    }

    public void setStatus(@NotNull TradeSessionStatus status) {
        this.status = status;
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    private static List<ItemStack> cloneItems(@NotNull List<ItemStack> items) {
        List<ItemStack> clones = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                clones.add(item.clone());
            }
        }
        return clones;
    }

    private static @NotNull List<UUID> normalizeSourceEntryIds(int itemCount, @Nullable List<UUID> sourceEntryIds) {
        if (sourceEntryIds == null || sourceEntryIds.size() != itemCount) {
            return new ArrayList<>(java.util.Collections.nCopies(itemCount, null));
        }
        return new ArrayList<>(sourceEntryIds);
    }

    private static boolean sameItems(@NotNull List<ItemStack> left, @NotNull List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            ItemStack leftItem = left.get(i);
            ItemStack rightItem = right.get(i);
            if (leftItem.getAmount() != rightItem.getAmount() || !leftItem.isSimilar(rightItem)) {
                return false;
            }
        }
        return true;
    }
}
