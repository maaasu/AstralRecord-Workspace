package io.github.maaasu.astralRecord.feature.inventory.repository;

import org.jetbrains.annotations.NotNull;

/**
 * インベントリ API が 2xx 以外を返したことを表す例外です。
 *
 * <p>HTTP ステータスとレスポンス本文を保持するため、保存処理側で
 * 「対象 inventory 不在」と「楽観ロック競合」を区別して扱えます。</p>
 */
public final class InventoryApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String method;
    private final String path;
    private final int statusCode;
    private final String responseBody;

    /**
     * API エラー情報を構築します。
     *
     * @param method HTTP メソッド
     * @param path API パス
     * @param statusCode HTTP ステータス
     * @param responseBody API レスポンス本文
     */
    public InventoryApiException(
        @NotNull String method,
        @NotNull String path,
        int statusCode,
        @NotNull String responseBody
    ) {
        super(buildMessage(method, path, statusCode, summarize(responseBody)));
        this.method = method;
        this.path = path;
        this.statusCode = statusCode;
        this.responseBody = summarize(responseBody);
    }

    /** @return HTTP メソッド */
    public @NotNull String getMethod() {
        return method;
    }

    /** @return API パス */
    public @NotNull String getPath() {
        return path;
    }

    /** @return HTTP ステータス */
    public int getStatusCode() {
        return statusCode;
    }

    /** @return API レスポンス本文 */
    public @NotNull String getResponseBody() {
        return responseBody;
    }

    private static @NotNull String buildMessage(
        @NotNull String method,
        @NotNull String path,
        int statusCode,
        @NotNull String responseBody
    ) {
        String body = responseBody;
        if (body.isEmpty()) {
            body = "<empty>";
        }
        return "Unexpected status " + statusCode + " for " + method + " " + path + ", response=" + body;
    }

    private static @NotNull String summarize(@NotNull String responseBody) {
        String body = responseBody.trim();
        if (body.isEmpty()) {
            body = "<empty>";
        } else if (body.length() > 500) {
            body = body.substring(0, 500) + "...";
        }
        return body;
    }
}
