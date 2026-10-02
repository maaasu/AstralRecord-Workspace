package io.github.maaasu.astralRecord.feature.vip.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryOperationSnapshotParser;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.vip.repository.OnlinePaidOperationRepository;
import io.github.maaasu.astralRecord.feature.vip.repository.OnlinePaidOperationRepository.Kind;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService.RuntimeAccountAuthority;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** Web有償購入とメール通貨受取をオンライン保存境界で確定します。 */
public final class OnlinePaidOperationService {
    private final AstralRecord plugin;
    private final InventoryService inventory;
    private final InventorySaveCoordinator saves;
    private final OnlinePaidOperationRepository repository = new OnlinePaidOperationRepository();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask pollTask;
    private volatile boolean closing;

    /** Plugin正本と保存laneを受け取ります。 */
    public OnlinePaidOperationService(AstralRecord plugin, InventoryService inventory,
                                      InventorySaveCoordinator saves) {
        this.plugin = plugin;
        this.inventory = inventory;
        this.saves = saves;
    }

    /** オンラインアカウントの未処理操作を2秒ごとに照会します。 */
    public void start() {
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (AstPlayer player : AstPlayerCache.getAll()) {
                UUID accountId = player.getAccount().getUuid();
                if (!inFlight.add(accountId)) continue;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> findPending(player));
            }
        }, 40L, 40L);
    }

    /** 新規のポーリングを停止します。確定結果不明の保存境界は保持します。 */
    public void stop() {
        closing = true;
        if (pollTask != null) pollTask.cancel();
    }

    /** 非同期で購入を優先して1件の保留操作を選びます。 */
    private void findPending(AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        try {
            Pending next = first(Kind.PURCHASE, accountId);
            if (next == null) next = first(Kind.MAIL_CLAIM, accountId);
            Pending chosen = next;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (chosen == null || closing || AstPlayerCache.get(player.getBukkit()) != player) {
                    inFlight.remove(accountId);
                    return;
                }
                prepare(player, chosen);
            });
        } catch (Exception error) {
            Logger.error(LogId.E_7611, error, accountId);
            inFlight.remove(accountId);
        }
    }

    /** API配列の先頭操作を返します。 */
    private Pending first(Kind kind, UUID accountId) throws java.io.IOException, InterruptedException {
        var values = repository.pending(kind, accountId);
        if (values.isEmpty()) return null;
        JsonObject value = values.get(0).getAsJsonObject();
        return new Pending(kind, UUID.fromString(value.get("operationId").getAsString()), accountId);
    }

    /** 通常保存ACKを得た後、外部操作の境界を取得します。 */
    private void prepare(AstPlayer player, Pending pending) {
        saves.prepareExternalOperationAfterSave(pending.accountId()).whenComplete((prepared, error) -> {
            if (error != null || prepared == null) {
                inFlight.remove(pending.accountId());
                return;
            }
            RuntimeAccountAuthority authority = plugin.getSkillTreeService()
                .snapshotRuntimeAccountAuthority(pending.accountId());
            complete(player, pending, prepared, authority, false);
        });
    }

    /** 確定結果とinventory正本を同じ保存境界で照合し、通信不明時は再試行します。 */
    private void complete(AstPlayer player, Pending pending,
                          InventorySaveCoordinator.PreparedExternalOperation prepared,
                          RuntimeAccountAuthority authority, boolean reported) {
        saves.completePreparedExternalOperation(prepared, baseline -> {
            JsonObject result;
            try { result = repository.process(pending.kind(), pending.operationId(), pending.accountId(), authority); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(error);
            } catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
            if ("REJECTED".equals(result.get("status").getAsString()))
                throw new TerminalRejectedException();
            if (!"COMPLETED".equals(result.get("status").getAsString()))
                throw new IllegalStateException("Paid operation not completed");
            List<UUID> affected = affectedIds(result);
            inventory.reconcileExternalInventoryEntries(pending.accountId(), affected, baseline,
                InventoryOperationSnapshotParser.parse(result.get("inventorySnapshot")));
            return result;
        }).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof TerminalRejectedException) {
                    saves.abandonPreparedExternalOperation(prepared);
                    inFlight.remove(pending.accountId());
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (AstPlayerCache.get(player.getBukkit()) == player)
                            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7615);
                    });
                    return;
                }
                if (!reported) Logger.error(LogId.E_7611, error, pending.accountId());
                if (!closing && plugin.isEnabled()) Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                    () -> complete(player, pending, prepared, authority, true), 40L);
                return;
            }
            inFlight.remove(pending.accountId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (AstPlayerCache.get(player.getBukkit()) != player) return;
                inventory.refreshManagedInventoryUi(player);
                if (pending.kind() == Kind.PURCHASE) {
                    if (result.has("benefits") && result.get("benefits").isJsonObject())
                        plugin.getAccountBenefitsService().applyConfirmedSnapshot(
                            pending.accountId(), result.getAsJsonObject("benefits"));
                    if (result.has("boost") && result.get("boost").isJsonObject())
                        plugin.getChannelBoostService().applyActivation(result);
                } else {
                    plugin.getMailGuiEventHandler().invalidateOpenMail(player.getBukkit());
                }
            });
        });
    }

    /** APIの変更対象entry IDだけをUUIDへ変換します。 */
    private static List<UUID> affectedIds(JsonObject result) {
        List<UUID> ids = new ArrayList<>();
        if (result.has("affectedInventoryEntryIds") && result.get("affectedInventoryEntryIds").isJsonArray())
            for (JsonElement id : result.getAsJsonArray("affectedInventoryEntryIds"))
                ids.add(UUID.fromString(id.getAsString()));
        return ids;
    }

    private record Pending(Kind kind, UUID operationId, UUID accountId) { }

    private static final class TerminalRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
