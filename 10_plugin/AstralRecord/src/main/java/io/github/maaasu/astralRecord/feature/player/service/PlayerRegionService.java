package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.title.Title;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * オンライン中のプレイヤー地域を更新し、地域切り替えタイトルを制御します。
 */
public final class PlayerRegionService {

    private static final long WORLD_CHANGE_TITLE_DELAY_TICKS = 45L;
    private static final Title.Times REGION_CHANGE_TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(150L), Duration.ofMillis(1800L), Duration.ofMillis(250L));

    private final Plugin plugin;
    private final WorldService worldService;
    private final BiConsumer<Player, String> titleDisplayer;
    private final Map<UUID, BukkitTask> pendingWorldChangeTitles = new HashMap<>();

    /**
     * サービスを構築します。
     *
     * @param plugin プラグイン本体
     * @param worldService ワールドサービス
     */
    public PlayerRegionService(@NotNull Plugin plugin, @NotNull WorldService worldService) {
        this(plugin, worldService, PlayerRegionService::showRegionChangeTitle);
    }

    /**
     * テスト用のタイトル表示処理を受け取ってサービスを構築します。
     *
     * @param plugin プラグイン本体
     * @param worldService ワールドサービス
     * @param titleDisplayer 地域名をタイトル表示する処理
     */
    PlayerRegionService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @NotNull BiConsumer<Player, String> titleDisplayer
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.titleDisplayer = titleDisplayer;
    }

    /**
     * ログイン直後の地域を現在ワールドの種別から初期化します。
     * 初期化では地域切り替えタイトルを表示しません。
     *
     * @param astPlayer 初期化対象プレイヤー
     */
    public void initializeRegion(@NotNull AstPlayer astPlayer) {
        cancelPendingTitle(astPlayer.getBukkit().getUniqueId());
        astPlayer.setCurrentRegion(resolveDefaultRegion(astPlayer.getBukkit().getWorld()));
    }

    /**
     * ワールド移動後の地域をワールド種別の既定値へ更新します。
     * 既存のワールド移動タイトルと競合しないよう、地域タイトルは遅延して表示します。
     * 遅延中にスポナー地域へ切り替わった場合は、その最終地域だけを表示します。
     *
     * @param astPlayer ワールドを移動したプレイヤー
     */
    public void handleWorldChange(@NotNull AstPlayer astPlayer) {
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        cancelPendingTitle(playerId);
        String region = resolveDefaultRegion(astPlayer.getBukkit().getWorld());
        if (!setCurrentRegion(astPlayer, region)) {
            return;
        }

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingWorldChangeTitles.remove(playerId);
            AstPlayer current = AstPlayerCache.get(playerId);
            if (current == null || !current.getBukkit().isOnline() || current.getCurrentRegion() == null) {
                return;
            }
            titleDisplayer.accept(current.getBukkit(), current.getCurrentRegion());
        }, WORLD_CHANGE_TITLE_DELAY_TICKS);
        pendingWorldChangeTitles.put(playerId, task);
    }

    /**
     * Mob スポナーの地域をプレイヤーへ反映します。
     * オーバーワールド以外、地域未設定、現在地域と同一の場合は何も行いません。
     *
     * @param astPlayer 対象プレイヤー
     * @param region スポナーに定義された地域名
     * @return 地域が実際に切り替わった場合は {@code true}
     */
    public boolean updateRegionFromSpawner(@NotNull AstPlayer astPlayer, @Nullable String region) {
        if (region == null || region.isBlank() || resolveWorldType(astPlayer.getBukkit().getWorld()) != WorldType.OVERWORLD) {
            return false;
        }
        String normalized = region.trim();
        if (!setCurrentRegion(astPlayer, normalized)) {
            return false;
        }
        if (!pendingWorldChangeTitles.containsKey(astPlayer.getBukkit().getUniqueId())) {
            titleDisplayer.accept(astPlayer.getBukkit(), normalized);
        }
        return true;
    }

    /**
     * 地域付きスポナーの範囲外にいるオーバーワールドプレイヤーを既定地域へ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 地域が実際に切り替わった場合は {@code true}
     */
    public boolean resetOverworldRegion(@NotNull AstPlayer astPlayer) {
        return updateRegionFromSpawner(astPlayer, WorldType.OVERWORLD.getRegionDisplayName());
    }

    /**
     * プレイヤーの保留中タイトルタスクを破棄します。
     *
     * @param playerId 対象プレイヤー UUID
     */
    public void clearPlayer(@NotNull UUID playerId) {
        cancelPendingTitle(playerId);
    }

    /**
     * 現在ワールドがスポナーによる地域切り替え対象か判定します。
     *
     * @param world 判定対象ワールド
     * @return オーバーワールドの場合は {@code true}
     */
    public boolean isSpawnerRegionWorld(@NotNull World world) {
        return resolveWorldType(world) == WorldType.OVERWORLD;
    }

    /**
     * 現在ワールドの種別から地域既定値を解決します。
     *
     * @param world 対象ワールド
     * @return 地域既定値。管理対象外ワールドでは Bukkit ワールド名
     */
    @NotNull
    private String resolveDefaultRegion(@NotNull World world) {
        WorldType worldType = resolveWorldType(world);
        return worldType == null ? world.getName() : worldType.getRegionDisplayName();
    }

    /**
     * ワールドサービスから対象ワールドの種別を解決します。
     *
     * @param world 対象ワールド
     * @return ワールド種別。管理対象外の場合は {@code null}
     */
    @Nullable
    private WorldType resolveWorldType(@NotNull World world) {
        return worldService.resolveWorldType(world);
    }

    /**
     * 現在地域が異なる場合だけプレイヤーセッションを更新します。
     *
     * @param astPlayer 更新対象プレイヤー
     * @param region 新しい地域名
     * @return 地域を更新した場合は {@code true}
     */
    private boolean setCurrentRegion(@NotNull AstPlayer astPlayer, @NotNull String region) {
        if (region.equals(astPlayer.getCurrentRegion())) {
            return false;
        }
        astPlayer.setCurrentRegion(region);
        return true;
    }

    /**
     * 指定プレイヤーの保留中地域タイトルをキャンセルします。
     *
     * @param playerId 対象プレイヤー UUID
     */
    private void cancelPendingTitle(@NotNull UUID playerId) {
        BukkitTask pending = pendingWorldChangeTitles.remove(playerId);
        if (pending != null) {
            pending.cancel();
        }
    }

    /**
     * 地域名と地域移動サブタイトルをプレイヤーへ表示します。
     *
     * @param player 表示対象プレイヤー
     * @param region 表示する地域名
     */
    private static void showRegionChangeTitle(@NotNull Player player, @NotNull String region) {
        String displayRegion = ColorCodeUtil.toLegacyText(region, region);
        player.showTitle(Title.title(
                PlayerMsgResource.formatComponent(PlayerMsgId.P_5780.getId(), displayRegion),
                PlayerMsgResource.getComponent(PlayerMsgId.P_5781.getId()),
                REGION_CHANGE_TITLE_TIMES
        ));
    }
}
