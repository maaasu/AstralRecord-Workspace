package io.github.maaasu.astralRecord.feature.trade.model;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class TradeSession {
    private final UUID sessionId;
    private final UUID playerAUuid;
    private final UUID playerBUuid;
    private final String playerAName;
    private final String playerBName;
    private List<ItemStack> playerAItems;
    private List<ItemStack> playerBItems;
    private boolean playerAReady;
    private boolean playerBReady;
    private TradeSessionStatus status;
    private final Instant openedAt;
    private Instant updatedAt;

    public TradeSession(
        @NotNull UUID sessionId,
        @NotNull UUID playerAUuid,
        @NotNull String playerAName,
        @NotNull UUID playerBUuid,
        @NotNull String playerBName,
        @NotNull Instant openedAt
    ) {
        this.sessionId = sessionId;
        this.playerAUuid = playerAUuid;
        this.playerBUuid = playerBUuid;
        this.playerAName = playerAName;
        this.playerBName = playerBName;
        this.playerAItems = List.of();
        this.playerBItems = List.of();
        this.status = TradeSessionStatus.OPEN;
        this.openedAt = openedAt;
        this.updatedAt = openedAt;
    }

    public UUID getSessionId() { return sessionId; }
    public UUID getPlayerAUuid() { return playerAUuid; }
    public UUID getPlayerBUuid() { return playerBUuid; }
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

    public String getPartnerName(@NotNull UUID playerUuid) {
        return playerAUuid.equals(playerUuid) ? playerBName : playerAName;
    }

    public List<ItemStack> getItems(@NotNull UUID playerUuid) {
        return cloneItems(playerAUuid.equals(playerUuid) ? playerAItems : playerBItems);
    }

    public List<ItemStack> getPartnerItems(@NotNull UUID playerUuid) {
        return getItems(getPartnerUuid(playerUuid));
    }

    public void setItems(@NotNull UUID playerUuid, @NotNull List<ItemStack> items) {
        List<ItemStack> clones = cloneItems(items);
        List<ItemStack> current = playerAUuid.equals(playerUuid) ? playerAItems : playerBItems;
        if (sameItems(current, clones)) {
            return;
        }
        if (playerAUuid.equals(playerUuid)) {
            playerAItems = clones;
        } else {
            playerBItems = clones;
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
