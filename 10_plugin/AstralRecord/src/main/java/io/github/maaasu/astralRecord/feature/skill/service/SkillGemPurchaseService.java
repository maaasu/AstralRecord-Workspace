package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import io.github.maaasu.astralRecord.feature.shop.service.ShopSpecialPurchaseHandler;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** スキルジェム購入を、同一トランザクション素材を使う習得またはレベルアップへ接続します。 */
public final class SkillGemPurchaseService implements ShopSpecialPurchaseHandler {
    private final LearnedSkillService learnedSkillService;
    private final SkillService skillService;
    private final InventoryService inventoryService;
    private final PassiveSkillService passiveSkillService;
    private final ConcurrentMap<UUID, PendingPurchase> pendingByAccount = new ConcurrentHashMap<>();
    private BiConsumer<AstPlayer, String> skillLearnedListener = (player, skillId) -> { };
    private BiConsumer<AstPlayer, String> skillEnhancedListener = (player, skillId) -> { };

    /**
     * 購入ジェムを習得済みスキルAPIへ反映するサービスを生成します。
     *
     * @param learnedSkillService 習得・レベルアップmutation担当
     * @param skillService スキル定義の解決元
     * @param inventoryService 購入ジェム消費後の表示同期担当
     * @param passiveSkillService 習得状態変更後のパッシブ再評価担当
     */
    public SkillGemPurchaseService(
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull SkillService skillService,
        @NotNull InventoryService inventoryService,
        @NotNull PassiveSkillService passiveSkillService
    ) {
        this.learnedSkillService = learnedSkillService;
        this.skillService = skillService;
        this.inventoryService = inventoryService;
        this.passiveSkillService = passiveSkillService;
    }

