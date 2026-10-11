package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** セレネア専用フィールドのエメラルド床で、約30ブロックの跳躍を付与します。 */
public final class SeleneaFieldJumpService implements Listener {
    /** インスタンスにも引き継がれる対象ワールドのマスタIDです。 */
    public static final String FIELD_WORLD_ID = "vanaheim_boss_selenea_field";

    // Minecraft 1.21.11: 初速0.42 + 0.1 * 21、重力0.08、空気抵抗0.98で最高点は約29.68m。
    private static final int JUMP_AMPLIFIER = 20;
    private static final int EFFECT_DURATION_TICKS = 80;
    private static final int REFRESH_REMAINING_TICKS = 40;
    private final AstralRecord plugin;
    private final WorldService worldService;
    private final PlayerDeathService playerDeathService;
    private final Set<UUID> boostedPlayers = new HashSet<>();
    private @Nullable BukkitTask task;
    private boolean changingEffect;

    /**
     * 専用床の跳躍サービスを構築します。イベント登録は呼出元が行います。
     * @param plugin 同期タスクを所有するプラグイン
     * @param worldService Bukkitワールドからマスタを解決するサービス
     * @param playerDeathService 独自戦闘の死亡状態を判定するサービス
     */
    public SeleneaFieldJumpService(@NotNull AstralRecord plugin, @NotNull WorldService worldService,
                                  @NotNull PlayerDeathService playerDeathService) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.playerDeathService = playerDeathService;
    }

    /** メインスレッドで床と所有効果の監視を開始します。重複開始は何もしません。 */
    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    /** メインスレッドで監視を停止し、所有効果を解除して元のジャンプ効果を戻します。 */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID playerId : Set.copyOf(boostedPlayers)) {
            clearPlayer(playerId);
        }
        boostedPlayers.clear();
    }

    /**
     * メインスレッドで指定プレイヤーの専用効果を解除します。独自死亡開始時にも使用します。
     * 既存のジャンプ効果は、経過した残り時間と非表示の効果チェーンを保ったまま復元します。
     * @param playerId 解除するプレイヤーUUID。未追跡・オフラインの場合は何もしません
     */
    public void clearPlayer(@NotNull UUID playerId) {
        if (!boostedPlayers.remove(playerId)) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) removeBoost(player, true);
    }

    /**
     * 他ワールドへの移動時に専用効果を解除します。
     * @param event 移動完了イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        if (!isField(event.getPlayer().getWorld())) clearPlayer(event.getPlayer().getUniqueId());
    }

    /**
     * ログアウト前に専用効果を解除し、UUID追跡を破棄します。
     * @param event ログアウトイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (boostedPlayers.remove(player.getUniqueId())) removeBoost(player, true);
    }

    /**
     * バニラ死亡時に専用効果を解除します。死亡で消える元の効果は復元しません。
     * @param event 死亡イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (boostedPlayers.remove(player.getUniqueId())) removeBoost(player, false);
    }

    /**
     * 外部による削除や同等以上の効果への置換時に所有権を手放し、外部効果を後から消さないようにします。
     * 弱い効果が追加された場合はPaperの非表示効果チェーンを維持します。
     * @param event ポーション効果変更イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionChange(@NotNull EntityPotionEffectEvent event) {
        if (changingEffect || !(event.getEntity() instanceof Player player)
            || !PotionEffectType.JUMP_BOOST.equals(event.getModifiedType())) return;
        PotionEffect next = event.getNewEffect();
        if (next == null || (event.isOverride() && next.getAmplifier() >= JUMP_AMPLIFIER)) {
            boostedPlayers.remove(player.getUniqueId());
        }
    }

    /** 対象フィールドのプレイヤーだけに床判定を行い、オフラインの追跡も回収します。 */
    private void tick() {
        boostedPlayers.removeIf(playerId -> Bukkit.getPlayer(playerId) == null);
        for (World world : Bukkit.getWorlds()) {
            boolean field = isField(world);
            for (Player player : world.getPlayers()) {
                if (field) updatePlayer(player);
                else if (boostedPlayers.contains(player.getUniqueId())) clearPlayer(player.getUniqueId());
            }
        }
    }

    /**
     * 対象プレイヤーの床と所有効果を同期します。離陸後は同フィールド内で着地まで維持します。
     * @param player 対象フィールド内のプレイヤー
     */
    private void updatePlayer(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || playerDeathService.isDead(playerId)
            || player.getGameMode() == GameMode.SPECTATOR || player.isFlying()
            || player.isGliding() || player.isInsideVehicle() || player.isInWater()) {
            clearPlayer(playerId);
            return;
        }
        PotionEffect current = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        boolean owned = boostedPlayers.contains(playerId);
        if (owned && !isOwnedBoost(current)) {
            boostedPlayers.remove(playerId);
            owned = false;
        }
        if (isGrounded(player)) {
            if (!onEmerald(player)) {
                clearPlayer(playerId);
                return;
            }
            if (!owned) {
                // 同等以上の外部効果は上書きしません。
                if (current == null || current.getAmplifier() < JUMP_AMPLIFIER) applyBoost(player, current);
                return;
            }
        } else if (!owned) {
            return;
        }
        if (current != null && current.getDuration() <= REFRESH_REMAINING_TICKS) {
            applyBoost(player, current.getHiddenPotionEffect());
        }
    }

    /**
     * 既存のジャンプ効果を非表示チェーンに残した専用効果を付与します。
     * @param player 対象プレイヤー
     * @param hidden 通常の残り時間で進行させる元の効果。未付与ならnull
     */
    private void applyBoost(@NotNull Player player, @Nullable PotionEffect hidden) {
        changingEffect = true;
        try {
            // 同種効果の通常更新は、寿命の短い元効果を捨てるため、初回だけ明示的な非表示チェーンへ移します。
            if (!boostedPlayers.contains(player.getUniqueId()) && hidden != null) {
                player.removePotionEffect(PotionEffectType.JUMP_BOOST);
                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) return;
            }
            boolean applied = player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,
                EFFECT_DURATION_TICKS, JUMP_AMPLIFIER, false, false, false, hidden));
            if (applied && isOwnedBoost(player.getPotionEffect(PotionEffectType.JUMP_BOOST))) {
                boostedPlayers.add(player.getUniqueId());
            } else if (hidden != null && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                // 外部の取消で付与できなかった場合、初回に退避した元効果を失わないようにします。
                player.addPotionEffect(hidden);
            }
        } finally {
            changingEffect = false;
        }
    }

    /**
     * 現在の所有効果を外し、必要なら非表示になっていた元のジャンプ効果を戻します。
     * @param player 対象プレイヤー
     * @param restoreHidden 元の効果を復元する場合true
     */
    private void removeBoost(@NotNull Player player, boolean restoreHidden) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (!isOwnedBoost(current)) return;
        changingEffect = true;
        try {
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
            PotionEffect hidden = current.getHiddenPotionEffect();
            if (restoreHidden && hidden != null && !player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                player.addPotionEffect(hidden);
            }
        } finally {
            changingEffect = false;
        }
    }

    /**
     * 所有記録と併用する効果の強度・寿命の識別を行います。
     * @param effect 現在のジャンプ効果。未付与ならnull
     * @return 専用効果の強度と短い寿命に一致すればtrue
     */
    private boolean isOwnedBoost(@Nullable PotionEffect effect) {
        return effect != null && effect.getAmplifier() == JUMP_AMPLIFIER
            && !effect.isInfinite() && effect.getDuration() <= EFFECT_DURATION_TICKS;
    }

    /**
     * マスタIDを完全一致で判定します。実行時登録済みインスタンスも元のマスタから解決します。
     * @param world 判定対象のBukkitワールド
     * @return セレネア専用フィールドならtrue
     */
    private boolean isField(@NotNull World world) {
        WorldMasterData master = worldService.findByBukkitWorld(world);
        return master != null && FIELD_WORLD_ID.equals(master.id());
    }

    /**
     * Bukkitの接地フラグを床ブロックの実測と併用します。空中では新規付与しません。
     * @param player 判定対象プレイヤー
     * @return クライアントが接地を報告している場合true
     */
    @SuppressWarnings("deprecation") // Playerの接地フラグには公開APIの代替がなく、エメラルド床も別途検証します。
    private boolean isGrounded(@NotNull Player player) {
        return player.isOnGround();
    }

    /**
     * 足元直下の実ブロックを確認します。隣接する未ロードchunkを読み込みません。
     * @param player 接地中のプレイヤー
     * @return 足元中心直下がエメラルドブロックならtrue
     */
    private boolean onEmerald(@NotNull Player player) {
        Location feet = player.getLocation().subtract(0.0D, 0.05D, 0.0D);
        return feet.getWorld().isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4)
            && feet.getBlock().getType() == Material.EMERALD_BLOCK;
    }
}
