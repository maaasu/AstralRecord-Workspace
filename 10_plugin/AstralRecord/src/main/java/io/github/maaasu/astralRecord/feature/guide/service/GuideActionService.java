package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideAction;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.mail.event.MailGuiEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ガイド詳細画面から実行する案内アクションを処理します。
 */
public final class GuideActionService {
    private static final long NPC_HIGHLIGHT_DURATION_TICKS = 200L;

    private final AstralRecord plugin;
    private final MobService mobService;
    private final NpcPlacementService npcPlacementService;
    private final PlayerMessageService playerMessageService;
    private final Map<UUID, HighlightSession> highlights = new HashMap<>();

    /**
     * ガイド案内アクションサービスを生成します。
     *
     * @param plugin スケジューラを提供するプラグイン
     * @param mobService NPC 実体・マスター参照サービス
     * @param npcPlacementService NPC 配置参照サービス
     * @param playerMessageService プレイヤー向け通知サービス
     */
    public GuideActionService(
        @NotNull AstralRecord plugin,
        @NotNull MobService mobService,
        @NotNull NpcPlacementService npcPlacementService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.npcPlacementService = npcPlacementService;
        this.playerMessageService = playerMessageService;
    }

    /**
     * ガイドのクリックアクションを実行します。
     *
     * @param player 操作したプレイヤー。メインスレッド上で呼び出す
     * @param action 実行するアクション
     * @return アクションを受け付けた場合は true
     */
    public boolean execute(@NotNull Player player, @NotNull GuideAction action) {
        return switch (action.type()) {
            case NAVIGATE_NPC -> navigateNpc(player, action.npcId());
            case OPEN_MENU -> openMenu(player, action.menuId());
        };
    }

    /**
     * 先の案内表示を回収し、同じワールドの最寄り NPC の座標と個別の発光表示を案内します。
     *
     * @param player 操作したプレイヤー。メインスレッド上で呼び出す
     * @param npcId 案内対象の NPC 定義 ID。null の場合は汎用エラーを通知する
     * @return 案内先の座標を解決できた場合は true
     */
    private boolean navigateNpc(@NotNull Player player, @Nullable String npcId) {
        clearHighlight(player.getUniqueId());
        if (npcId == null) {
            playerMessageService.send(player, PlayerMsgId.P_5184);
            return false;
        }
        NavigationTarget target = findNearestLiveNpc(player, npcId);
        if (target == null) {
            target = findNearestPlacement(player, npcId);
        }
        if (target == null) {
            playerMessageService.send(player, PlayerMsgId.P_5183);
            return false;
        }

        Location location = target.location();
        String displayName = target.displayName();
        playerMessageService.send(
            player,
            PlayerMsgId.P_5182,
            displayName,
            location.getBlockX(),
            location.getBlockY(),
            location.getBlockZ()
        );
        if (target.instance() != null) {
            highlight(player, target.instance());
        }
        return true;
    }

    private boolean openMenu(@NotNull Player player, @Nullable String menuId) {
        if (menuId == null) {
            playerMessageService.send(player, PlayerMsgId.P_5184);
            return false;
        }
        if (menuId.equalsIgnoreCase("mail")) {
            MailGuiEventHandler mailGuiEventHandler = plugin.getMailGuiEventHandler();
            if (mailGuiEventHandler == null) {
                playerMessageService.send(player, PlayerMsgId.P_5184);
                return false;
            }
            mailGuiEventHandler.open(player);
            return true;
        }
        if (menuId.equalsIgnoreCase("skill_bind")) {
            SkillBindGuiEventHandler skillBindGuiEventHandler = plugin.getSkillBindGuiEventHandler();
            if (skillBindGuiEventHandler == null) {
                playerMessageService.send(player, PlayerMsgId.P_5184);
                return false;
            }
            return skillBindGuiEventHandler.open(player);
        }
        playerMessageService.send(player, PlayerMsgId.P_5184);
        return false;
    }

