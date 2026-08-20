package io.github.maaasu.astralRecord.feature.mail.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * メール一覧、既読化、報酬受取を扱うサービスです。
 */
public final class MailService {
    private static final String REWARD_SOURCE = "mail";
    private static final long RECONCILIATION_DELAY_TICKS = 100L;

    private final Plugin plugin;
    private final MailRepository mailRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final Set<MailClaimKey> claimsInFlight = ConcurrentHashMap.newKeySet();
    private final Set<MailClaimKey> completedClaims = ConcurrentHashMap.newKeySet();
    private BiConsumer<AstPlayer, String> mailReceivedListener = (player, mailId) -> { };

    /**
     * メールサービスを構築します。
     *
     * @param plugin スケジューラーを提供するプラグイン
     * @param mailRepository メール API リポジトリ
     * @param itemService アイテム定義・インスタンスサービス
     * @param inventoryService インベントリサービス
     */
    public MailService(
        @NotNull Plugin plugin,
        @NotNull MailRepository mailRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.mailRepository = mailRepository;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
    }

    /**
     * 表示可能なメール一覧を取得します。
     *
     * @param userId 対象ユーザー ID
     * @param filter 既読フィルター
     * @return メール一覧
     */
    public @NotNull List<MailEntry> list(@NotNull UUID userId, @NotNull MailFilter filter) {
        return mailRepository.findAvailable(userId, filter);
    }

    /**
     * メール報酬の受取成功時の通知先を設定します。
     *
     * @param mailReceivedListener 受取プレイヤーとメールIDを受け取る通知先
     */
    public void setMailReceivedListener(@NotNull BiConsumer<AstPlayer, String> mailReceivedListener) {
        this.mailReceivedListener = mailReceivedListener;
    }

    /**
     * 表示可能な未読メール件数を返します。
     *
     * @param userId 対象ユーザー ID
     * @return 未読メール件数
     */
    public int countUnread(@NotNull UUID userId) {
        return list(userId, MailFilter.UNREAD).size();
    }

