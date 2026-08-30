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
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ガイド詳細画面から実行する案内アクションを処理します。
 */
public final class GuideActionService {
    private static final long NPC_HIGHLIGHT_DURATION_TICKS = 200L;

    private final AstralRecord plugin;
    private final MobService mobService;
    private final NpcPlacementService npcPlacementService;
    private final PlayerMessageService playerMessageService;
    private final Map<UUID, UUID> highlightTokens = new ConcurrentHashMap<>();

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

    private boolean navigateNpc(@NotNull Player player, @Nullable String npcId) {
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
        if (target.entityId() != null) {
            highlight(target.entityId());
        }
        return true;
    }

    private boolean openMenu(@NotNull Player player, @Nullable String menuId) {
        if (menuId == null || !menuId.equalsIgnoreCase("mail")) {
            playerMessageService.send(player, PlayerMsgId.P_5184);
            return false;
        }
        MailGuiEventHandler mailGuiEventHandler = plugin.getMailGuiEventHandler();
        if (mailGuiEventHandler == null) {
            playerMessageService.send(player, PlayerMsgId.P_5184);
            return false;
        }
        mailGuiEventHandler.open(player);
        return true;
    }

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
            nearest.bukkitEntityId()
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

    private void highlight(@NotNull UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        if (entity == null || !entity.isValid()) {
            return;
        }
        UUID token = UUID.randomUUID();
        highlightTokens.put(entityId, token);
        MobInstance instance = mobService.getInstanceByEntity(entityId);
        if (instance != null) {
            mobService.setGlowing(instance, true);
        } else {
            entity.setGlowing(true);
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!token.equals(highlightTokens.get(entityId))) {
                return;
            }
            highlightTokens.remove(entityId, token);
            if (instance != null) {
                mobService.setGlowing(instance, false);
                return;
            }
            Entity current = Bukkit.getEntity(entityId);
            if (current != null && current.isValid()) {
                current.setGlowing(false);
            }
        }, NPC_HIGHLIGHT_DURATION_TICKS);
    }

    private @NotNull String displayName(@NotNull MobTemplate template) {
        return ColorCodeUtil.toPlainText(template.displayName(), "案内対象NPC");
    }

    private record NavigationTarget(
        @NotNull Location location,
        @NotNull String displayName,
        @Nullable UUID entityId
    ) {
    }
}
