package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * プレイヤーの PvE 通常攻撃連打による劣化状態を管理します。
 * <p>
 * 通常攻撃の成功した発動回数をプレイヤーごとに保持し、通常攻撃劣化遅延を加味した回数から
 * 通常攻撃のダメージと攻撃速度へ段階補正を適用します。状態は
 * {@link io.github.maaasu.astralRecord.feature.buff.service.BuffService} とは独立した一時 runtime state
 * として扱います。
 */
public final class NormalAttackDegradationService {

    /** 最後の通常攻撃から状態を保持する時間（ミリ秒）。 */
    public static final long DEGRADATION_DURATION_MILLIS = 10_000L;
    /** 通常攻撃劣化の最大段階。通常攻撃倍率が0になった時点で以降の補正値が変化しません。 */
    public static final int MAX_STAGE = 11;
    private static final int FIRST_DEGRADATION_ATTACK_COUNT = 5;
    private static final double MAX_ATTACK_SPEED_REDUCTION = 0.50D;
    private static final long UPDATE_INTERVAL_TICKS = 1L;

    private final @Nullable StatusService statusService;
    private final LongSupplier currentTimeMillis;
    private final Map<UUID, DegradationState> states = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private BukkitTask task;

    /** 本番用の時計でサービスを構築します。 */
    public NormalAttackDegradationService() {
        this(null, System::currentTimeMillis);
    }

    /**
     * ステータスを参照する本番用サービスを構築します。
     *
     * @param statusService プレイヤーの計算済みステータスを取得するサービス
     */
    public NormalAttackDegradationService(@NotNull StatusService statusService) {
        this(statusService, System::currentTimeMillis);
    }

    /**
     * 時計を差し替えてサービスを構築します。
     * <p>経過時間を固定した単体テストで使用します。</p>
     *
     * @param currentTimeMillis 現在時刻を返す関数
     */
    NormalAttackDegradationService(@NotNull LongSupplier currentTimeMillis) {
        this(null, currentTimeMillis);
    }

    /**
     * ステータスサービスと時計を差し替えてサービスを構築します。
     * <p>通常攻撃劣化遅延を固定した単体テストで使用します。</p>
     *
     * @param statusService プレイヤーの計算済みステータスを取得するサービス。null の場合は遅延なし
     * @param currentTimeMillis 現在時刻を返す関数
     */
    NormalAttackDegradationService(
            @Nullable StatusService statusService,
            @NotNull LongSupplier currentTimeMillis
    ) {
        this.statusService = statusService;
        this.currentTimeMillis = currentTimeMillis;
    }

    /**
     * 通常攻撃成功前に状態を1回進め、今回の攻撃へ適用する補正を返します。
     * <p>
     * 呼び出し元は通常攻撃の cast が失敗した場合に
     * {@link #rollbackNormalAttack(AstPlayer, AttackTicket)} を呼び出してください。
     * </p>
     *
     * @param player 通常攻撃を行うプレイヤー
     * @return 今回の通常攻撃に採用する段階と補正
     */
    public @NotNull AttackTicket beginNormalAttack(@NotNull AstPlayer player) {
        UUID playerId = player.getBukkit().getUniqueId();
        if (isDegradationExcluded(player)) {
            clearPlayer(playerId);
            return new AttackTicket(0, 1.0D, 1.0D, 0, 0, 0L, 0L);
        }
        long now = currentTimeMillis.getAsLong();
        DegradationState previous = activeState(playerId, now);
        int previousAttackCount = previous == null ? 0 : previous.attackCount();
        long previousExpiresAtMillis = previous == null ? 0L : previous.expiresAtMillis();
        int degradationDelay = degradationDelayAttackCount(player);
        int maxAttackCountForState = maxAttackCount(degradationDelay);
        int attackCount = previousAttackCount >= maxAttackCountForState
                ? maxAttackCountForState
                : previousAttackCount + 1;
        long expiresAtMillis = now + DEGRADATION_DURATION_MILLIS;
        DegradationState updated = new DegradationState(attackCount, expiresAtMillis);
        states.put(playerId, updated);

        int stage = stageForAttackCount(attackCount, degradationDelay);
        if (stage > 0 && player.getBukkit().isOnline()) {
            updateBossBar(player.getBukkit(), updated, now, stage);
        }
        return new AttackTicket(
                stage,
                damageMultiplierForStage(stage),
                attackSpeedMultiplierForStage(stage),
                attackCount,
                previousAttackCount,
                previousExpiresAtMillis,
                expiresAtMillis
        );
    }

