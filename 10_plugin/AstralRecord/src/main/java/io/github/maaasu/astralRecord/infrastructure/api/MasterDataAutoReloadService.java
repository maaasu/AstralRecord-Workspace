package io.github.maaasu.astralRecord.infrastructure.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;

import java.net.http.HttpResponse;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MasterDataDB health API を定期監視し、新しい成功済み Seeder 実行に追随して
 * Plugin のマスターデータを再読込します。
 */
public final class MasterDataAutoReloadService {
    private static final String HEALTH_PATH = "/api/master-data/health";

    private final AstralRecord plugin;
    private final long pollIntervalSeconds;
    private final MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<HttpResponse<String>>> healthRequestInFlight =
        new AtomicReference<>();
    private volatile ScheduledTask pollTask;

    /**
     * 監視サービスを生成します。
     *
     * @param plugin Plugin 本体
     * @param pollIntervalSeconds health 取得間隔（秒）
     */
    public MasterDataAutoReloadService(@NotNull AstralRecord plugin, long pollIntervalSeconds) {
        this.plugin = plugin;
        this.pollIntervalSeconds = Math.max(1L, pollIntervalSeconds);
    }

    /**
     * 非同期 Scheduler 上で定期監視を開始します。
     * 初回応答は比較基準としてだけ記録し、再読込には使用しません。
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(false, true)) {
                return;
            }
            try {
                pollTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin,
                    ignored -> pollOnce(),
                    1L,
                    pollIntervalSeconds,
                    TimeUnit.SECONDS
                );
                Logger.log(LogId.I_1602, pollIntervalSeconds);
            } catch (RuntimeException schedulingFailure) {
                running.set(false);
                Logger.log(LogId.E_1601, schedulingFailure, failureMessage(schedulingFailure));
            }
        }
    }

    /**
     * 定期監視と処理中の health リクエストを停止します。
     * 完了待ちの再読込 callback は停止後の状態を変更しません。
     */
    public void stop() {
        ScheduledTask currentTask;
        CompletableFuture<HttpResponse<String>> currentRequest;
        synchronized (lifecycleLock) {
            if (!running.getAndSet(false)) {
                return;
            }
            currentTask = pollTask;
            pollTask = null;
            currentRequest = healthRequestInFlight.getAndSet(null);
        }
        if (currentTask != null) {
            currentTask.cancel();
        }
        if (currentRequest != null) {
            currentRequest.cancel(true);
        }
    }

    private void pollOnce() {
        CompletableFuture<HttpResponse<String>> request;
        synchronized (lifecycleLock) {
            if (!running.get() || healthRequestInFlight.get() != null) {
                return;
            }
            try {
                request = ApiRequestUtil.sharedClient().sendAsync(
                    ApiRequestUtil.buildRequestBuilder(HEALTH_PATH).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
            } catch (RuntimeException requestFailure) {
                Logger.log(LogId.E_1601, requestFailure, failureMessage(requestFailure));
                return;
            }
            healthRequestInFlight.set(request);
        }
        request.whenComplete((response, throwable) -> {
            healthRequestInFlight.compareAndSet(request, null);
            if (!running.get() || request.isCancelled()) {
                return;
            }
            if (throwable != null) {
                Throwable cause = unwrap(throwable);
                Logger.log(LogId.E_1601, cause, failureMessage(cause));
                return;
            }
            handleResponse(response);
        });
    }

    private void handleResponse(@NotNull HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Logger.log(LogId.W_1602, response.statusCode());
            return;
        }

        MasterDataHealthSnapshot snapshot;
        try {
            snapshot = parseSnapshot(response.body());
        } catch (RuntimeException parseFailure) {
            Logger.log(LogId.E_1602, parseFailure, failureMessage(parseFailure));
            return;
        }

        MasterDataAutoReloadCoordinator.Observation observation = coordinator.observe(snapshot);
        switch (observation) {
            case BASELINE_CAPTURED -> Logger.log(
                LogId.I_1603,
                snapshot.lastSeedRunId(),
                snapshot.lastSeedRunStatus(),
                snapshot.lastSucceededAt()
            );
            case MISSING_RUN_ID -> Logger.log(
                LogId.W_1603,
                snapshot.lastSeedRunStatus(),
                snapshot.lastSucceededAt()
            );
            case INCOMPLETE_SUCCESS -> Logger.log(LogId.W_1604, snapshot.lastSeedRunId());
            case RELOAD_REQUIRED -> {
                MasterDataHealthSnapshot reloadTarget = coordinator.reloadTarget();
                if (reloadTarget != null) {
                    scheduleReload(reloadTarget);
                }
            }
            case NONE, DEFERRED -> {
                // 次の定期取得または実行中 reload の完了後再確認へ委ねる。
            }
        }
    }

