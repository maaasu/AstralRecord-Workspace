package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayTimeAccumulator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** 通常プレイモードのアカウント別時間を、既存の原子的 player-state 保存へ渡します。 */
public final class PlayerPlayTimeService {
    public static final String SECTION_NAME = "playTime";
    private final InventoryService inventoryService;
    private final LongSupplier clock;
    private final Map<UUID, PlayTimeAccumulator> byAccount = new ConcurrentHashMap<>();

    /**
     * 単調増加時計を使用するプレイ時間サービスを生成します。
     * @param inventoryService 非同期 account 保存要求を登録するサービス
     */
    public PlayerPlayTimeService(@NotNull InventoryService inventoryService) {
        this(inventoryService, System::nanoTime);
    }

    /** テストで決定的な時計を指定し、Bukkit サーバーなしで時間の保存契約を検証します。 */
    PlayerPlayTimeService(InventoryService inventoryService, LongSupplier clock) {
        this.inventoryService = inventoryService;
        this.clock = clock;
    }

    /**
     * ログインまたはアカウント再ロードの確定後だけ計測を開始します。
     * @param player 確定したプレイヤーセッション
     */
    public void startSession(@NotNull AstPlayer player) {
        byAccount.computeIfAbsent(player.getAccount().getUuid(), ignored -> new PlayTimeAccumulator(clock))
            .start(player, player.getAccount().getMode() == AccountMode.PLAYER);
    }

    /**
     * account state lock 下でローカルモードを確定した直後に旧モードの時間を捕捉します。
     * @param player 対象の現セッション
     * @param mode 確定した新モード。通常プレイ以外は計測しません
     */
    public void changeMode(@NotNull AstPlayer player, @NotNull AccountMode mode) {
        PlayTimeAccumulator state = byAccount.get(player.getAccount().getUuid());
        if (state != null) state.changeMode(player, mode == AccountMode.PLAYER);
    }

    /**
     * logout・account 切替・Plugin 停止の保存開始前に最終時間を捕捉し、計測を止めます。
     * @param player 終了するセッション。後続セッションの計測は止めません
     */
    public void stopSession(@NotNull AstPlayer player) {
        PlayTimeAccumulator state = byAccount.get(player.getAccount().getUuid());
        if (state != null) state.stop(player);
    }

    /**
     * 明示的な保存境界で現アカウントの時間を捕捉します。計測は継続し、API 完了を待機しません。
     * @param player 保存するオンラインプレイヤー
     */
    public void flushSession(@NotNull AstPlayer player) {
        PlayTimeAccumulator state = byAccount.get(player.getAccount().getUuid());
        if (state != null) state.flush();
    }

    /**
     * 定期境界で経過分を捕捉し、既存の非同期保存キューへ渡します。HTTP 完了を待機しません。
     * display の有無にかかわらず呼び出してください。snapshot の dirty 照会では時計を進めません。
     */
    public void flushOnlineSessions() {
        byAccount.forEach((accountId, state) -> {
            if (state.flush()) inventoryService.queueLocalPlayerSave(accountId);
        });
    }

    /**
     * 未保存秒数を player-state section として捕捉します。時計および Bukkit 状態は参照しません。
     * @param accountId 所有アカウント。既存の inventory state lock 下から呼び出します
     * @return 未保存差分がある場合その section。ない場合 null
     */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        PlayTimeAccumulator state = byAccount.get(accountId);
        if (state == null) return null;
        PlayTimeAccumulator.Capture captured = state.capture();
        if (captured == null) return null;
        JsonObject payload = new JsonObject();
        payload.addProperty("accountId", accountId.toString());
        payload.addProperty("deltaSeconds", captured.deltaSeconds());
        return new PlayerStateSection(SECTION_NAME, payload, ack -> {
            validateAcknowledgement(payload, ack);
            if (byAccount.get(accountId) == state) state.acknowledge(captured);
        });
    }

    /**
     * 復旧専用の破棄境界で保存不能 account の時間状態を捨てます。未 ACK 分は復元を保証しません。
     * @param accountId 破棄する旧アカウント
     */
    public void discardAccountState(@NotNull UUID accountId) {
        byAccount.remove(accountId);
    }

    /**
     * 保存元 account と、送信差分以上の非負整数累計を、全 section 適用前に検証します。
     * @param requested 送信した playTime section
     * @param acknowledged API が返した同名 section
     * @throws IllegalStateException account 不一致、欠落、整数範囲外または古い累計の場合
     */
    public static void validateAcknowledgement(@NotNull JsonObject requested, @Nullable JsonElement acknowledged) {
        if (acknowledged == null || !acknowledged.isJsonObject()) {
            throw new IllegalStateException("Missing play-time acknowledgement");
        }
        JsonObject ack = acknowledged.getAsJsonObject();
        JsonElement total = ack.get("totalSeconds");
        if (!requested.get("accountId").equals(ack.get("accountId"))
            || total == null || !total.isJsonPrimitive() || !total.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Invalid play-time acknowledgement");
        }
        long seconds = total.getAsBigDecimal().longValueExact();
        if (seconds < requested.get("deltaSeconds").getAsBigDecimal().longValueExact() || seconds < 0L) {
            throw new IllegalStateException("Stale play-time acknowledgement");
        }
    }
}