    /**
     * 失敗した通常攻撃の状態更新を、更新前へ戻します。
     * <p>後続の通常攻撃がすでに状態を更新していた場合は、その状態を壊さないため何もしません。</p>
     *
     * @param player 通常攻撃を行ったプレイヤー
     * @param ticket {@link #beginNormalAttack(AstPlayer)} が返したチケット
     */
    public void rollbackNormalAttack(@NotNull AstPlayer player, @NotNull AttackTicket ticket) {
        UUID playerId = player.getBukkit().getUniqueId();
        DegradationState current = states.get(playerId);
        if (current == null
                || current.attackCount() != ticket.attackCount()
                || current.expiresAtMillis() != ticket.expiresAtMillis()) {
            return;
        }

        if (ticket.previousAttackCount() <= 0) {
            states.remove(playerId, current);
            removeBossBar(playerId);
            return;
        }

        DegradationState restored = new DegradationState(
                ticket.previousAttackCount(),
                ticket.previousExpiresAtMillis()
        );
        states.put(playerId, restored);
        Player bukkitPlayer = player.getBukkit();
        int stage = stageForState(player, restored);
        if (stage == 0 || !bukkitPlayer.isOnline()) {
            removeBossBar(playerId);
        } else {
            updateBossBar(bukkitPlayer, restored, currentTimeMillis.getAsLong(), stage);
        }
    }

    /**
     * プレイヤーの現在の通常攻撃劣化段階を返します。
     *
     * @param player 対象プレイヤー
     * @return 劣化段階。劣化していない場合は0
     */
    public int currentStage(@NotNull AstPlayer player) {
        if (isDegradationExcluded(player)) {
            clearPlayer(player.getBukkit().getUniqueId());
            return 0;
        }
        DegradationState state = activeState(player.getBukkit().getUniqueId(), currentTimeMillis.getAsLong());
        return state == null ? 0 : stageForState(player, state);
    }

    /**
     * プレイヤーの通常攻撃へ適用するダメージ倍率を返します。
     *
     * @param player 対象プレイヤー
     * @return 通常攻撃ダメージ倍率。劣化していない場合は1.0
     */
    public double currentDamageMultiplier(@NotNull AstPlayer player) {
        return damageMultiplierForStage(currentStage(player));
    }

    /**
     * プレイヤーの通常攻撃へ適用する攻撃速度倍率を返します。
     *
     * @param player 対象プレイヤー
     * @return 攻撃速度倍率。劣化していない場合は1.0
     */
    public double currentAttackSpeedMultiplier(@NotNull AstPlayer player) {
        return attackSpeedMultiplierForStage(currentStage(player));
    }

