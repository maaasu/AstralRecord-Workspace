package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 拠点の初期スポーン付近で石ボタンを見ているプレイヤーのブロックリーチと、
 * プレイヤーモードのブロッククリック制限を制御します。
 */
public final class StoneButtonReachService
    implements PlayerInputResolver<PlayerInteractionSnapshot> {

    private static final long UPDATE_PERIOD_TICKS = 20L;
    private static final double BASE_SPAWN_RADIUS_SQUARED = 15.0D * 15.0D;
    private static final double STONE_BUTTON_REACH = 4.5D;
    private static final String PLAYER_MODE_BLOCK_CLICK_GUARD_ID = "player-mode-block-click-guard";

    private final Plugin plugin;
    private final WorldService worldService;
    private BukkitTask task;

    /**
     * サービスを構築します。
     *
     * @param plugin 周期タスクを起動するプラグイン
     * @param worldService 現在ワールドと初期スポーンを解決するサービス
     */
    public StoneButtonReachService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * 石ボタン注視状態の更新タスクを開始します。
     * 更新はメインスレッドで 20 tick（1 秒）ごとに行います。
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::refreshAll,
                0L,
                UPDATE_PERIOD_TICKS
        );
    }

    /**
     * 更新タスクを停止し、プレイヤーモードのブロックリーチを 0 に戻します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            resetPlayerReach(astPlayer);
        }
    }

    /**
     * プレイヤーモードの条件外ブロッククリックをキャンセル候補として返します。
     * 条件内の石ボタン、プレイヤーモード以外、ブロッククリック以外は通常処理へ委譲します。
     *
     * @param context プレイヤー入力のコンテキスト
     * @return 条件外クリックをキャンセルする候補、または候補なし
     */
    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
            @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.source() != InputSource.PLAYER_INTERACT
                || !isBlockClick(snapshot.action())
                || snapshot.clickedBlock() == null) {
            return List.of();
        }

        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (astPlayer == null
                || astPlayer.getAccount().getMode() != AccountMode.PLAYER
                || isAllowedStoneButtonClick(snapshot.player(), snapshot.clickedBlock())) {
            return List.of();
        }

        return List.of(new PlayerInputCandidate(
                PLAYER_MODE_BLOCK_CLICK_GUARD_ID,
                InteractionTier.INPUT_LOCK,
                0.0D,
                InteractionCandidateOrder.PLAYER_BLOCK_CLICK_GUARD,
                snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
        ));
    }

    /**
     * キャッシュ済みプレイヤーの石ボタン注視状態を一括更新します。
     *
     * <p>Bukkit API、ワールド解決、ray trace、属性変更を行うため、メインスレッドから呼び出してください。
     * `PLAYER` 以外のアカウントは変更せず、`PLAYER` の対象外状態はブロックリーチを 0 に戻します。
     */
    void refreshAll() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            refreshPlayer(astPlayer);
        }
    }

    /**
     * 指定プレイヤーのアカウントモードと周辺状態を判定してブロックリーチを更新します。
     *
     * <p>メインスレッドで実行し、拠点初期スポーン付近で石ボタンを見ている場合だけ 4.5、その他の
     * `PLAYER` 状態では 0 を属性へ反映します。`PLAYER` 以外のアカウントには副作用を発生させません。
     *
     * @param astPlayer 更新対象のキャッシュ済みプレイヤー
     */
    private void refreshPlayer(@NotNull AstPlayer astPlayer) {
        if (astPlayer.getAccount().getMode() != AccountMode.PLAYER) {
            return;
        }

        Player player = astPlayer.getBukkit();
        double blockReach = isEligibleForExtendedReach(player) ? STONE_BUTTON_REACH : 0.0D;
        applyBlockReach(player, blockReach);
    }

    /**
     * 指定プレイヤーのブロックリーチを通常の 0 へ戻します。
     *
     * <p>メインスレッドで実行してください。対象が `PLAYER` の場合だけ属性を変更し、管理者などの
     * `PLAYER` 以外のアカウントが持つ基準値はこのサービスから上書きしません。
     *
     * @param astPlayer リーチをリセットするキャッシュ済みプレイヤー
     */
    private void resetPlayerReach(@NotNull AstPlayer astPlayer) {
        if (astPlayer.getAccount().getMode() == AccountMode.PLAYER) {
            applyBlockReach(astPlayer.getBukkit(), 0.0D);
        }
    }

    /**
     * 拠点初期スポーンからの距離と視線先を確認し、拡張条件を満たすか判定します。
     *
     * <p>メインスレッドで実行してください。現在ワールドが `BASE` で初期スポーンが解決でき、
     * 三次元距離が 15 メートル以内の場合だけ、目線から最大 4.5 ブロックの同期 ray trace を行います。
     *
     * @param player 判定対象の Bukkit プレイヤー
     * @return 石ボタンを視点の先に捉えていてブロックリーチを拡張できる場合は {@code true}、それ以外は {@code false}
     */
    private boolean isEligibleForExtendedReach(@NotNull Player player) {
        if (!isWithinBaseSpawnRadius(player)) {
            return false;
        }

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        RayTraceResult hit = world.rayTraceBlocks(
                eye,
                eye.getDirection(),
                STONE_BUTTON_REACH,
                FluidCollisionMode.NEVER,
                false
        );
        if (hit == null) {
            return false;
        }

        Block target = hit.getHitBlock();
        return target != null && target.getType() == Material.STONE_BUTTON;
    }

    /**
     * 指定されたクリック対象がプレイヤーモードで許可された石ボタンか判定します。
     *
     * <p>メインスレッドで実行してください。クリック対象が現在ワールドの石ボタンで、
     * プレイヤーが拠点初期スポーンから15メートル以内の場合だけ {@code true} を返します。
     *
     * @param player クリックした Bukkit プレイヤー
     * @param clickedBlock 直接クリックされたブロック。未クリックの場合は {@code null}
     * @return 通常のブロック操作へ委譲できる石ボタンの場合は {@code true}
     */
    private boolean isAllowedStoneButtonClick(
            @NotNull Player player,
            @Nullable Block clickedBlock
    ) {
        return clickedBlock != null
                && clickedBlock.getWorld() == player.getWorld()
                && clickedBlock.getType() == Material.STONE_BUTTON
                && isWithinBaseSpawnRadius(player);
    }

    /**
     * プレイヤーが拠点の初期スポーンから許容半径内にいるか判定します。
     *
     * <p>メインスレッドで実行してください。現在ワールドが拠点で、初期スポーンを解決でき、
     * プレイヤーとの三次元距離が15メートル以内の場合だけ {@code true} を返します。
     *
     * @param player 判定対象の Bukkit プレイヤー
     * @return 拠点初期スポーンから15メートル以内の場合は {@code true}
     */
    private boolean isWithinBaseSpawnRadius(@NotNull Player player) {
        World world = player.getWorld();
        WorldMasterData worldData = worldService.findByBukkitWorld(world);
        if (worldData == null || worldData.worldType() != WorldType.BASE) {
            return false;
        }

        Location spawn = worldService.resolveSpawnLocation(worldData);
        return spawn != null
                && spawn.getWorld() == world
                && player.getLocation().distanceSquared(spawn) <= BASE_SPAWN_RADIUS_SQUARED;
    }

    /**
     * 入力 action がブロッククリックを表すか判定します。
     *
     * @param action PlayerInteractEvent から取得した action。{@code null} を許容します
     * @return 左または右のブロッククリックの場合は {@code true}
     */
    private boolean isBlockClick(@Nullable Action action) {
        return action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK;
    }

    /**
     * Bukkit のブロックリーチ属性へ指定値を反映します。
     *
     * <p>メインスレッドで実行してください。属性が存在し、現在値と異なる場合だけ base value を変更します。
     *
     * @param player 属性を変更する Bukkit プレイヤー
     * @param value 設定するブロックリーチ値
     */
    private void applyBlockReach(@NotNull Player player, double value) {
        AttributeInstance attribute = player.getAttribute(Attribute.BLOCK_INTERACTION_RANGE);
        if (attribute != null && Double.compare(attribute.getBaseValue(), value) != 0) {
            attribute.setBaseValue(value);
        }
    }
}
