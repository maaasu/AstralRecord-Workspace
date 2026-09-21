package io.github.maaasu.astralRecord.feature.account.repository;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** アカウント識別子の解決と、API内の原子的な複製操作を扱います。同期通信は非同期スレッド専用です。 */
public final class AccountManagementRepository {
    private final AccountRepository accounts;

    /** 標準のアカウント読み出し境界で初期化します。 */
    public AccountManagementRepository() { this(new AccountRepository()); }

    /**
     * 作成結果の読み出し境界を指定します。
     * @param accounts アカウント読み出しリポジトリ
     */
    AccountManagementRepository(AccountRepository accounts) { this.accounts = accounts; }

    /**
     * UUID・名称・スロット、またはユーザーの選択中アカウントを解決します。
     * @param selector UUID・名称・1～2桁のスロット。nullなら選択中
     * @param playerName 所属ユーザーのMCID。nullなら全体で名称またはUUID検索
     * @return 未削除アカウント。未登録ならnull
     * @throws IOException API通信または入力検証に失敗した場合
     */
    public @Nullable AccountModel resolve(@Nullable String selector, @Nullable String playerName) throws IOException {
        String path = "/api/account/resolve?";
        if (selector != null) path += "selector=" + encode(selector) + "&";
        if (playerName != null) path += "user_mcid=" + encode(playerName);
        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder(path).GET().build());
        if (response.statusCode() == 404) return null;
        requireSuccess(response);
        return accounts.findByUuid(UUID.fromString(JsonParser.parseString(response.body()).getAsJsonObject().get("uuid").getAsString()));
    }

    /**
     * 確認済みの複製先だけへアカウント専用データを複製します。失敗時は自動再送しません。
     * @param source 複製元UUID
     * @param targetUser 複製先ユーザーUUID
     * @param slot 複製先スロット（0～99）
     * @param expectedTarget 確認した既存先UUID。新規の場合null
     * @param actor 操作者UUID
     * @return 複製されたアカウント
     * @throws IOException API通信・競合・検証に失敗した場合
     * @throws IllegalArgumentException スロットが範囲外の場合（通信しない）
     */
    public AccountModel cloneAccount(UUID source, UUID targetUser, int slot, @Nullable UUID expectedTarget, UUID actor)
        throws IOException {
        if (slot < 0 || slot > 99) throw new IllegalArgumentException("Slot must be between 0 and 99");
        JsonObject body = new JsonObject();
        body.addProperty("targetUserId", targetUser.toString());
        body.addProperty("targetSlotIndex", slot);
        body.addProperty("overwrite", expectedTarget != null);
        if (expectedTarget != null) body.addProperty("expectedTargetAccountId", expectedTarget.toString());
        body.addProperty("createdBy", actor.toString());
        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder("/api/account/" + source + "/clone")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        requireSuccess(response);
        UUID id = UUID.fromString(JsonParser.parseString(response.body()).getAsJsonObject()
            .getAsJsonObject("account").get("uuid").getAsString());
        AccountModel result = accounts.findByUuid(id);
        if (result == null) throw new IOException("Cloned account could not be loaded: " + id);
        return result;
    }

    /** クエリ値をUTF-8でエンコードします。 */
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** 割り込み状態を維持して共有HTTPクライアントで通信します。 */
    private static HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Account operation interrupted", exception);
        }
    }

    /** APIエラーを成功として扱わず呼出元へ返します。 */
    private static void requireSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        String code = "";
        try {
            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            if (body.has("code")) code = body.get("code").getAsString();
        } catch (RuntimeException ignored) { /* HTML等のエラー応答ではHTTP状態のみを保持します。 */ }
        throw new AccountOperationException(response.statusCode(), code);
    }

    /** APIが明示的に拒否した状態を保持します。レスポンス本文全体は公開しません。 */
    public static final class AccountOperationException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String code;
        /**
         * APIの拒否応答を作成します。
         * @param status HTTP状態
         * @param code APIエラーコード
         */
        public AccountOperationException(int status, String code) {
            super("Account API returned HTTP " + status + " (" + code + ")");
            this.code = code;
        }
        /** @return APIが返した機械判定用エラーコード */
        public String code() { return code; }
    }
}
