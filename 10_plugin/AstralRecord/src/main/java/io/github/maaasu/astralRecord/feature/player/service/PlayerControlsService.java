package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.AccountControlsState;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTask;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** ADMIN/PLAYER共通の操作状態をアカウント所有として原子的保存へ参加させます。 */
public final class PlayerControlsService implements PlayerSaveTask {
    public static final String SECTION_NAME = "accountControls";
    private final AccountRepository repository;
    private final Map<UUID, AccountControlsState> states = new ConcurrentHashMap<>();
    private final Map<UUID, AstPlayer> owners = new ConcurrentHashMap<>();

    /** account読込repositoryを受け取り、HTTP通信は非同期読込境界だけで実行します。
     * @param repository アカウントのAPI読込repository
     */
    public PlayerControlsService(AccountRepository repository) { this.repository = repository; }

    /** 先行保存を待った後、保持中の未保存値またはDB正本を読みます。メインスレッド禁止です。
     * @param accountId 読込対象account
     * @return 未公開の読込state、または保持中の未保存state
     * @throws RuntimeException API取得失敗または不正DB値の場合
     */
    public AccountControlsState load(UUID accountId) {
        AccountControlsState retained = states.get(accountId);
        if (retained != null && retained.capture() != null) return retained;
        var account = repository.findByUuid(accountId);
        if (account == null) throw new IllegalStateException("Account controls owner unavailable");
        return new AccountControlsState(new AccountControlsState.Values(account.getSelectedHotbarSlot(),
            account.getViewYaw(), account.getViewPitch()), account.getControlsVersion());
    }

    /** 参加transaction内でBukkit値を反映します。状態の公開はcommitまで行いません。
     * @param player 対象player。メインスレッドで呼びます
     * @param state 復元する操作state
     * @throws IllegalStateException 方向復元の転送がキャンセルされた場合
     */
    public void apply(Player player, AccountControlsState state) {
        var values = state.values();
        player.getInventory().setHeldItemSlot(values.selectedHotbarSlot());
        if (!io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService.restoreLookDirection(
            player, values.viewYaw(), values.viewPitch()))
            throw new IllegalStateException("Account controls restoration was cancelled");
    }

    /** 参加が成功したセッションだけ操作状態の正本と所有者を公開します。
     * @param player 確定したセッション
     * @param state 成功した参加で適用済みの操作state
     */
    public synchronized void commit(AstPlayer player, AccountControlsState state) {
        states.put(player.getAccount().getUuid(), state);
        owners.put(player.getAccount().getUuid(), player);
    }

    /** Bukkit値をメインスレッドで捕捉し、後続の非同期snapshotへ渡します。HTTPを待ちません。
     * @param player 対象セッション。所有者が既に変わった場合は何もしません
     * @throws IllegalArgumentException Bukkit値が不正な場合
     */
    public void capture(AstPlayer player) {
        UUID id = player.getAccount().getUuid();
        AccountControlsState state = states.get(id);
        if (state == null || owners.get(id) != player) return;
        var location = player.getBukkit().getLocation();
        state.update(new AccountControlsState.Values(player.getBukkit().getInventory().getHeldItemSlot(),
            location.getYaw(), location.getPitch()));
    }

    /** 同一保存境界の操作捕捉タスク名です。
     * @return 保存section名
     */
    @Override public @NotNull String getTaskName() { return SECTION_NAME; }
    /** logout/channel/shutdownの保存前にメインスレッドで値を捕捉します。
     * @param player 保存対象セッション
     * @param trigger 保存契機
     */
    @Override public void prepare(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) { capture(player); }
    /** 保存本体はinventoryの原子的snapshotで行うため、このtaskはI/Oを行いません。
     * @param player 保存対象セッション
     * @param trigger 保存契機
     */
    @Override public void save(@NotNull AstPlayer player, @NotNull PlayerSaveTrigger trigger) { }

    /** account state lock下で不変sectionを捕捉します。Bukkitは参照しません。
     * @param accountId 対象所有account
     * @return 未保存操作があればsection、なければnull
     */
    public @Nullable PlayerStateSection snapshotPlayerState(UUID accountId) {
        AccountControlsState state = states.get(accountId);
        if (state == null) return null;
        var captured = state.capture();
        if (captured == null) return null;
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("expectedVersion", captured.expectedVersion());
        payload.addProperty("selectedHotbarSlot", captured.values().selectedHotbarSlot());
        payload.addProperty("viewYaw", captured.values().viewYaw());
        payload.addProperty("viewPitch", captured.values().viewPitch());
        return new PlayerStateSection(SECTION_NAME, payload, ack -> {
            validateAcknowledgement(payload, ack);
            if (states.get(accountId) == state) state.acknowledge(captured, ack.getAsJsonObject().get("version").getAsInt());
        });
    }

    /** 全section適用前に所有accountと専用DB世代を検証します。
     * @param requested 送信した不変payload
     * @param acknowledgement API応答の同名section
     * @throws RuntimeException 所有者不一致、欠落、非整数、期待版+1と異なる場合
     */
    public static void validateAcknowledgement(JsonObject requested, JsonElement acknowledgement) {
        if (acknowledgement == null || !acknowledgement.isJsonObject())
            throw new IllegalStateException("Missing controls acknowledgement");
        var ack = acknowledgement.getAsJsonObject();
        var raw = ack.get("version");
        if (!requested.get("accountId").equals(ack.get("accountId")) || raw == null
            || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
            || raw.getAsBigDecimal().intValueExact() != Math.addExact(requested.get("expectedVersion").getAsInt(), 1))
            throw new IllegalStateException("Invalid controls acknowledgement");
    }

    /** 保存成功した旧セッションだけ解放します。再接続先が所有したstateは消しません。
     * @param player ACK完了した旧セッション
     */
    public synchronized void releaseSaved(AstPlayer player) {
        UUID id = player.getAccount().getUuid();
        owners.computeIfPresent(id, (ignored, owner) -> {
            var state = states.get(id);
            if (owner != player || state != null && state.capture() != null) return owner;
            if (state != null) states.remove(id, state);
            return null;
        });
    }

    /** 保存不能復旧・account削除時だけ、未保存状態を明示的に破棄します。
     * @param accountId 旧操作状態を破棄するaccount
     */
    public synchronized void discard(UUID accountId) { owners.remove(accountId); states.remove(accountId); }
}
