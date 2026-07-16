package io.github.maaasu.astralRecord.feature.mail.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * メール一覧・既読化・報酬受け取りを扱うサービスです。
 */
public final class MailService {
    private static final String REWARD_SOURCE = "mail";

    private final MailRepository mailRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;

    public MailService(
        @NotNull MailRepository mailRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService
    ) {
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
     * 表示可能な未読メール件数を返します。
     *
     * @param userId 対象ユーザー ID
     * @return 未読メール件数
     */
    public int countUnread(@NotNull UUID userId) {
        return list(userId, MailFilter.UNREAD).size();
    }

    /**
     * メールを既読化し、未読メールであれば報酬を付与します。
     *
     * @param astPlayer 対象プレイヤー
     * @param mail      メール
     * @return 既読化できた場合 true
     */
    public boolean readAndReceive(@NotNull AstPlayer astPlayer, @NotNull MailEntry mail) {
        MailEntry updated = mailRepository.markRead(astPlayer.getBukkit().getUniqueId(), mail.id());
        if (updated == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5622);
            return false;
        }
        if (!mail.read() && mail.receiveOnRead()) {
            int granted = grantRewards(astPlayer, mail.rewards());
            if (granted > 0) {
                PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5620, mail.title(), granted);
            } else if (!mail.rewards().isEmpty()) {
                PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5623, mail.title());
            }
        }
        return true;
    }

    /**
     * プレイヤー単位でメールを削除状態にします。
     *
     * @param astPlayer 対象プレイヤー
     * @param mailId    メール ID
     * @return 削除できた場合 true
     */
    public boolean delete(@NotNull AstPlayer astPlayer, @NotNull String mailId) {
        boolean deleted = mailRepository.delete(astPlayer.getBukkit().getUniqueId(), mailId);
        if (deleted) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5621);
        } else {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5622);
        }
        return deleted;
    }

    private int grantRewards(@NotNull AstPlayer astPlayer, @NotNull List<MailReward> rewards) {
        int totalGranted = 0;
        for (MailReward reward : rewards) {
            if (reward.amount() <= 0) {
                continue;
            }
            ItemModel model = itemService.findLoadedById(reward.itemId());
            if (model == null) {
                model = itemService.loadItem(reward.itemId(), reward.category());
            }
            if (model == null) {
                continue;
            }
            totalGranted += inventoryService.addItemToNormalInventory(astPlayer, model, reward.amount(), REWARD_SOURCE);
        }
        return totalGranted;
    }
}
