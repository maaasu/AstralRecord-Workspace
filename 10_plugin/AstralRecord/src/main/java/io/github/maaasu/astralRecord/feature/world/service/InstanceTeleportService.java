package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportDestination;
import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportFilter;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 読込済みボス・ダンジョンの受付一覧と、通常ワールド上の受付への転送を扱います。 */
public final class InstanceTeleportService {
    private final Plugin plugin;
    private final WorldService worldService;
    private final MobService mobService;
    private final BossChallengeService bossService;
    private final DungeonService dungeonService;
    private final InventoryService inventoryService;
    private final Set<UUID> pendingPlayers = new HashSet<>();

    /**
     * 読込済みマスタと既存の挑戦・転送サービスを使用します。メインスレッド専用です。
     * @param plugin プラグイン本体
     * @param worldService ワールド参照と転送サービス
     * @param mobService 読込済みボスマスタ
     * @param bossService ボス挑戦状態
     * @param dungeonService 検証済みダンジョンマスタと挑戦状態
     * @param inventoryService 受付ワールドへ入るための所持品条件
     */
    public InstanceTeleportService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @NotNull MobService mobService,
            @NotNull BossChallengeService bossService,
            @NotNull DungeonService dungeonService,
            @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.mobService = mobService;
        this.bossService = bossService;
        this.dungeonService = dungeonService;
        this.inventoryService = inventoryService;
    }

    /**
     * 受付を持つ読込済みマスタを、表示レベル・表示名・種別・ID の順に返します。
     * API・filebase をクリック時に再読込せず、reload で公開済みのマスタを使います。
     * @param filter 表示種別。ALL は絞り込みなし
     * @return 変更不能な受付候補一覧
     */
    public @NotNull List<InstanceTeleportDestination> listDestinations(@NotNull InstanceTeleportFilter filter) {
        List<InstanceTeleportDestination> destinations = new ArrayList<>();
        if (filter.includes(InstanceTeleportFilter.BOSS)) {
            for (String id : mobService.getLoadedMobIdsByCategory(List.of(MobCategory.BOSS))) {
                MobTemplate template = mobService.findLoadedTemplate(id);
                if (template == null || template.challenge() == null) {
                    continue;
                }
                BossChallengeConfig challenge = template.challenge();
                BossLocation entry = challenge.entryLocation();
                destinations.add(new InstanceTeleportDestination(
                        new InstanceTeleportDestination.Key(InstanceTeleportFilter.BOSS, template.id()),
                        template.displayName(), template.level(), template.lore(), null, null,
                        challenge.partyMin(), challenge.partyMax(), challenge.timeLimitSeconds(),
                        challenge.deathLimit(), entry.worldId(), entry.x(), entry.y(), entry.z()
                ));
            }
        }
        if (filter.includes(InstanceTeleportFilter.DUNGEON)) {
            for (DungeonService.LoadedDefinition loaded : dungeonService.getLoadedDefinitions()) {
                DungeonDefinition definition = loaded.definition();
                DungeonDefinition.Entry entry = definition.entry();
                destinations.add(new InstanceTeleportDestination(
                        new InstanceTeleportDestination.Key(InstanceTeleportFilter.DUNGEON, definition.id()),
                        definition.displayName(), definition.recommendedLevel(), List.of(),
                        definition.generation().roomCount(), loaded.bossMob().displayName(),
                        definition.partySize().min(), definition.partySize().max(),
                        definition.challenge().timeLimitSeconds(), definition.challenge().deathLimit(),
                        loaded.entryWorldData().id(), entry.x(), entry.y(), entry.z()
                ));
            }
        }
        return destinations.stream().sorted(Comparator
                .comparingInt(InstanceTeleportDestination::level)
                .thenComparing(destination -> ColorCodeUtil.toPlainText(destination.displayName(), ""))
                .thenComparing(destination -> destination.key().kind())
                .thenComparing(destination -> destination.key().masterId())).toList();
    }

    /**
     * 選択した候補を最新マスタで再解決し、受付地点へ非同期転送します。
     * 視線方向は共通転送サービスが保持し、挑戦参加中・account 切替・二重転送を拒否します。
     * @param astPlayer 操作時のプレイヤー
     * @param key 選択した受付の内部キー
     * @return 転送処理を開始した場合は true。拒否理由はプレイヤーへ通知します
     */
    public boolean teleportToEntry(@NotNull AstPlayer astPlayer, @NotNull InstanceTeleportDestination.Key key) {
        Player player = astPlayer.getBukkit();
        PlayerMessageService messages = PlayerMessageService.getInstance();
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            messages.send(astPlayer, PlayerMsgId.P_5065);
            return false;
        }
        if (isInChallenge(player.getUniqueId())) {
            messages.send(astPlayer, PlayerMsgId.P_5792);
            return false;
        }
        if (pendingPlayers.contains(player.getUniqueId())) {
            messages.send(astPlayer, PlayerMsgId.P_5793);
            return false;
        }
        InstanceTeleportDestination destination = findDestination(key);
        WorldMasterData worldData = entryWorld(destination);
        if (destination == null || worldData == null) {
            messages.send(astPlayer, PlayerMsgId.P_5791);
            return false;
        }
        if (!hasRequiredItem(astPlayer, worldData)) {
            messages.send(astPlayer, PlayerMsgId.P_5777);
            return false;
        }
        World world = worldService.resolveOrLoadWorld(worldData);
        if (world == null) {
            messages.send(astPlayer, PlayerMsgId.P_5791);
            return false;
        }

        UUID playerId = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        Location target = new Location(world, destination.x(), destination.y(), destination.z());
        pendingPlayers.add(playerId);
        player.closeInventory();
        try {
            worldService.teleportPlayerAsync(player, target, null,
                    () -> isCurrentTransfer(player, accountId, destination, worldData))
                    .whenComplete((success, failure) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        pendingPlayers.remove(playerId);
                        AstPlayer current = AstPlayerCache.get(player);
                        if (!player.isOnline() || current == null
                                || !current.getAccount().getUuid().equals(accountId)) {
                            return;
                        }
                        if (failure != null) {
                            Logger.log(LogId.E_5790, failure, player.getName(), "teleport");
                        }
                        if (failure != null || !Boolean.TRUE.equals(success)) {
                            messages.send(current, hasRequiredItem(current, worldData)
                                    ? PlayerMsgId.P_5791 : PlayerMsgId.P_5777);
                        } else {
                            messages.send(current, PlayerMsgId.P_5790, destination.displayName());
                        }
                    }));
        } catch (RuntimeException failure) {
            pendingPlayers.remove(playerId);
            Logger.log(LogId.E_5790, failure, player.getName(), "teleport");
            messages.send(astPlayer, PlayerMsgId.P_5791);
            return false;
        }
        return true;
    }

    /**
     * 候補に通常ワールド上の有効な受付定義があるかを返します。ワールドの読込は行いません。
     * @param destination 表示候補
     * @return 受付定義と通常ワールドマスタを解決できる場合は true
     */
    public boolean isAvailable(@NotNull InstanceTeleportDestination destination) {
        return entryWorld(destination) != null;
    }

    /**
     * 種別と ID の組を最新の読込済みマスタで再解決します。
     * @param key 受付キー
     * @return 現在の候補。受付が削除された場合は null
     */
    private @Nullable InstanceTeleportDestination findDestination(@NotNull InstanceTeleportDestination.Key key) {
        return listDestinations(key.kind()).stream()
                .filter(destination -> destination.key().equals(key)).findFirst().orElse(null);
    }

    /**
     * 通常ワールド上の受付だけを解決します。戦闘インスタンスは対象外です。
     * @param destination 受付候補。未解決なら null
     * @return 転送可能な受付ワールドマスタ。欠損・内部戦場の場合は null
     */
    private @Nullable WorldMasterData entryWorld(@Nullable InstanceTeleportDestination destination) {
        if (destination == null || !destination.hasValidEntry()) {
            return null;
        }
        WorldMasterData data = worldService.getById(destination.entryWorldId());
        return data != null && data.worldType() != WorldType.BOSS_FIELD
                && data.worldType() != WorldType.DUNGEON ? data : null;
    }

    /**
     * 実転送直前にプレイヤー・account・挑戦状態・マスタ世代の一致を再検証します。
     * @param player 転送対象
     * @param accountId 操作時 account UUID
     * @param expected 操作時の受付定義
     * @param expectedWorld 操作時のワールド定義
     * @return 操作時の条件が引き続き有効なら true
     */
    private boolean isCurrentTransfer(
            @NotNull Player player, @NotNull UUID accountId,
            @NotNull InstanceTeleportDestination expected, @NotNull WorldMasterData expectedWorld
    ) {
        AstPlayer current = AstPlayerCache.get(player);
        InstanceTeleportDestination latest = findDestination(expected.key());
        return player.isOnline() && current != null && current.getAccount().getUuid().equals(accountId)
                && AccountModeGuard.isGameplayPlayer(current) && !isInChallenge(player.getUniqueId())
                && hasRequiredItem(current, expectedWorld)
                && expected.equals(latest) && expectedWorld.equals(entryWorld(latest));
    }

    /**
     * 通常のオーバーワールド転送と同じ入場用アイテム所持条件を確認します。
     * @param player 現在 account のプレイヤー
     * @param world 入場先ワールドマスタ
     * @return 条件未指定または必要アイテムを1個以上所持している場合は true
     */
    private boolean hasRequiredItem(@NotNull AstPlayer player, @NotNull WorldMasterData world) {
        String requiredItemId = world.requiredItemId();
        return requiredItemId == null || (!requiredItemId.isBlank()
                && inventoryService.getCurrencyAmount(player.getAccount().getUuid(), requiredItemId) >= 1L);
    }

    /**
     * ボスまたはダンジョンで移動を制限する参加状態かを返します。
     * @param playerId プレイヤー UUID
     * @return 既存挑戦サービスが参加中として扱う場合は true
     */
    private boolean isInChallenge(@NotNull UUID playerId) {
        return bossService.isPlayerInActiveChallenge(playerId)
                || dungeonService.isPlayerInActiveSession(playerId);
    }
}
