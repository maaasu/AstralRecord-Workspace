package io.github.maaasu.astralRecord.feature.builder.repository;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** dev サーバー専用の建築インポート API クライアント。非同期タスクから使用します。 */
public final class BuilderSchematicRepository {
    private static final String ROOT = "/api/builder-schematics";
    private static final Gson GSON = new Gson();
    private final String devKey;

    /** @param devKey API と共有する dev 専用キー */
    public BuilderSchematicRepository(String devKey) {
        this.devKey = devKey;
    }

    /** @param userUuid 登録リンクを受け取る AstralRecord ユーザーの UUID
     * @return 本人用の一回限りのトークン */
    public String issueTicket(UUID userUuid) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("userUuid", userUuid.toString());
        byte[] response = send(post("/tickets", body), 201);
        String token = GSON.fromJson(new String(response, StandardCharsets.UTF_8), Ticket.class).token();
        if (token == null || !token.matches("[A-Za-z0-9_-]{24,128}")) {
            throw new IOException("Invalid schematic ticket response");
        }
        return token;
    }

    /** @return dev で検証を待つファイル */
    public List<Entry> pending() throws IOException, InterruptedException {
        return entries("/pending");
    }

    /** @param userUuid AstralRecord ユーザーの UUID
     * @return 本人の登録済みファイル */
    public List<Entry> registered(UUID userUuid) throws IOException, InterruptedException {
        return entries("/registered?userUuid=" + userUuid);
    }

    /** @param id API が発行した建築 ID
     * @param owner 登録済みファイルの所有者。検証待ちファイルの取得時は null
     * @return 圧縮済みの .schem データ。検証待ち一覧から消えた場合は null */
    public byte[] read(UUID id, UUID owner) throws IOException, InterruptedException {
        String query = owner == null ? "" : "?userUuid=" + owner;
        HttpResponse<byte[]> response = send(request("/files/" + id + query).GET().build());
        return owner == null && response.statusCode() == 404 ? null : requireStatus(response, 200);
    }

    /** @param id API が発行した建築 ID
     * @param error 検証失敗の理由コード。成功時は null */
    public void decide(UUID id, String error) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("accepted", error == null);
        if (error != null) body.addProperty("error", error);
        HttpResponse<byte[]> response = send(post("/decisions/" + id, body));
        if (response.statusCode() != 404) requireStatus(response, 204);
    }

    private List<Entry> entries(String path) throws IOException, InterruptedException {
        byte[] response = send(request(path).GET().build(), 200);
        Entry[] items = GSON.fromJson(new String(response, StandardCharsets.UTF_8), Entry[].class);
        return List.of(items);
    }

    private HttpRequest post(String path, JsonObject body) {
        return request(path).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    private HttpRequest.Builder request(String path) {
        return ApiRequestUtil.buildRequestBuilder(ROOT + path)
                .timeout(Duration.ofSeconds(10))
                .header("X-Builder-Dev-Key", devKey);
    }

    private byte[] send(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        return requireStatus(send(request), expectedStatus);
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] requireStatus(HttpResponse<byte[]> response, int expectedStatus) throws IOException {
        if (response.statusCode() != expectedStatus) {
            throw new IOException("Schematic API status " + response.statusCode());
        }
        return response.body();
    }

    /** API の一覧に含まれる、建築 ID と表示名。 */
    public record Entry(UUID id, String name) { }

    private record Ticket(String token) { }
}
