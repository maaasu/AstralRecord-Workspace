package io.github.maaasu.astralRecord.infrastructure.api;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

/**
 * AstralRecord API への疎通確認を行うクラス。
 * プラグイン有効化時に非同期で HTTP リクエストを送信し、接続可否をログに記録します。
 * HTTP クライアントの構築（SSL 設定・タイムアウト・認証ヘッダー）は {@link ApiRequestUtil} に委譲します。
 */
public final class ApiHealthChecker {

    private static final String HEALTH_PATH = "/health";

    private ApiHealthChecker() {
        // utility class
    }

    /**
     * 非同期でAPI疎通確認を実行します。
     * PaperAPI の非同期スケジューラを使用するため、メインスレッドをブロックしません。
     */
    public static void checkAsync() {
        AstralRecord plugin = AstralRecord.getInstance();
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> check());
    }

    /**
     * API疎通確認を同期的に実行します。
     * このメソッドは必ず非同期スレッドから呼び出すこと。
     */
    private static void check() {
        String url = ConfigProperties.getInstance().getApiBaseUrl() + HEALTH_PATH;
        Logger.log(LogId.I_1600, url);

        try (HttpClient client = ApiRequestUtil.buildClient()) {
            HttpResponse<Void> response = client.send(
                    ApiRequestUtil.buildRequestBuilder(HEALTH_PATH).GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) {
                Logger.log(LogId.I_1601, statusCode);
            } else {
                Logger.log(LogId.W_1600, statusCode);
            }

        } catch (IOException e) {
            Logger.log(LogId.E_1600, e, url);
        } catch (InterruptedException e) {
            Logger.log(LogId.E_1600, e, url);
            Thread.currentThread().interrupt();
        }
    }
}