    private void scheduleReload(@NotNull MasterDataHealthSnapshot snapshot) {
        UUID seedRunId = snapshot.lastSeedRunId();
        if (seedRunId == null) {
            return;
        }
        Logger.log(LogId.I_1604, seedRunId, snapshot.lastSucceededAt());
        try {
            AsyncTaskUtil.runSync(plugin, () -> beginReload(seedRunId));
        } catch (RuntimeException schedulingFailure) {
            coordinator.schedulingFailed(seedRunId);
            Logger.log(LogId.E_1603, schedulingFailure, seedRunId, failureMessage(schedulingFailure));
        }
    }

    private void beginReload(@NotNull UUID seedRunId) {
        if (!running.get() || !plugin.isEnabled()) {
            coordinator.schedulingFailed(seedRunId);
            return;
        }

        AstralRecord.MasterDataReloadStart reload;
        try {
            reload = plugin.reloadMasterData();
        } catch (RuntimeException reloadStartFailure) {
            coordinator.schedulingFailed(seedRunId);
            Logger.log(LogId.E_1603, reloadStartFailure, seedRunId, failureMessage(reloadStartFailure));
            return;
        }

        boolean ownedReload = reload.started();
        if (!ownedReload) {
            Logger.log(LogId.W_1605, seedRunId);
        }
        reload.completion().whenComplete((loadedCount, throwable) -> {
            Throwable cause = throwable == null ? null : unwrap(throwable);
            boolean succeeded = cause == null;
            boolean deferred;
            synchronized (lifecycleLock) {
                if (!running.get()) {
                    return;
                }
                deferred = coordinator.reloadCompleted(seedRunId, ownedReload, succeeded);
            }
            if (ownedReload && succeeded) {
                Logger.log(LogId.I_1605, seedRunId, loadedCount);
            } else if (cause != null) {
                Logger.log(LogId.E_1603, cause, seedRunId, failureMessage(cause));
            }
            if (succeeded || deferred) {
                requestImmediatePoll();
            }
        });
    }

    private void requestImmediatePoll() {
        if (!running.get()) {
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> pollOnce());
        } catch (RuntimeException schedulingFailure) {
            Logger.log(LogId.E_1601, schedulingFailure, failureMessage(schedulingFailure));
        }
    }

    static @NotNull MasterDataHealthSnapshot parseSnapshot(String responseBody) {
        JsonElement root = JsonParser.parseString(responseBody);
        if (!root.isJsonObject()) {
            throw new JsonParseException("master-data health response is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        String runIdText = optionalString(object, "lastSeedRunId");
        String status = optionalString(object, "lastSeedRunStatus");
        String succeededAtText = optionalString(object, "lastSucceededAt");
        try {
            UUID runId = runIdText == null ? null : UUID.fromString(runIdText);
            if (succeededAtText != null) {
                DateTimeFormatter.ISO_DATE_TIME.parse(succeededAtText);
            }
            return new MasterDataHealthSnapshot(runId, status, succeededAtText);
        } catch (IllegalArgumentException | DateTimeParseException invalidValue) {
            throw new JsonParseException("master-data health response contains an invalid value", invalidValue);
        }
    }

    private static String optionalString(@NotNull JsonObject object, @NotNull String propertyName) {
        JsonElement element = object.get(propertyName);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static @NotNull Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException("unknown failure") : current;
    }

    private static @NotNull String failureMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
