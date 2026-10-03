package io.github.maaasu.astralRecord.feature.pet.repository;

import com.google.gson.*;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.net.http.*;
import java.io.IOException;
import java.util.UUID;

/** ペットAPIの通信だけを担当します。Bukkitメインスレッドから呼び出してはいけません。 */
public final class PetRepository {
    /**
     * 単一ペットマスターを取得します。HTTP失敗は例外です。
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject master() { return request("GET","/api/pet/master",null); }
    /**
     * 所有個体と装備選択を取得します。
     * @param accountId 所有アカウントID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject account(UUID accountId) { return request("GET",path(accountId),null); }
    /**
     * 所有者限定の単一個体を取得します。非同期専用です。
     * @param accountId 所有アカウントID
     * @param instanceId 個体ID
     * @return APIの所有個体応答。取得失敗時は例外
     */
    public JsonObject instance(UUID accountId, UUID instanceId) {
        return request("GET", "/api/pet/instances/" + instanceId + "?account_id=" + accountId, null);
    }
    /**
     * 同じoperationIdの確定結果を照会します。未確定はnullです。
     * @param accountId 所有アカウントID
     * @param operationId 再送でも維持する操作ID
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject operation(UUID accountId,UUID operationId) {
        try { return request("GET",path(accountId)+"/operations/"+operationId,null); }
        catch (RejectedOperation failure) { if(failure.status==404) return null; throw failure; }
    }
    /**
     * アカウント所有の原子操作を実行します。bodyにはoperationIdを含めます。
     * @param accountId 所有アカウントID
     * @param suffix アカウントAPIに続く操作パス
     * @param method HTTPメソッド
     * @param body 操作要求のJSON
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public JsonObject mutate(UUID accountId,String suffix,String method,JsonObject body) {
        return request(method,path(accountId)+suffix,body);
    }
    private static String path(UUID id) { return "/api/pet/accounts/"+id; }
    private JsonObject request(String method,String path,JsonObject body) {
        try {
            HttpRequest.Builder builder=ApiRequestUtil.buildRequestBuilder(path);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
            if(body!=null) builder.header("Content-Type","application/json");
            HttpResponse<String> response=ApiRequestUtil.sharedClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200 || response.statusCode()>=300) {
                if(response.statusCode()>=400 && response.statusCode()<500)
                    throw new RejectedOperation(response.statusCode(),response.body());
                throw new IOException("Pet API HTTP "+response.statusCode());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Pet API interrupted",failure);
        } catch(IOException failure) { throw new IllegalStateException("Pet API transport failed",failure); }
    }
    /** APIが操作を拒否した確定エラーです。内部応答をプレイヤーへ直接表示しません。 */
    public static final class RejectedOperation extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID=1L;
        public final int status;
        /** HTTP状態と診断用の応答を保持します。 */
        public RejectedOperation(int status,String response) { super(response);this.status=status; }
        /**
         * JSONの短い拒否コードだけを返します。HTMLや内部診断本文は通知へ渡しません。
         * @return API拒否コード。解釈できない応答はoperation_rejected
         */
        public String failureCode() {
            try {
                JsonElement value = JsonParser.parseString(getMessage()).getAsJsonObject().get("failure");
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String code = value.getAsString();
                    if (code.matches("[a-z_]{1,64}")) return code;
                }
            } catch (RuntimeException ignored) { }
            return "operation_rejected";
        }
    }
}
