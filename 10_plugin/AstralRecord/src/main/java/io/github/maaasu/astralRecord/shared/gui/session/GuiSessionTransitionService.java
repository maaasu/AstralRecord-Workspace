package io.github.maaasu.astralRecord.shared.gui.session;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プラグイン管理 GUI の開始・終了候補をセッショントークン単位で追跡します。
 *
 * <p>close 候補を直ちに終了へ確定せず、次 tick に同じセッションが残っている場合だけ
 * 終了とします。後続 GUI の open が成功した場合は新しいセッショントークンへ置き換わるため、
 * 先行 GUI の close 候補は自動的に無効になります。</p>
 */
public final class GuiSessionTransitionService {
    private static final String PLUGIN_PACKAGE_PREFIX = "io.github.maaasu.astralRecord.";

    private final Map<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, ContinuationToken> pendingContinuations = new ConcurrentHashMap<>();

    /**
     * プラグイン管理 GUI の表示成功を記録します。
     *
     * @param playerId プレイヤー UUID
     * @param inventory 表示された GUI inventory
     */
    public void registerOpened(@NotNull UUID playerId, @NotNull Inventory inventory) {
        activeSessions.put(playerId, new ActiveSession(UUID.randomUUID(), inventory));
        pendingContinuations.remove(playerId);
    }

    /**
     * GUI close を終了候補として記録します。
     *
     * <p>別 GUI の open が同じ tick 中に成功した場合は、次 tick の完了判定時にこの候補を
     * 無効化します。</p>
     *
     * @param playerId プレイヤー UUID
     * @param inventory close された GUI inventory
     * @return 現在の GUI セッションに対応する close トークン。管理対象外または古い close の場合は {@code null}
     */
    public @Nullable CloseToken beginClose(@NotNull UUID playerId, @NotNull Inventory inventory) {
        ActiveSession active = activeSessions.get(playerId);
        if (active == null || !active.matches(inventory)) {
            return null;
        }
        return new CloseToken(UUID.randomUUID(), active.sessionId(), inventory);
    }

    /**
     * close 後の共有 GUI 再表示を、現在のセッションを維持したまま予約します。
     *
     * @param playerId プレイヤー UUID
     * @param inventory close された遷移元 GUI inventory
     * @return 継続予約 token。現在のセッションと一致しない場合は {@code null}
     */
    public @Nullable ContinuationToken beginContinuation(@NotNull UUID playerId, @NotNull Inventory inventory) {
        ActiveSession active = activeSessions.get(playerId);
        if (active == null || !active.matches(inventory)) {
            return null;
        }
        ContinuationToken continuation = new ContinuationToken(UUID.randomUUID(), active.sessionId(), inventory);
        pendingContinuations.put(playerId, continuation);
        return continuation;
    }

    /**
     * 指定した継続予約が現在の GUI セッションに対して有効か判定します。
     *
     * @param playerId プレイヤー UUID
     * @param continuation 評価対象 token
     * @return 現在有効な継続予約の場合は {@code true}
     */
    public boolean isContinuationPending(@NotNull UUID playerId, @NotNull ContinuationToken continuation) {
        ActiveSession active = activeSessions.get(playerId);
        return continuation.equals(pendingContinuations.get(playerId))
            && active != null
            && active.matches(continuation);
    }

    /**
     * 継続予約の target open が失敗した場合に、遷移元セッションを終了します。
     *
     * @param playerId プレイヤー UUID
     * @param continuation 失敗した継続予約 token
     * @return 終了を確定した inventory。既に別遷移へ進んだ場合は {@code null}
     */
    public @Nullable Inventory failContinuation(@NotNull UUID playerId, @NotNull ContinuationToken continuation) {
        if (!pendingContinuations.remove(playerId, continuation)) {
            return null;
        }
        ActiveSession active = activeSessions.get(playerId);
        if (active == null || !active.matches(continuation)) {
            return null;
        }
        return activeSessions.remove(playerId, active) ? active.inventory() : null;
    }

    /**
     * 後続の非管理 GUI open により不要になった継続予約だけを破棄します。
     *
     * @param playerId プレイヤー UUID
     */
    public void cancelContinuation(@NotNull UUID playerId) {
        pendingContinuations.remove(playerId);
    }

    /**
     * close トークンを次 tick に評価し、GUI セッションの終了を確定します。
     *
     * @param playerId プレイヤー UUID
     * @param closeToken 評価する close トークン
     * @return 同じセッションが継続していたため終了した inventory。別 GUI 遷移または古い候補なら {@code null}
     */
    public @Nullable Inventory finishClose(@NotNull UUID playerId, @NotNull CloseToken closeToken) {
        ActiveSession active = activeSessions.get(playerId);
        if (active == null || !active.matches(closeToken)) {
            return null;
        }
        ContinuationToken continuation = pendingContinuations.get(playerId);
        if (continuation != null && active.matches(continuation)) {
            return null;
        }
        return activeSessions.remove(playerId, active) ? active.inventory() : null;
    }

    /**
     * ログアウト時に現在の GUI セッションを音なしで終了します。
     *
     * <p>未確定の close・継続 token も同時に破棄し、同じ session を後続 task が再終了しないようにします。</p>
     *
     * @param playerId 終了対象プレイヤー UUID
     * @return 終了を確定した inventory。管理対象の session がない場合は {@code null}
     */
    public @Nullable Inventory endSilently(@NotNull UUID playerId) {
        pendingContinuations.remove(playerId);
        ActiveSession active = activeSessions.remove(playerId);
        return active == null ? null : active.inventory();
    }

    /**
    * ログアウトなどでプレイヤーの GUI 遷移状態を破棄します。
     *
     * @param playerId 破棄対象プレイヤー UUID
     */
    public void clear(@NotNull UUID playerId) {
        activeSessions.remove(playerId);
        pendingContinuations.remove(playerId);
    }

    /**
     * 指定 inventory が AstralRecord のプラグイン管理 GUI かを判定します。
     *
     * @param inventory 判定対象 inventory
     * @return プラグイン管理 GUI の場合は {@code true}
     */
    public static boolean isPluginManagedGui(@Nullable Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder != null && holder.getClass().getName().startsWith(PLUGIN_PACKAGE_PREFIX);
    }

    /** GUI close と後続 open を対応付ける一回限りのトークンです。 */
    public record CloseToken(@NotNull UUID id, @NotNull UUID sessionId, @NotNull Inventory inventory) {
    }

    /** GUI close 後の再表示を対応付ける一回限りの継続 token です。 */
    public record ContinuationToken(@NotNull UUID id, @NotNull UUID sessionId, @NotNull Inventory inventory) {
    }

    private record ActiveSession(@NotNull UUID sessionId, @NotNull Inventory inventory) {
        private boolean matches(@NotNull Inventory candidate) {
            return inventory == candidate;
        }

        private boolean matches(@NotNull CloseToken closeToken) {
            return sessionId.equals(closeToken.sessionId()) && inventory == closeToken.inventory();
        }

        private boolean matches(@NotNull ContinuationToken continuation) {
            return sessionId.equals(continuation.sessionId()) && inventory == continuation.inventory();
        }
    }
}