    /**
     * 購入による新規習得の通知先を設定します。
     *
     * @param listener 習得したプレイヤーとスキル ID を受け取る通知先
     */
    public void setSkillLearnedListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.skillLearnedListener = listener;
    }

    /**
     * 購入によるレベルアップの通知先を設定します。
     *
     * @param listener 強化したプレイヤーとスキル ID を受け取る通知先
     */
    public void setSkillEnhancedListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.skillEnhancedListener = listener;
    }

    @Override
    public @NotNull ShopSpecialPurchaseState preview(@NotNull AstPlayer player, @NotNull ItemModel item) {
        if (item.getSkillGem() == null) {
            return ShopSpecialPurchaseState.standard();
        }
        UUID accountId = player.getAccount().getUuid();
        if (pendingByAccount.containsKey(accountId)
            || learnedSkillService.hasMutationInProgress(accountId)) {
            return ShopSpecialPurchaseState.processing();
        }
        if (!learnedSkillService.hasLoadedSkills(accountId)) {
            return ShopSpecialPurchaseState.unavailable();
        }
        String skillId = item.getSkillGem().getSkillId();
        SkillDefinition definition = skillService.registry().getDefinition(skillId);
        if (definition == null) {
            return ShopSpecialPurchaseState.unavailable();
        }
        LearnedSkillInstance learned = findCanonicalInstance(accountId, skillId);
        if (learned == null) {
            return ShopSpecialPurchaseState.learn(definition.getMaxLevel());
        }
        if (learned.getLevel() >= Math.max(1, definition.getMaxLevel())) {
            return ShopSpecialPurchaseState.maxLevel(definition.getMaxLevel());
        }
        return ShopSpecialPurchaseState.levelUp(learned.getLevel(), definition.getMaxLevel());
    }

    @Override
    public boolean reserve(@NotNull AstPlayer player, @NotNull ItemModel item) {
        ShopSpecialPurchaseState state = preview(player, item);
        if (item.getSkillGem() == null || !state.canPurchase() || !state.special()) {
            return false;
        }
        UUID accountId = player.getAccount().getUuid();
        String skillId = item.getSkillGem().getSkillId();
        LearnedSkillInstance learned = findCanonicalInstance(accountId, skillId);
        PendingPurchase pending = new PendingPurchase(
            item.getId(),
            skillId,
            state.action(),
            learned == null ? null : learned.getLearnedSkillId(),
            state.nextLevel()
        );
        return pendingByAccount.putIfAbsent(accountId, pending) == null;
    }

    @Override
    public void completePurchase(
        @NotNull AstPlayer player,
        @NotNull ItemModel item,
        @NotNull UUID inventoryEntryId,
        @NotNull BooleanSupplier compensatePurchase,
        @NotNull Runnable onPurchasePersisted,
        @NotNull Runnable onStateChanged
    ) {
        UUID accountId = player.getAccount().getUuid();
        PendingPurchase pending = pendingByAccount.get(accountId);
        if (pending == null || !pending.itemId().equalsIgnoreCase(item.getId())) {
            learnedSkillService.compensateRejectedPurchaseAsync(
                accountId,
                compensatePurchase,
                onStateChanged,
                error -> onStateChanged.run()
            );
            return;
        }
        boolean scheduled;
        if (pending.action() == ShopSpecialPurchaseState.Action.SKILL_LEARN) {
            scheduled = learnedSkillService.learnFromPurchaseAsync(
                accountId,
                pending.skillId(),
                inventoryEntryId,
                accountId,
                compensatePurchase,
                learned -> completeSuccess(
                    player,
                    pending,
                    learned,
                    onPurchasePersisted,
                    onStateChanged
                ),
                error -> completeFailure(player, pending, onStateChanged)
            );
        } else if (pending.action() == ShopSpecialPurchaseState.Action.SKILL_LEVEL_UP
            && pending.learnedSkillId() != null) {
            scheduled = learnedSkillService.levelUpFromPurchaseAsync(
                accountId,
                pending.learnedSkillId(),
                pending.nextLevel(),
                inventoryEntryId,
                accountId,
                compensatePurchase,
                learned -> completeSuccess(
                    player,
                    pending,
                    learned,
                    onPurchasePersisted,
                    onStateChanged
                ),
                error -> completeFailure(player, pending, onStateChanged)
            );
        } else {
            scheduled = false;
        }
        if (!scheduled) {
            learnedSkillService.compensateRejectedPurchaseAsync(
                accountId,
                compensatePurchase,
                () -> completeFailure(player, pending, onStateChanged),
                error -> completeFailure(player, pending, onStateChanged)
            );
        }
    }

    @Override
    public void cancel(@NotNull AstPlayer player, @NotNull ItemModel item) {
        UUID accountId = player.getAccount().getUuid();
        pendingByAccount.computeIfPresent(accountId, (ignored, pending) ->
            pending.itemId().equalsIgnoreCase(item.getId()) ? null : pending
        );
    }

    private void completeSuccess(
        @NotNull AstPlayer original,
        @NotNull PendingPurchase pending,
        @NotNull LearnedSkillInstance learned,
        @NotNull Runnable onPurchasePersisted,
        @NotNull Runnable onStateChanged
    ) {
        if (!pendingByAccount.remove(original.getAccount().getUuid(), pending)) {
            return;
        }
        if (pending.action() == ShopSpecialPurchaseState.Action.SKILL_LEARN) {
            skillLearnedListener.accept(original, learned.getSkillId());
        } else {
            skillEnhancedListener.accept(original, learned.getSkillId());
        }
        AstPlayer current = currentPlayer(original);
        if (current != null) {
            inventoryService.applyInventoriesToGui(current);
            passiveSkillService.markDirty(current);
            SkillDefinition definition = skillService.registry().getDefinition(learned.getSkillId());
            String name = SkillPresentationUtil.plainName(definition, learned.getSkillId());
            if (pending.action() == ShopSpecialPurchaseState.Action.SKILL_LEARN) {
                GuiSound.SKILL_LEARN.play(current.getBukkit());
                PlayerMessageService.getInstance().send(current, PlayerMsgId.P_5856, name);
            } else {
                GuiSound.UPGRADE.play(current.getBukkit());
                PlayerMessageService.getInstance().send(current, PlayerMsgId.P_5874, name, learned.getLevel());
            }
        }
        onPurchasePersisted.run();
        onStateChanged.run();
    }

    private void completeFailure(
        @NotNull AstPlayer original,
        @NotNull PendingPurchase pending,
        @NotNull Runnable onStateChanged
    ) {
        if (!pendingByAccount.remove(original.getAccount().getUuid(), pending)) {
            return;
        }
        AstPlayer current = currentPlayer(original);
        if (current != null) {
            inventoryService.applyInventoriesToGui(current);
            GuiSound.DENY.play(current.getBukkit());
            PlayerMessageService.getInstance().send(
                current,
                pending.action() == ShopSpecialPurchaseState.Action.SKILL_LEARN
                    ? PlayerMsgId.P_5857
                    : PlayerMsgId.P_5875
            );
        }
        onStateChanged.run();
    }

    private @Nullable AstPlayer currentPlayer(@NotNull AstPlayer original) {
        AstPlayer current = AstPlayerCache.get(original.getBukkit());
        if (current == null || !current.getAccount().getUuid().equals(original.getAccount().getUuid())) {
            return null;
        }
        return current;
    }

    private @Nullable LearnedSkillInstance findCanonicalInstance(@NotNull UUID accountId, @NotNull String skillId) {
        return learnedSkillService.getLearnedSkills(accountId).stream()
            .filter(learned -> learned.getSkillId().equalsIgnoreCase(skillId))
            .findFirst()
            .orElse(null);
    }

    private record PendingPurchase(
        @NotNull String itemId,
        @NotNull String skillId,
        @NotNull ShopSpecialPurchaseState.Action action,
        @Nullable UUID learnedSkillId,
        int nextLevel
    ) {
    }
}