    /**
     * 同じワールドの最寄り NPC と、表示の複製に使うインスタンスを解決します。
     *
     * @param player 距離の基準となるプレイヤー
     * @param npcId 対象 NPC の定義 ID
     * @return 最寄りの案内先。該当個体がない場合は null
     */
    private @Nullable NavigationTarget findNearestLiveNpc(@NotNull Player player, @NotNull String npcId) {
        MobInstance nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (MobInstance instance : mobService.getInstances()) {
            if (instance.template().category() != MobCategory.NPC
                || !instance.template().id().equalsIgnoreCase(npcId)) {
                continue;
            }
            Location location = instance.currentLocation();
            if (location.getWorld() != player.getWorld()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < nearestDistance) {
                nearest = instance;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return null;
        }
        return new NavigationTarget(
            nearest.currentLocation(),
            displayName(nearest.template()),
            nearest
        );
    }

    private @Nullable NavigationTarget findNearestPlacement(@NotNull Player player, @NotNull String npcId) {
        NpcPlacement nearest = null;
        Location nearestLocation = null;
        double nearestDistance = Double.MAX_VALUE;
        for (NpcPlacement placement : npcPlacementService.getPlacements()) {
            if (!placement.npcId().equalsIgnoreCase(npcId)
                || !placement.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            Location location = placement.toLocation();
            if (location == null) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < nearestDistance) {
                nearest = placement;
                nearestLocation = location;
                nearestDistance = distance;
            }
        }
        if (nearest == null || nearestLocation == null) {
            return null;
        }
        MobTemplate template = mobService.findLoadedTemplate(nearest.npcId());
        return new NavigationTarget(
            nearestLocation,
            template == null ? "案内対象NPC" : displayName(template),
            null
        );
    }

    /**
     * 操作したプレイヤーだけへ発光する NPC の複製を表示し、満了時の回収を予約します。
     *
     * @param player 表示先プレイヤー。メインスレッド上で呼び出す
     * @param instance 複製元の NPC。元の発光状態は変更しない
     */
    private void highlight(@NotNull Player player, @NotNull MobInstance instance) {
        Runnable removeDisplay = mobService.showNpcHighlight(player, instance);
        if (removeDisplay == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        HighlightSession session = new HighlightSession(removeDisplay);
        highlights.put(playerId, session);
        try {
            session.expiryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (highlights.get(playerId) == session) {
                    clearHighlight(playerId);
                }
            }, NPC_HIGHLIGHT_DURATION_TICKS);
        } catch (RuntimeException failure) {
            clearHighlight(playerId);
            throw failure;
        }
    }

    /**
     * プレイヤーの案内表示を破棄し、満了タスクも取り消します。重複呼び出しは無操作です。
     *
     * @param playerId 回収するプレイヤー UUID。メインスレッド上で呼び出す
     */
    public void clearHighlight(@NotNull UUID playerId) {
        HighlightSession session = highlights.remove(playerId);
        if (session == null) {
            return;
        }
        if (session.expiryTask != null) {
            session.expiryTask.cancel();
        }
        session.removeDisplay.run();
    }

    /**
     * プラグイン停止時に全プレイヤーの案内表示と満了タスクを回収します。
     * メインスレッド上で、Mob の表示サービス停止前に呼び出します。
     */
    public void stop() {
        for (UUID playerId : List.copyOf(highlights.keySet())) {
            clearHighlight(playerId);
        }
    }

    private @NotNull String displayName(@NotNull MobTemplate template) {
        return ColorCodeUtil.toPlainText(template.displayName(), "案内対象NPC");
    }

    private record NavigationTarget(
        @NotNull Location location,
        @NotNull String displayName,
        @Nullable MobInstance instance
    ) {
    }

    private static final class HighlightSession {
        private final Runnable removeDisplay;
        private @Nullable BukkitTask expiryTask;

        /**
         * 一つの表示専用の回収処理を保持します。
         *
         * @param removeDisplay 複製の破棄処理。重複回収や通信失敗でも例外を送出しない
         */
        private HighlightSession(@NotNull Runnable removeDisplay) {
            this.removeDisplay = removeDisplay;
        }
    }
}
