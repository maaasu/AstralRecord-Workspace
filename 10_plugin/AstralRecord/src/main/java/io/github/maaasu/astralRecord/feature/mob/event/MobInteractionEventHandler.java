package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.feature.item.service.EquipmentEnhancementService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRepairService;
import io.github.maaasu.astralRecord.feature.currency.event.CurrencyExchangeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionActionConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.quest.event.QuestGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.event.SkillForgetGuiEventHandler;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * NPC クリックアクションを実行するイベントハンドラです。
 */
public final class MobInteractionEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final MobService mobService;
    private final ShopGuiEventHandler shopGuiEventHandler;
    private final MenuView menuView;
    private final PlayerClassService playerClassService;
    private final StorageService storageService;
    private final EquipmentEnhancementService equipmentEnhancementService;
    private final EquipmentRepairService equipmentRepairService;
    private final QuestGuiEventHandler questGuiEventHandler;
    private final CurrencyExchangeGuiEventHandler currencyExchangeGuiEventHandler;
    private final LoginBonusService loginBonusService;
    private final SkillForgetGuiEventHandler skillForgetGuiEventHandler;

    /**
     * ハンドラを生成します。
     *
     * @param mobService          Mob 管理サービス
     * @param shopGuiEventHandler ショップ GUI ハンドラ
     * @param menuView            メニュー GUI ビュー
     * @param playerClassService  職業表示用サービス
     * @param storageService      ストレージ GUI サービス
     * @param equipmentEnhancementService 装備強化 GUI サービス
     * @param equipmentRepairService 装備修理 GUI サービス
     * @param questGuiEventHandler クエストボード GUI ハンドラ
     * @param currencyExchangeGuiEventHandler ゴールド両替 GUI ハンドラ
     * @param loginBonusService ログインボーナス GUI サービス
     * @param skillForgetGuiEventHandler スキル忘却 GUI ハンドラ
     */
    public MobInteractionEventHandler(
            @NotNull MobService mobService,
            @NotNull ShopGuiEventHandler shopGuiEventHandler,
            @NotNull MenuView menuView,
            @NotNull PlayerClassService playerClassService,
            @NotNull StorageService storageService,
            @NotNull EquipmentEnhancementService equipmentEnhancementService,
            @NotNull EquipmentRepairService equipmentRepairService,
            @NotNull QuestGuiEventHandler questGuiEventHandler,
            @NotNull CurrencyExchangeGuiEventHandler currencyExchangeGuiEventHandler,
            @NotNull LoginBonusService loginBonusService,
            @NotNull SkillForgetGuiEventHandler skillForgetGuiEventHandler) {
        this.mobService = mobService;
        this.shopGuiEventHandler = shopGuiEventHandler;
        this.menuView = menuView;
        this.playerClassService = playerClassService;
        this.storageService = storageService;
        this.equipmentEnhancementService = equipmentEnhancementService;
        this.equipmentRepairService = equipmentRepairService;
        this.questGuiEventHandler = questGuiEventHandler;
        this.currencyExchangeGuiEventHandler = currencyExchangeGuiEventHandler;
        this.loginBonusService = loginBonusService;
        this.skillForgetGuiEventHandler = skillForgetGuiEventHandler;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if ((context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK)
            || !snapshot.isMainHandInput()) {
            return List.of();
        }

        MobService.MobInteractionHit hit = resolveHit(snapshot);
        if (hit == null || !snapshot.isVisible(hit.hitDistance())) {
            return List.of();
        }
        MobInstance instance = hit.instance();
        List<MobInteractionActionConfig> actions = context.family() == InputFamily.LEFT_CLICK
            ? instance.template().interactions().leftClick()
            : instance.template().interactions().rightClick();
        return List.of(new PlayerInputCandidate(
            "npc-interaction",
            InteractionTier.WORLD_INTERACTION,
            hit.hitDistance(),
            InteractionCandidateOrder.NPC,
            instance.instanceId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
                PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
                MobService.MobInteractionHit current = resolveHit(currentSnapshot);
                return current != null
                    && current.instance().instanceId().equals(instance.instanceId())
                    && currentSnapshot.isVisible(current.hitDistance());
            },
            () -> {
                if (AccountModeGuard.isGameplayPlayer(snapshot.player())) {
                    execute(snapshot.player(), instance, actions);
                }
            }
        ));
    }

    private MobService.MobInteractionHit resolveHit(PlayerInteractionSnapshot snapshot) {
        if (snapshot.targetEntity() != null) {
            MobInstance direct = mobService.getNpcInstanceByEntity(snapshot.targetEntity().getUniqueId());
            Double hitDistance = snapshot.hitDistance(
                snapshot.targetEntity(),
                MobService.NPC_INTERACTION_RAY_SIZE
            );
            if (direct != null && hitDistance != null
                && hitDistance <= MobService.NPC_INTERACTION_DISTANCE) {
                return new MobService.MobInteractionHit(direct, hitDistance);
            }
        }
        return mobService.findTargetedNpcHit(
            snapshot.player(),
            MobService.NPC_INTERACTION_DISTANCE,
            MobService.NPC_INTERACTION_RAY_SIZE
        );
    }

    private void execute(@NotNull Player player, @NotNull MobInstance instance, @NotNull List<MobInteractionActionConfig> actions) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return;
        }
        if (actions.isEmpty()) {
            GuiSound.DENY.play(player);
            return;
        }
        for (MobInteractionActionConfig action : actions) {
            execute(player, instance, action);
        }
    }

    private void execute(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        switch (action.id().toLowerCase(Locale.ROOT)) {
            case "message" -> sendMessage(player, action);
            case "gui" -> openGui(player, instance, action);
            case "command" -> executeCommand(player, action);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void sendMessage(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String message = action.params().get("message");
        if (message == null || message.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        PlayerMessageService.getInstance().sendComponent(
            player,
            LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(message))
        );
    }

    private void openGui(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        String rawType = action.params().get("type");
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "SHOP" -> openShop(player, action);
            case "QUEST", "QUEST_BOARD" -> openQuestBoard(player, instance, action);
            case "SELL" -> {
                menuView.openSell(player, List.of(), 0);
                GuiSound.OPEN.play(player);
            }
            case "CLASS" -> openClass(player);
            case "STORAGE" -> openStorage(player);
            case "EQUIPMENT_ENHANCE", "ENHANCE" -> openEquipmentEnhance(player);
            case "EQUIPMENT_REPAIR", "REPAIR" -> openEquipmentRepair(player);
            case "CURRENCY_EXCHANGE", "EXCHANGE" -> currencyExchangeGuiEventHandler.open(player);
            case "LOGIN_BONUS" -> openLoginBonus(player);
            case "SKILL_FORGET", "FORGET_SKILL" -> openSkillForget(player);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void openShop(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String shopId = action.params().get("shopId");
        if (shopId == null || shopId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        shopGuiEventHandler.openFromNpc(player, shopId);
    }

    private void openQuestBoard(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        String boardId = action.params().get("boardId");
        if (boardId == null || boardId.isBlank()) {
            boardId = action.params().get("questBoardId");
        }
        if (boardId == null || boardId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        questGuiEventHandler.openBoard(player, boardId, instance.template().id());
    }

    private void openClass(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        menuView.openClass(player, astPlayer, playerClassService.getClassViewEntries(astPlayer));
        GuiSound.OPEN.play(player);
    }

    private void openStorage(@NotNull Player player) {
        storageService.open(player);
        GuiSound.OPEN.play(player);
    }

    private void openEquipmentEnhance(@NotNull Player player) {
        equipmentEnhancementService.open(player);
        GuiSound.OPEN.play(player);
    }

    private void openEquipmentRepair(@NotNull Player player) {
        equipmentRepairService.open(player);
        GuiSound.OPEN.play(player);
    }

    /**
     * NPC からログインボーナス GUI を開きます。
     *
     * @param player 対象プレイヤー
     */
    private void openLoginBonus(@NotNull Player player) {
        loginBonusService.openAfterDataLoaded(player);
        GuiSound.OPEN.play(player);
    }

    /**
     * NPC からスキル忘却 GUI を開きます。
     *
     * @param player 対象プレイヤー
     */
    private void openSkillForget(@NotNull Player player) {
        skillForgetGuiEventHandler.open(player);
    }

    private void executeCommand(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String command = action.params().get("command");
        if (command == null || command.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty() || !player.performCommand(normalized)) {
            GuiSound.DENY.play(player);
        }
    }
}