    /**
     * メール一覧と表示に必要な報酬定義を非同期で取得します。
     *
     * @param userId 対象ユーザー ID
     * @param filter 既読フィルター
     * @param completion 成功時処理
     * @param failure 失敗時処理
     */
    public void listAsync(
        @NotNull UUID userId,
        @NotNull MailFilter filter,
        @NotNull Consumer<List<MailEntry>> completion,
        @NotNull Runnable failure
    ) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<MailEntry> mails = list(userId, filter);
                preloadRewardModels(mails);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    completedClaims.removeIf(claim -> claim.userId().equals(userId));
                    completion.accept(mails);
                });
            } catch (RuntimeException e) {
                plugin.getServer().getScheduler().runTask(plugin, failure);
            }
        });
    }

    /**
     * メールを既読化し、未読メールであれば報酬を付与します。
     *
     * @param astPlayer 対象プレイヤー
     * @param mail メール
     * @param completion 完了通知
     */
    public void readAndReceive(
        @NotNull AstPlayer astPlayer,
        @NotNull MailEntry mail,
        @NotNull Consumer<Boolean> completion
    ) {
        UUID userId = astPlayer.getUser().getUuid();
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        MailClaimKey claimKey = new MailClaimKey(userId, mail.id());
        if (completedClaims.contains(claimKey)) {
            completion.accept(true);
            return;
        }
        if (!claimsInFlight.add(claimKey)) {
            completion.accept(false);
            return;
        }
        if (mail.read()) {
            completedClaims.add(claimKey);
            claimsInFlight.remove(claimKey);
            completion.accept(true);
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PreparedClaimRewards prepared = mail.receiveOnRead()
                ? prepareRewards(accountId, mail.rewards())
                : new PreparedClaimRewards(List.of(), List.of());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (prepared == null) {
                    finishClaimFailure(claimKey, currentPlayer(playerId, userId, accountId), mail,
                        completion, PlayerMsgId.P_5623);
                    return;
                }
                AstPlayer current = currentPlayer(playerId, userId, accountId);
                if (current == null) {
                    cleanupPreparedInstancesAsync(prepared.instances());
                    finishClaimFailure(claimKey, null, mail, completion, PlayerMsgId.P_5623);
                    return;
                }

                InventoryService.InventoryGrantReceipt receipt;
                if (prepared.rewards().isEmpty()) {
                    receipt = new InventoryService.InventoryGrantReceipt(accountId, List.of());
                } else {
                    receipt = inventoryService.addPreparedRewardsToNormalInventory(current, prepared.rewards());
                }
                if (receipt == null) {
                    cleanupPreparedInstancesAsync(prepared.instances());
                    finishClaimFailure(claimKey, current, mail, completion, PlayerMsgId.P_5623);
                    return;
                }
                markReadAfterGrant(
                    playerId,
                    userId,
                    accountId,
                    claimKey,
                    mail,
                    prepared.instances(),
                    receipt,
                    completion
                );
            });
        });
    }

    /**
     * プレイヤー単位でメールを削除状態にします。
     *
     * @param astPlayer 対象プレイヤー
     * @param mailId メール ID
     * @param completion 完了通知
     */
    public void delete(
        @NotNull AstPlayer astPlayer,
        @NotNull String mailId,
        @NotNull Consumer<Boolean> completion
    ) {
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        UUID userId = astPlayer.getUser().getUuid();
        UUID accountId = astPlayer.getAccount().getUuid();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean deleted;
            try {
                deleted = mailRepository.delete(userId, mailId);
            } catch (RuntimeException e) {
                deleted = false;
            }
            boolean result = deleted;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                AstPlayer current = currentPlayer(playerId, userId, accountId);
                if (current != null) {
                    PlayerMessageService.getInstance().send(
                        current,
                        result ? PlayerMsgId.P_5621 : PlayerMsgId.P_5622
                    );
                }
                completion.accept(result);
            });
        });
    }

    private void markReadAfterGrant(
        @NotNull UUID playerId,
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull MailClaimKey claimKey,
        @NotNull MailEntry mail,
        @NotNull List<InventoryService.PreparedInventoryInstance> preparedInstances,
        @NotNull InventoryService.InventoryGrantReceipt receipt,
        @NotNull Consumer<Boolean> completion
    ) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            MailEntry updated;
            try {
                updated = mailRepository.markRead(userId, mail.id());
            } catch (RuntimeException e) {
                updated = null;
            }
            MailEntry result = updated;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                AstPlayer online = currentPlayer(playerId, userId, accountId);
                if (result == null) {
                    if (inventoryService.rollbackPreparedRewards(receipt)) {
                        cleanupPreparedInstancesAsync(preparedInstances);
                        finishClaimFailure(claimKey, online, mail, completion, PlayerMsgId.P_5622);
                    } else {
                        completion.accept(false);
                        scheduleClaimReconciliation(
                            userId,
                            claimKey,
                            mail,
                            preparedInstances,
                            receipt
                        );
                    }
                    return;
                }

                if (!receipt.mutations().isEmpty() && online != null) {
                    PlayerMessageService.getInstance().send(
                        online,
                        PlayerMsgId.P_5620,
                        mail.title()
                    );
                }
                completedClaims.add(claimKey);
                claimsInFlight.remove(claimKey);
                if (online != null) {
                    mailReceivedListener.accept(online, mail.id());
                }
                completion.accept(true);
            });
        });
    }

    private void scheduleClaimReconciliation(
        @NotNull UUID userId,
        @NotNull MailClaimKey claimKey,
        @NotNull MailEntry mail,
        @NotNull List<InventoryService.PreparedInventoryInstance> preparedInstances,
        @NotNull InventoryService.InventoryGrantReceipt receipt
    ) {
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            MailEntry updated;
            try {
                updated = mailRepository.markRead(userId, mail.id());
            } catch (RuntimeException e) {
                updated = null;
            }
            MailEntry result = updated;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result != null) {
                    completedClaims.add(claimKey);
                    claimsInFlight.remove(claimKey);
                    return;
                }
                if (inventoryService.rollbackPreparedRewards(receipt)) {
                    cleanupPreparedInstancesAsync(preparedInstances);
                    claimsInFlight.remove(claimKey);
                    return;
                }
                scheduleClaimReconciliation(userId, claimKey, mail, preparedInstances, receipt);
            });
        }, RECONCILIATION_DELAY_TICKS);
    }

    private void preloadRewardModels(@NotNull List<MailEntry> mails) {
        for (MailEntry mail : mails) {
            for (MailReward reward : mail.rewards()) {
                if (reward.amount() > 0) {
                    resolveRewardModel(reward);
                }
            }
        }
    }

    private @Nullable PreparedClaimRewards prepareRewards(
        @NotNull UUID accountId,
        @NotNull List<MailReward> rewards
    ) {
        List<InventoryService.PreparedInventoryReward> preparedRewards = new ArrayList<>();
        List<InventoryService.PreparedInventoryInstance> preparedInstances = new ArrayList<>();
        try {
            for (MailReward reward : rewards) {
                if (reward.amount() <= 0) {
                    continue;
                }
                ItemModel model = resolveRewardModel(reward);
                if (model == null) {
                    cleanupPreparedInstances(preparedInstances);
                    return null;
                }

                ItemCategory category = ItemCategory.fromApiValue(model.getCategory());
                List<InventoryService.PreparedInventoryInstance> rewardInstances = new ArrayList<>();
                for (int index = 0; index < reward.amount(); index++) {
                    InventoryService.PreparedInventoryInstance prepared = switch (category) {
                        case EQUIPMENT -> prepareEquipmentInstance(model, accountId);
                        case RUNE -> prepareRuneInstance(model, accountId);
                        default -> null;
                    };
                    if ((category == ItemCategory.EQUIPMENT || category == ItemCategory.RUNE)
                        && prepared == null) {
                        cleanupPreparedInstances(preparedInstances);
                        return null;
                    }
                    if (prepared != null) {
                        rewardInstances.add(prepared);
                        preparedInstances.add(prepared);
                    } else {
                        break;
                    }
                }
                preparedRewards.add(new InventoryService.PreparedInventoryReward(
                    model,
                    reward.amount(),
                    rewardInstances
                ));
            }
            return new PreparedClaimRewards(preparedRewards, preparedInstances);
        } catch (RuntimeException e) {
            cleanupPreparedInstances(preparedInstances);
            return null;
        }
    }

    private @Nullable InventoryService.PreparedInventoryInstance prepareEquipmentInstance(
        @NotNull ItemModel model,
        @NotNull UUID accountId
    ) {
        EquipmentInstance instance = itemService.createEquipmentInstance(
            model.getId(),
            accountId.toString(),
            REWARD_SOURCE,
            accountId.toString()
        );
        if (instance == null) {
            return null;
        }
        UUID instanceId = parseUuidOrNull(instance.getEquipmentInstanceId());
        if (instanceId == null) {
            itemService.deleteEquipmentInstance(instance.getEquipmentInstanceId());
            return null;
        }
        return new InventoryService.PreparedInventoryInstance(InventoryInstanceType.EQUIPMENT, instanceId);
    }

    private @Nullable InventoryService.PreparedInventoryInstance prepareRuneInstance(
        @NotNull ItemModel model,
        @NotNull UUID accountId
    ) {
        RuneInstance instance = itemService.createRuneInstance(
            model.getId(),
            accountId.toString(),
            REWARD_SOURCE,
            accountId.toString()
        );
        if (instance == null) {
            return null;
        }
        UUID instanceId = parseUuidOrNull(instance.getRuneInstanceId());
        return instanceId == null
            ? null
            : new InventoryService.PreparedInventoryInstance(InventoryInstanceType.RUNE, instanceId);
    }

    private @Nullable ItemModel resolveRewardModel(@NotNull MailReward reward) {
        ItemModel model = itemService.findLoadedById(reward.itemId());
        return model != null ? model : itemService.loadItem(reward.itemId(), reward.category());
    }

    private void cleanupPreparedInstancesAsync(
        @NotNull List<InventoryService.PreparedInventoryInstance> preparedInstances
    ) {
        if (preparedInstances.isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(
            plugin,
            () -> cleanupPreparedInstances(preparedInstances)
        );
    }

    private void cleanupPreparedInstances(
        @NotNull List<InventoryService.PreparedInventoryInstance> preparedInstances
    ) {
        for (InventoryService.PreparedInventoryInstance prepared : preparedInstances) {
            if (prepared.instanceType() == InventoryInstanceType.EQUIPMENT) {
                itemService.deleteEquipmentInstance(prepared.instanceId().toString());
            }
        }
    }

    private void finishClaimFailure(
        @NotNull MailClaimKey claimKey,
        @Nullable AstPlayer astPlayer,
        @NotNull MailEntry mail,
        @NotNull Consumer<Boolean> completion,
        @NotNull PlayerMsgId messageId
    ) {
        claimsInFlight.remove(claimKey);
        if (astPlayer != null) {
            if (messageId == PlayerMsgId.P_5623) {
                PlayerMessageService.getInstance().send(astPlayer, messageId, mail.title());
            } else {
                PlayerMessageService.getInstance().send(astPlayer, messageId);
            }
        }
        completion.accept(false);
    }

    private @Nullable AstPlayer currentPlayer(
        @NotNull UUID playerId,
        @NotNull UUID userId,
        @NotNull UUID accountId
    ) {
        Player player = plugin.getServer().getPlayer(playerId);
        AstPlayer astPlayer = player == null || !player.isOnline() ? null : AstPlayerCache.get(player);
        return astPlayer != null
            && astPlayer.getUser().getUuid().equals(userId)
            && astPlayer.getAccount().getUuid().equals(accountId)
            ? astPlayer
            : null;
    }

    private @Nullable UUID parseUuidOrNull(@NotNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record MailClaimKey(@NotNull UUID userId, @NotNull String mailId) {
    }

    private record PreparedClaimRewards(
        @NotNull List<InventoryService.PreparedInventoryReward> rewards,
        @NotNull List<InventoryService.PreparedInventoryInstance> instances
    ) {
        private PreparedClaimRewards {
            rewards = List.copyOf(rewards);
            instances = List.copyOf(instances);
        }
    }
}
