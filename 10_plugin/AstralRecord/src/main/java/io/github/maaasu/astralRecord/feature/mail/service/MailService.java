package io.github.maaasu.astralRecord.feature.mail.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * メール一覧、既読化、報酬受取を扱うサービスです。
 */
public final class MailService {
    private static final String MAIL_CLAIM_SECTION = "mailClaim";
    private static final String MAIL_DELETE_SECTION = "mailDelete";

    private final Plugin plugin;
    private final MailRepository mailRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final Set<MailClaimKey> claimsInFlight = ConcurrentHashMap.newKeySet();
    private final Set<MailClaimKey> completedClaims = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PendingMailClaim> pendingClaimsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, PendingMailDelete> pendingDeletesByAccount = new ConcurrentHashMap<>();
    private final Set<PendingReceivedNotification> pendingReceivedNotifications = ConcurrentHashMap.newKeySet();
    private BiConsumer<AstPlayer, String> mailReceivedListener = (player, mailId) -> { };

    /**
     * メールの既読化・報酬受取処理の結果です。
     *
     * @param success 既読化処理が成功した場合は {@code true}
     * @param rewardReceived この呼び出しで報酬がインベントリへ付与され、SQL ACKまで完了した場合は {@code true}
     */
    public record ReadAndReceiveResult(boolean success, boolean rewardReceived) {
    }

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
        inventoryService.getPersistence().registerStateParticipant(this::captureMailAction);
    }

    /**
     * 表示可能なメール一覧を取得します。
     *
     * @param accountId 対象アカウント ID
     * @param filter 既読フィルター
     * @return メール一覧
     */
    public @NotNull List<MailEntry> list(@NotNull UUID accountId, @NotNull MailFilter filter) {
        return mailRepository.findAvailable(accountId, filter);
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
     * ログイン完了後のプレイヤーへ、受取成功通知を再送します。
     *
     * <p>報酬付与後にプレイヤーが切断した場合、既読化の最終確定時点で
     * {@link AstPlayer} を安全に取得できないため通知を保留します。このメソッドは
     * プレイヤーのデータとキャッシュが準備できた後に呼び出してください。</p>
     *
     * @param astPlayer 通知対象のログイン済みプレイヤー
     */
    public void notifyPendingMailReceived(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getBukkit().isOnline()) {
            return;
        }
        UUID userId = astPlayer.getUser().getUuid();
        UUID accountId = astPlayer.getAccount().getUuid();
        for (PendingReceivedNotification pending : pendingReceivedNotifications) {
            if (!pending.userId().equals(userId)
                || !pending.accountId().equals(accountId)
                || !pendingReceivedNotifications.remove(pending)) {
                continue;
            }
            mailReceivedListener.accept(astPlayer, pending.mailId());
        }
    }

    /**
     * 表示可能な未読メール件数を返します。
     *
     * @param accountId 対象アカウント ID
     * @return 未読メール件数
     */
    public int countUnread(@NotNull UUID accountId) {
        return mailRepository.countUnread(accountId);
    }

    /**
     * メール一覧と表示に必要な報酬定義を非同期で取得します。
     *
     * @param accountId 対象アカウント ID
     * @param filter 既読フィルター
     * @param completion 成功時処理
     * @param failure 失敗時処理
     */
    public void listAsync(
        @NotNull UUID accountId,
        @NotNull MailFilter filter,
        @NotNull Consumer<List<MailEntry>> completion,
        @NotNull Runnable failure
    ) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<MailEntry> mails = list(accountId, filter);
                preloadRewardModels(mails);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
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
     * @param completion 完了通知。引数には既読化の成否と、この呼び出しで報酬が残ったかを渡す。
     *                   rollback 失敗時は既読化が失敗していても報酬が残るため、後者は {@code true}
     */
    public void readAndReceive(
        @NotNull AstPlayer astPlayer,
        @NotNull MailEntry mail,
        @NotNull Consumer<ReadAndReceiveResult> completion
    ) {
        UUID userId = astPlayer.getUser().getUuid();
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        MailClaimKey claimKey = new MailClaimKey(accountId, mail.id());
        if (completedClaims.contains(claimKey)) {
            completion.accept(new ReadAndReceiveResult(true, false));
            return;
        }
        if (!claimsInFlight.add(claimKey)) {
            completion.accept(new ReadAndReceiveResult(false, false));
            return;
        }
        if (mail.read()) {
            completedClaims.add(claimKey);
            claimsInFlight.remove(claimKey);
            completion.accept(new ReadAndReceiveResult(true, false));
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PreparedClaimRewards prepared = mail.receiveOnRead()
                ? prepareRewards(mail.rewards())
                : new PreparedClaimRewards(List.of());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (prepared == null) {
                    finishClaimFailure(claimKey, currentPlayer(playerId, userId, accountId), mail,
                        completion, PlayerMsgId.P_5623);
                    return;
                }
                AstPlayer current = currentPlayer(playerId, userId, accountId);
                if (current == null) {
                    finishClaimFailure(claimKey, null, mail, completion, PlayerMsgId.P_5623);
                    return;
                }
                claimWithinCriticalSnapshot(
                    current, playerId, userId, accountId, claimKey, mail, prepared, completion
                );
            });
        });
    }

    /**
     * アカウント単位でメールを削除状態にし、完成スナップショットのSQL ACK後に完了します。
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
        inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            PendingMailDelete pending = new PendingMailDelete(accountId, UUID.randomUUID(), mailId);
            if (pendingDeletesByAccount.putIfAbsent(accountId, pending) != null) {
                throw new IllegalStateException("Mail delete is already pending for account " + accountId);
            }
            return new io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.CriticalMutation<>(
                true,
                () -> pendingDeletesByAccount.remove(accountId, pending)
            );
        }).whenComplete((deleted, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean result = failure == null && Boolean.TRUE.equals(deleted);
            AstPlayer current = currentPlayer(playerId, userId, accountId);
            if (current != null) {
                PlayerMessageService.getInstance().send(
                    current,
                    result ? PlayerMsgId.P_5621 : PlayerMsgId.P_5622
                );
            }
            completion.accept(result);
        }));
    }

    /**
     * 未読メールの既読化と報酬付与を、同一player-state snapshotで確定します。
     *
     * <p>local mutation と SQL 通信は account lane で実行されます。完了通知と Bukkit GUI 操作だけを
     * メインスレッドへ戻すため、通信中にプレイヤーへ未確定の報酬を表示しません。</p>
     */
    private void claimWithinCriticalSnapshot(
        @NotNull AstPlayer astPlayer,
        @NotNull UUID playerId,
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull MailClaimKey claimKey,
        @NotNull MailEntry mail,
        @NotNull PreparedClaimRewards prepared,
        @NotNull Consumer<ReadAndReceiveResult> completion
    ) {
        inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryRollback = inventoryService.snapshotState(accountId);
            if (inventoryRollback == null) {
                throw new IllegalStateException("Mail claim inventory state is not loaded");
            }
            Runnable equipmentRollback = itemService.captureEquipmentStateRollback(accountId);
            List<InventoryService.PreparedInventoryReward> rewards = materializePreparedRewards(
                accountId, prepared.rewards()
            );
            if (rewards == null) {
                equipmentRollback.run();
                throw new IllegalStateException("Mail reward equipment creation failed");
            }

            PendingMailClaim pending = new PendingMailClaim(
                accountId, UUID.randomUUID(), mail.id()
            );
            if (pendingClaimsByAccount.putIfAbsent(accountId, pending) != null) {
                equipmentRollback.run();
                throw new IllegalStateException("Mail claim is already pending for account " + accountId);
            }
            InventoryService.InventoryGrantReceipt receipt = inventoryService
                .addPreparedRewardsToNormalInventoryStateOnly(astPlayer, rewards);
            if (receipt == null) {
                pendingClaimsByAccount.remove(accountId, pending);
                inventoryService.restoreState(inventoryRollback);
                equipmentRollback.run();
                throw new IllegalStateException("Mail reward inventory grant failed");
            }
            return new io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.CriticalMutation<>(
                receipt,
                () -> {
                    pendingClaimsByAccount.remove(accountId, pending);
                    inventoryService.restoreState(inventoryRollback);
                    equipmentRollback.run();
                }
            );
        }).whenComplete((receipt, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            AstPlayer online = currentPlayer(playerId, userId, accountId);
            if (failure != null || receipt == null) {
                finishClaimFailure(claimKey, online, mail, completion, PlayerMsgId.P_5625);
                return;
            }
            boolean rewardReceived = !receipt.mutations().isEmpty();
            if (rewardReceived && online != null) {
                inventoryService.applyInventoryToGui(online, InventoryType.BAG);
                PlayerMessageService.getInstance().send(online, PlayerMsgId.P_5620, mail.title());
            }
            completedClaims.add(claimKey);
            claimsInFlight.remove(claimKey);
            if (rewardReceived) {
                notifyMailReceived(claimKey, playerId, userId, accountId, mail.id());
            }
            completion.accept(new ReadAndReceiveResult(true, rewardReceived));
        }));
    }

    /**
     * 未確定メール受領をplayer-state snapshotのmailClaim sectionへ捕捉します。
     *
     * <p>APIは {@code clientRevision} と {@code mailId} が一致するACKだけを返します。不一致は
     * ACK不正として重要操作全体を失敗させ、InventorySaveCoordinator がローカル変更を補償します。</p>
     */
    private @Nullable PlayerStateSection captureMailAction(@NotNull UUID accountId) {
        PlayerStateSection claim = captureMailClaim(accountId);
        return claim != null ? claim : captureMailDelete(accountId);
    }

    private @Nullable PlayerStateSection captureMailClaim(@NotNull UUID accountId) {
        PendingMailClaim pending = pendingClaimsByAccount.get(accountId);
        if (pending == null) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", pending.accountId().toString());
        payload.addProperty("clientRevision", pending.clientRevision().toString());
        payload.addProperty("mailId", pending.mailId());
        return new PlayerStateSection(MAIL_CLAIM_SECTION, payload,
            acknowledgement -> acknowledgeMailClaim(pending, acknowledgement));
    }

    private @Nullable PlayerStateSection captureMailDelete(@NotNull UUID accountId) {
        PendingMailDelete pending = pendingDeletesByAccount.get(accountId);
        if (pending == null) {
            return null;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", pending.accountId().toString());
        payload.addProperty("clientRevision", pending.clientRevision().toString());
        payload.addProperty("mailId", pending.mailId());
        return new PlayerStateSection(MAIL_DELETE_SECTION, payload,
            acknowledgement -> acknowledgeMailDelete(pending, acknowledgement));
    }

    private void acknowledgeMailClaim(
        @NotNull PendingMailClaim pending,
        @Nullable JsonElement acknowledgement
    ) {
        if (acknowledgement == null || !acknowledgement.isJsonObject()) {
            throw new IllegalStateException("Missing mail claim acknowledgement");
        }
        JsonObject ack = acknowledgement.getAsJsonObject();
        if (!ack.has("clientRevision") || !ack.has("mailId")
            || !ack.has("version") || !ack.has("readAt")
            || !pending.clientRevision().toString().equals(ack.get("clientRevision").getAsString())
            || !pending.mailId().equals(ack.get("mailId").getAsString())) {
            throw new IllegalStateException("Mismatched mail claim acknowledgement");
        }
        pendingClaimsByAccount.remove(pending.accountId(), pending);
    }

    private void acknowledgeMailDelete(
        @NotNull PendingMailDelete pending,
        @Nullable JsonElement acknowledgement
    ) {
        if (acknowledgement == null || !acknowledgement.isJsonObject()) {
            throw new IllegalStateException("Missing mail delete acknowledgement");
        }
        JsonObject ack = acknowledgement.getAsJsonObject();
        if (!ack.has("clientRevision") || !ack.has("mailId")
            || !ack.has("version") || !ack.has("deletedAt")
            || !pending.clientRevision().toString().equals(ack.get("clientRevision").getAsString())
            || !pending.mailId().equals(ack.get("mailId").getAsString())) {
            throw new IllegalStateException("Mismatched mail delete acknowledgement");
        }
        pendingDeletesByAccount.remove(pending.accountId(), pending);
    }

    private void notifyMailReceived(
        @NotNull MailClaimKey claimKey,
        @NotNull UUID playerId,
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull String mailId
    ) {
        AstPlayer online = currentPlayer(playerId, userId, accountId);
        if (online != null) {
            mailReceivedListener.accept(online, mailId);
            return;
        }
        pendingReceivedNotifications.add(new PendingReceivedNotification(
            userId,
            accountId,
            mailId
        ));
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
        @NotNull List<MailReward> rewards
    ) {
        List<PreparedReward> preparedRewards = new ArrayList<>();
        for (MailReward reward : rewards) {
            if (reward.amount() <= 0) {
                continue;
            }
            ItemModel model = resolveRewardModel(reward);
            if (model == null) {
                return null;
            }
            preparedRewards.add(new PreparedReward(model, reward.amount()));
        }
        return new PreparedClaimRewards(preparedRewards);
    }

    /**
     * API通信前に解決済みの報酬定義から、snapshotへ含める個体参照を生成します。
     * UUIDと乱数はこの一度だけ決め、同一snapshotの再送では再生成しません。
     */
    private @Nullable List<InventoryService.PreparedInventoryReward> materializePreparedRewards(
        @NotNull UUID accountId,
        @NotNull List<PreparedReward> rewards
    ) {
        List<InventoryService.PreparedInventoryReward> materialized = new ArrayList<>();
        for (PreparedReward reward : rewards) {
            List<InventoryService.PreparedInventoryInstance> instances = new ArrayList<>();
            if (ItemCategory.fromApiValue(reward.model().getCategory()) == ItemCategory.EQUIPMENT) {
                for (int index = 0; index < reward.amount(); index++) {
                    var instance = itemService.createLocalEquipmentInstance(reward.model(), accountId);
                    if (instance == null) {
                        return null;
                    }
                    UUID instanceId = parseUuidOrNull(instance.getEquipmentInstanceId());
                    if (instanceId == null) {
                        return null;
                    }
                    instances.add(new InventoryService.PreparedInventoryInstance(
                        InventoryInstanceType.EQUIPMENT, instanceId
                    ));
                }
            }
            materialized.add(new InventoryService.PreparedInventoryReward(
                reward.model(), reward.amount(), instances
            ));
        }
        return materialized;
    }

    private @Nullable ItemModel resolveRewardModel(@NotNull MailReward reward) {
        ItemModel model = itemService.findLoadedById(reward.itemId());
        return model != null ? model : itemService.loadItem(reward.itemId(), reward.category());
    }

    private void finishClaimFailure(
        @NotNull MailClaimKey claimKey,
        @Nullable AstPlayer astPlayer,
        @NotNull MailEntry mail,
        @NotNull Consumer<ReadAndReceiveResult> completion,
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
        completion.accept(new ReadAndReceiveResult(false, false));
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

    private record MailClaimKey(@NotNull UUID accountId, @NotNull String mailId) {
    }

    private record PendingMailClaim(
        @NotNull UUID accountId,
        @NotNull UUID clientRevision,
        @NotNull String mailId
    ) {
    }

    private record PendingMailDelete(
        @NotNull UUID accountId,
        @NotNull UUID clientRevision,
        @NotNull String mailId
    ) {
    }

    private record PendingReceivedNotification(
        @NotNull UUID userId,
        @NotNull UUID accountId,
        @NotNull String mailId
    ) {
    }

    private record PreparedReward(@NotNull ItemModel model, int amount) {
    }

    private record PreparedClaimRewards(@NotNull List<PreparedReward> rewards) {
        private PreparedClaimRewards {
            rewards = List.copyOf(rewards);
        }
    }
}