    /**
     * プレイヤーのスキル成功時に通常攻撃劣化を解除します。
     * <p>通常攻撃自身の成功通知は、通常攻撃スキルIDを除外して保持します。</p>
     *
     * @param player スキルを成功させたプレイヤー
     * @param skillId 成功したスキルID
     */
    public void onSkillCast(@NotNull AstPlayer player, @NotNull String skillId) {
        if (BuiltInWeaponAttackDefinitions.isNormalAttackSkillId(skillId)) {
            if (currentStage(player) == 1) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5357);
            }
            return;
        }
        clearPlayer(player.getBukkit().getUniqueId());
    }

    /**
     * プレイヤーの通常攻撃劣化と表示を解除します。
     *
     * @param playerId 対象プレイヤーUUID
     */
    public void clearPlayer(@NotNull UUID playerId) {
        states.remove(playerId);
        removeBossBar(playerId);
    }

    /**
     * サービスを開始し、1 tick ごとに劣化BossBarを更新します。
     *
     * @param plugin プラグイン
     */
    public void start(@NotNull AstralRecord plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAll, 2L, UPDATE_INTERVAL_TICKS);
    }

    /** サービスを停止し、全プレイヤーの劣化状態とBossBarを破棄します。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (BossBar bossBar : bossBars.values()) {
            bossBar.removeAll();
            bossBar.setVisible(false);
        }
        bossBars.clear();
        states.clear();
    }

    void updateAll() {
        long now = currentTimeMillis.getAsLong();
        for (AstPlayer astPlayer : List.copyOf(AstPlayerCache.getAll())) {
            Player player = astPlayer.getBukkit();
            UUID playerId = player.getUniqueId();
            if (!player.isOnline()
                    || !astPlayer.getAccount().getMode().shouldProcessGameplay()
                    || isDegradationExcluded(astPlayer)) {
                clearPlayer(playerId);
                continue;
            }

            DegradationState state = activeState(playerId, now);
            if (state == null) {
                removeBossBar(playerId);
                continue;
            }
            int stage = stageForState(astPlayer, state);
            if (stage == 0) {
                removeBossBar(playerId);
                continue;
            }
            updateBossBar(player, state, now, stage);
        }

        for (UUID playerId : List.copyOf(states.keySet())) {
            if (AstPlayerCache.get(playerId) == null) {
                clearPlayer(playerId);
            }
        }
    }

    @NotNull BossBar createBossBar(@NotNull Player player) {
        BossBar bossBar = Bukkit.createBossBar(
                "",
                BarColor.RED,
                BarStyle.SEGMENTED_6
        );
        bossBar.setVisible(true);
        bossBar.addPlayer(player);
        return bossBar;
    }

    BossBar bossBarFor(@NotNull UUID playerId) {
        return bossBars.get(playerId);
    }

    private void updateBossBar(
            @NotNull Player player,
            @NotNull DegradationState state,
            long now,
            int stage
    ) {
        UUID playerId = player.getUniqueId();
        BossBar bossBar = bossBars.computeIfAbsent(playerId, ignored -> createBossBar(player));
        bossBar.addPlayer(player);
        bossBar.setColor(BarColor.RED);
        bossBar.setStyle(BarStyle.SEGMENTED_6);
        bossBar.setTitle("通常攻撃劣化[" + stage + "]");
        double progress = (double) (state.expiresAtMillis() - now) / (double) DEGRADATION_DURATION_MILLIS;
        bossBar.setProgress(Math.max(0.0D, Math.min(1.0D, progress)));
    }

    private @NotNull DegradationState activeState(@NotNull UUID playerId, long now) {
        DegradationState state = states.get(playerId);
        if (state == null || state.expiresAtMillis() > now) {
            return state;
        }
        if (states.remove(playerId, state)) {
            removeBossBar(playerId);
        }
        return null;
    }

    private void removeBossBar(@NotNull UUID playerId) {
        BossBar bossBar = bossBars.remove(playerId);
        if (bossBar == null) {
            return;
        }
        bossBar.removeAll();
        bossBar.setVisible(false);
    }

    private int stageForState(@NotNull AstPlayer player, @NotNull DegradationState state) {
        return stageForAttackCount(state.attackCount(), degradationDelayAttackCount(player));
    }

    private static boolean isDegradationExcluded(@NotNull AstPlayer player) {
        return MasterTagIds.Theme.ADVENTURER.equalsIgnoreCase(player.getClassId());
    }

    static int stageForAttackCount(int attackCount) {
        return stageForAttackCount(attackCount, 0);
    }

    static int stageForAttackCount(int attackCount, int degradationDelay) {
        long firstDegradationAttackCount = (long) FIRST_DEGRADATION_ATTACK_COUNT
                + Math.max(0, degradationDelay);
        if (attackCount < firstDegradationAttackCount) {
            return 0;
        }
        return Math.min(MAX_STAGE, (int) ((long) attackCount - firstDegradationAttackCount + 1L));
    }

    private int degradationDelayAttackCount(@NotNull AstPlayer player) {
        if (statusService == null) {
            return 0;
        }
        double value = statusService.getStatus(player).getMaxValue(StatusType.NORMAL_ATTACK_DEGRADATION_DELAY);
        if (!Double.isFinite(value)) {
            return 0;
        }
        return (int) Math.min(
                Integer.MAX_VALUE - (double) FIRST_DEGRADATION_ATTACK_COUNT,
                Math.floor(Math.max(0.0D, value))
        );
    }

    private static int maxAttackCount(int degradationDelay) {
        long maxAttackCount = (long) FIRST_DEGRADATION_ATTACK_COUNT
                + Math.max(0, degradationDelay)
                + MAX_STAGE - 1L;
        return (int) Math.min(Integer.MAX_VALUE, maxAttackCount);
    }

    static double damageMultiplierForStage(int stage) {
        if (stage <= 0) {
            return 1.0D;
        }
        double reductionPercent = switch (Math.min(stage, MAX_STAGE)) {
            case 1 -> 10.0D;
            case 2, 3 -> 15.0D;
            case 4 -> 30.0D;
            default -> 40.0D + (Math.min(stage, MAX_STAGE) - 5) * 10.0D;
        };
        return Math.max(0.0D, 1.0D - reductionPercent / 100.0D);
    }

    static double attackSpeedMultiplierForStage(int stage) {
        if (stage <= 3) {
            return 1.0D;
        }
        double reduction = Math.min(MAX_ATTACK_SPEED_REDUCTION, (stage - 3) * 0.10D);
        return 1.0D - reduction;
    }

    /**
     * 通常攻撃へ適用する段階情報です。
     *
     * @param stage 劣化段階
     * @param damageMultiplier 通常攻撃ダメージ倍率
     * @param attackSpeedMultiplier 通常攻撃攻撃速度倍率
     * @param attackCount 更新後の連続通常攻撃回数
     * @param previousAttackCount 更新前の連続通常攻撃回数
     * @param previousExpiresAtMillis 更新前の状態の有効期限
     * @param expiresAtMillis 更新後の状態の有効期限
     */
    public record AttackTicket(
            int stage,
            double damageMultiplier,
            double attackSpeedMultiplier,
            int attackCount,
            int previousAttackCount,
            long previousExpiresAtMillis,
            long expiresAtMillis
    ) {
    }

    private record DegradationState(int attackCount, long expiresAtMillis) {
    }
}
