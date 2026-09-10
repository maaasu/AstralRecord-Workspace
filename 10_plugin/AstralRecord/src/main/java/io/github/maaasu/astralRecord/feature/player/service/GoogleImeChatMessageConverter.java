package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * ローマ字をかなへ変換した後、Google CGI APIでかな漢字変換するチャット変換器です。
 */
public final class GoogleImeChatMessageConverter implements ChatMessageConverter {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

    /**
     * ローマ字チャットを変換します。外部変換に失敗した場合はかな変換結果を返します。
     *
     * @param message 正規化済みのチャット本文
     * @return 変換済み本文を返すFuture
     */
    @Override
    public @NotNull CompletableFuture<String> convert(@NotNull String message) {
        ConfigProperties config = ConfigProperties.getInstance();
        String bypassMarker = config.getChatRomajiBypassMarker();
        if (!bypassMarker.isEmpty() && message.startsWith(bypassMarker)) {
            return CompletableFuture.completedFuture(message.substring(bypassMarker.length()).trim());
        }
        if (!config.isChatRomajiConversionEnabled() || containsJapanese(message) || !containsAsciiLetter(message)) {
            return CompletableFuture.completedFuture(message);
        }

        String kana = RomajiKanaConverter.convert(message);
        if (!config.isChatKanjiConversionEnabled() || !containsHiragana(kana)) {
            return CompletableFuture.completedFuture(kana);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(buildRequestUri(config.getChatGoogleImeEndpoint(), kana))
                .GET()
                .timeout(Duration.ofMillis(config.getChatGoogleImeTimeoutMillis()))
                .header("Accept", "application/json")
                .build();
            return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200 ? parseFirstCandidates(response.body()) : kana)
                .exceptionally(ignored -> kana);
        } catch (IllegalArgumentException ignored) {
            return CompletableFuture.completedFuture(kana);
        }
    }

    private static @NotNull URI buildRequestUri(@NotNull String endpoint, @NotNull String kana) {
        String separator = endpoint.contains("?") ? "&" : "?";
        String query = "langpair=" + URLEncoder.encode("ja-Hira|ja", StandardCharsets.UTF_8)
            + "&text=" + URLEncoder.encode(kana, StandardCharsets.UTF_8);
        return URI.create(endpoint + separator + query);
    }

    private static @NotNull String parseFirstCandidates(@NotNull String responseBody) {
        JsonArray phrases = JsonParser.parseString(responseBody).getAsJsonArray();
        StringBuilder converted = new StringBuilder();
        for (JsonElement phraseElement : phrases) {
            JsonArray phrase = phraseElement.getAsJsonArray();
            if (phrase.size() < 2 || !phrase.get(1).isJsonArray() || phrase.get(1).getAsJsonArray().isEmpty()) {
                throw new IllegalArgumentException("Google IME response does not contain a conversion candidate");
            }
            converted.append(phrase.get(1).getAsJsonArray().get(0).getAsString());
        }
        if (converted.isEmpty()) {
            throw new IllegalArgumentException("Google IME response is empty");
        }
        return converted.toString();
    }

    private static boolean containsAsciiLetter(@NotNull String value) {
        return value.chars().anyMatch(character -> (character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z'));
    }

    private static boolean containsJapanese(@NotNull String value) {
        return value.codePoints().anyMatch(character -> (character >= 0x3040 && character <= 0x30FF)
            || (character >= 0x3400 && character <= 0x9FFF));
    }

    private static boolean containsHiragana(@NotNull String value) {
        return value.codePoints().anyMatch(character -> character >= 0x3040 && character <= 0x309F);
    }
}
