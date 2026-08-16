package io.github.maaasu.astralRecord.feature.market.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
import io.github.maaasu.astralRecord.feature.market.model.MarketCancelRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingCreateRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingQuery;
import io.github.maaasu.astralRecord.feature.market.model.MarketPriceQuote;
import io.github.maaasu.astralRecord.feature.market.model.MarketPriceQuoteRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketPurchaseRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AstralRecord API の market エンドポイントと通信し、読み取り結果を短時間キャッシュする repository です。
 */
public class MarketRepository {
    private static final Duration LIST_TTL = Duration.ofSeconds(15);
    private static final Duration DETAIL_TTL = Duration.ofSeconds(30);
    private static final Duration QUOTE_TTL = Duration.ofSeconds(5);

    private final Map<String, MarketCacheEntry<List<MarketListing>>> listingListCache = new ConcurrentHashMap<>();
    private final Map<UUID, MarketCacheEntry<MarketListing>> listingCache = new ConcurrentHashMap<>();
    private final Map<UUID, MarketCacheEntry<MarketAccountSummary>> summaryCache = new ConcurrentHashMap<>();
    private final Map<String, MarketCacheEntry<MarketPriceQuote>> quoteCache = new ConcurrentHashMap<>();

    /**
     * マーケット出品一覧を取得します。短時間キャッシュにより GUI ページングなどの連続参照を抑制します。
     *
     * @param query 検索条件
     * @return 出品一覧
     */
    public @NotNull List<MarketListing> findListings(@NotNull MarketListingQuery query) {
        String queryString = query.toQueryString();
        String path = "/api/market/listings?" + queryString;
        return cached(listingListCache, queryString, LIST_TTL, () -> {
            JsonArray array = getJsonArray(path);
            List<MarketListing> result = new ArrayList<>();
            for (var element : array) {
                if (element.isJsonObject()) {
                    MarketListing listing = parseListing(element.getAsJsonObject());
                    result.add(listing);
                    listingCache.put(listing.listingId(), MarketCacheEntry.of(listing, DETAIL_TTL));
                }
            }
            return List.copyOf(result);
        });
    }

    /**
     * マーケット出品を 1 件取得します。
     *
     * @param listingId 出品 ID
     * @return 出品。存在しない場合は空
     */
    public @NotNull Optional<MarketListing> findListing(@NotNull UUID listingId) {
        MarketCacheEntry<MarketListing> cached = listingCache.get(listingId);
        if (cached != null && cached.isAlive()) {
            return Optional.of(cached.value());
        }

        String path = "/api/market/listings/" + listingId;
        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder(path).GET().build(), path);
        if (response.statusCode() == 404) {
            listingCache.remove(listingId);
            return Optional.empty();
        }
        ensureStatus(response, 200, "GET " + path);
        MarketListing listing = parseListing(JsonParser.parseString(response.body()).getAsJsonObject());
        listingCache.put(listingId, MarketCacheEntry.of(listing, DETAIL_TTL));
        return Optional.of(listing);
    }

    /**
     * アカウント単位のマーケット概要を取得します。
     *
     * @param accountId アカウント ID
     * @return マーケット概要。アカウントが存在しない場合は空
     */
    public @NotNull Optional<MarketAccountSummary> findAccountSummary(@NotNull UUID accountId) {
        MarketCacheEntry<MarketAccountSummary> cached = summaryCache.get(accountId);
        if (cached != null && cached.isAlive()) {
            return Optional.of(cached.value());
        }

        String path = "/api/market/accounts/" + accountId + "/summary";
        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder(path).GET().build(), path);
        if (response.statusCode() == 404) {
            summaryCache.remove(accountId);
            return Optional.empty();
        }
        ensureStatus(response, 200, "GET " + path);
        MarketAccountSummary summary = parseSummary(JsonParser.parseString(response.body()).getAsJsonObject());
        summaryCache.put(accountId, MarketCacheEntry.of(summary, LIST_TTL));
        return Optional.of(summary);
    }

    /**
     * 出品予定商品の相場見積を取得します。価格入力中に連続呼び出しされるため短い TTL でキャッシュします。
     *
     * @param request 相場見積リクエスト
     * @return 相場見積。対象が存在しない場合は空
     */
    public @NotNull Optional<MarketPriceQuote> createPriceQuote(@NotNull MarketPriceQuoteRequest request) {
        String cacheKey = quoteCacheKey(request);
        MarketCacheEntry<MarketPriceQuote> cached = quoteCache.get(cacheKey);
        if (cached != null && cached.isAlive()) {
            return Optional.of(cached.value());
        }

        String path = "/api/market/price-quote";
        HttpResponse<String> response = post(path, priceQuoteBody(request));
        if (response.statusCode() == 404) {
            quoteCache.remove(cacheKey);
            return Optional.empty();
        }
        ensureStatus(response, 200, "POST " + path);
        MarketPriceQuote quote = parseQuote(JsonParser.parseString(response.body()).getAsJsonObject());
        quoteCache.put(cacheKey, MarketCacheEntry.of(quote, QUOTE_TTL));
        return Optional.of(quote);
    }

    /**
     * マーケット出品を作成し、関連キャッシュを破棄します。
     *
     * @param request 出品作成リクエスト
     * @return 作成済み出品
     */
    public @NotNull MarketListing createListing(@NotNull MarketListingCreateRequest request) {
        String path = "/api/market/listings";
        HttpResponse<String> response = post(path, listingBody(request));
        ensureStatus(response, 201, "POST " + path);
        MarketListing listing = parseListing(JsonParser.parseString(response.body()).getAsJsonObject());
        invalidateSeller(listing.sellerAccountId());
        listingCache.put(listing.listingId(), MarketCacheEntry.of(listing, DETAIL_TTL));
        return listing;
    }

    /**
     * マーケット出品を購入確定し、出品者・購入者・出品一覧キャッシュを破棄します。
     *
     * @param listingId 出品 ID
     * @param request 購入リクエスト
     * @return 約定情報
     */
    public @NotNull MarketTransaction purchase(@NotNull UUID listingId, @NotNull MarketPurchaseRequest request) {
        String path = "/api/market/listings/" + listingId + "/purchase";
        HttpResponse<String> response = post(path, purchaseBody(request));
        ensureStatus(response, 200, "POST " + path);
        MarketTransaction transaction = parseTransaction(JsonParser.parseString(response.body()).getAsJsonObject());
        invalidateSeller(transaction.sellerAccountId());
        summaryCache.remove(transaction.buyerAccountId());
        listingCache.remove(listingId);
        return transaction;
    }

    /**
     * マーケット出品をキャンセルし、出品者キャッシュを破棄します。
     *
     * @param listingId 出品 ID
     * @param request キャンセルリクエスト
     * @return 更新後出品
     */
    public @NotNull MarketListing cancel(@NotNull UUID listingId, @NotNull MarketCancelRequest request) {
        String path = "/api/market/listings/" + listingId + "/cancel";
        HttpResponse<String> response = post(path, cancelBody(request));
        ensureStatus(response, 200, "POST " + path);
        MarketListing listing = parseListing(JsonParser.parseString(response.body()).getAsJsonObject());
        invalidateSeller(listing.sellerAccountId());
        listingCache.put(listingId, MarketCacheEntry.of(listing, DETAIL_TTL));
        return listing;
    }

    /**
     * すべてのマーケットキャッシュを破棄します。
     */
    public void clearCache() {
        listingListCache.clear();
        listingCache.clear();
        summaryCache.clear();
        quoteCache.clear();
    }

    private void invalidateSeller(@NotNull UUID sellerAccountId) {
        listingListCache.clear();
        quoteCache.clear();
        summaryCache.remove(sellerAccountId);
    }

    private <T> T cached(
        Map<String, MarketCacheEntry<T>> cache,
        String key,
        Duration ttl,
        CacheLoader<T> loader
    ) {
        MarketCacheEntry<T> current = cache.get(key);
        if (current != null && current.isAlive()) {
            return current.value();
        }
        T loaded = loader.load();
        cache.put(key, MarketCacheEntry.of(loaded, ttl));
        return loaded;
    }

    private JsonArray getJsonArray(@NotNull String path) {
        HttpResponse<String> response = send(ApiRequestUtil.buildRequestBuilder(path).GET().build(), path);
        ensureStatus(response, 200, "GET " + path);
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private HttpResponse<String> post(@NotNull String path, @NotNull JsonObject body) {
        HttpRequest request = ApiRequestUtil.buildRequestBuilder(path)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return send(request, path);
    }

    private HttpResponse<String> send(@NotNull HttpRequest request, @NotNull String path) {
        try {
            var client = ApiRequestUtil.buildClient();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to request " + path, e);
        }
    }

    private void ensureStatus(@NotNull HttpResponse<String> response, int expected, @NotNull String operation) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(operation + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
    }

    private JsonObject listingBody(@NotNull MarketListingCreateRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("sellerAccountId", request.sellerAccountId().toString());
        addUuid(body, "sourceInventoryEntryId", request.sourceInventoryEntryId());
        body.addProperty("itemCategory", request.itemCategory());
        body.addProperty("itemId", request.itemId());
        addString(body, "instanceType", request.instanceType());
        addUuid(body, "instanceId", request.instanceId());
        body.addProperty("quantity", request.quantity());
        body.addProperty("currencyId", request.currencyId());
        body.addProperty("unitPrice", request.unitPrice());
        if (request.expiresAt() != null) {
            body.addProperty("expiresAt", request.expiresAt().toString());
        }
        body.addProperty("createdBy", request.createdBy().toString());
        return body;
    }

    private JsonObject priceQuoteBody(@NotNull MarketPriceQuoteRequest request) {
        JsonObject body = new JsonObject();
        addUuid(body, "accountId", request.accountId());
        body.addProperty("itemCategory", request.itemCategory());
        body.addProperty("itemId", request.itemId());
        addString(body, "instanceType", request.instanceType());
        addUuid(body, "instanceId", request.instanceId());
        body.addProperty("quantity", request.quantity());
        if (request.unitPrice() != null) {
            body.addProperty("unitPrice", request.unitPrice());
        }
        return body;
    }

    private JsonObject purchaseBody(@NotNull MarketPurchaseRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("buyerAccountId", request.buyerAccountId().toString());
        body.addProperty("idempotencyKey", request.idempotencyKey());
        body.addProperty("updatedBy", request.updatedBy().toString());
        return body;
    }

    private JsonObject cancelBody(@NotNull MarketCancelRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("sellerAccountId", request.sellerAccountId().toString());
        addString(body, "reason", request.reason());
        body.addProperty("updatedBy", request.updatedBy().toString());
        return body;
    }

    private String quoteCacheKey(@NotNull MarketPriceQuoteRequest request) {
        return String.join("|",
            request.accountId() == null ? "" : request.accountId().toString(),
            request.itemCategory(),
            request.itemId(),
            request.instanceType() == null ? "" : request.instanceType(),
            request.instanceId() == null ? "" : request.instanceId().toString(),
            Long.toString(request.quantity()),
            request.unitPrice() == null ? "" : request.unitPrice().toString()
        );
    }

    private MarketListing parseListing(@NotNull JsonObject obj) {
        return new MarketListing(
            uuid(obj, "listingId"),
            uuid(obj, "sellerAccountId"),
            string(obj, "sellerAccountName", ""),
            nullableUuid(obj, "buyerAccountId"),
            nullableUuid(obj, "sourceInventoryEntryId"),
            string(obj, "itemCategory", ""),
            string(obj, "itemId", ""),
            nullableString(obj, "instanceType"),
            nullableUuid(obj, "instanceId"),
            longValue(obj, "quantity", 0),
            string(obj, "currencyId", ""),
            longValue(obj, "unitPrice", 0),
            longValue(obj, "totalPrice", 0),
            longValue(obj, "priceFloor", 0),
            nullableLong(obj, "referenceUnitPrice"),
            nullableDouble(obj, "priceDeviationRate"),
            string(obj, "priceConfidence", "LOW"),
            nullableString(obj, "valuationSignature"),
            nullableString(obj, "valuationSnapshotJson"),
            string(obj, "status", ""),
            nullableString(obj, "statusReason"),
            instant(obj, "listedAt"),
            instant(obj, "expiresAt"),
            nullableInstant(obj, "soldAt"),
            nullableInstant(obj, "canceledAt"),
            intValue(obj, "version", 1),
            instant(obj, "createdAt"),
            instant(obj, "updatedAt")
        );
    }

    private MarketPriceQuote parseQuote(@NotNull JsonObject obj) {
        return new MarketPriceQuote(
            string(obj, "itemCategory", ""),
            string(obj, "itemId", ""),
            nullableString(obj, "instanceType"),
            nullableUuid(obj, "instanceId"),
            longValue(obj, "sellPrice", 0),
            longValue(obj, "suggestedUnitPrice", 0),
            nullableLong(obj, "referenceUnitPrice"),
            intValue(obj, "sampleCount", 0),
            string(obj, "referenceScope", ""),
            string(obj, "confidence", "LOW"),
            longValue(obj, "allowedMinUnitPrice", 0),
            longValue(obj, "allowedMaxUnitPrice", 0),
            string(obj, "judgement", ""),
            nullableString(obj, "valuationSignature"),
            nullableDouble(obj, "rollQualityScore"),
            nullableString(obj, "rollQualityBucket"),
            instant(obj, "evaluatedAt")
        );
    }

    private MarketAccountSummary parseSummary(@NotNull JsonObject obj) {
        return new MarketAccountSummary(
            uuid(obj, "accountId"),
            intValue(obj, "activeListingCount", 0),
            intValue(obj, "maxActiveListingCount", 0),
            intValue(obj, "usedListingSlotCount", intValue(obj, "activeListingCount", 0)),
            intValue(obj, "maxListingSlotCount", intValue(obj, "maxActiveListingCount", 0)),
            intValue(obj, "completedTradeCount", 0),
            string(obj, "tier", "T0"),
            nullableInstant(obj, "suspendedUntil"),
            instant(obj, "updatedAt")
        );
    }

    private MarketTransaction parseTransaction(@NotNull JsonObject obj) {
        return new MarketTransaction(
            uuid(obj, "transactionId"),
            uuid(obj, "listingId"),
            uuid(obj, "sellerAccountId"),
            uuid(obj, "buyerAccountId"),
            string(obj, "itemCategory", ""),
            string(obj, "itemId", ""),
            nullableString(obj, "instanceType"),
            nullableUuid(obj, "instanceId"),
            longValue(obj, "quantity", 0),
            string(obj, "currencyId", ""),
            longValue(obj, "unitPrice", 0),
            longValue(obj, "totalPrice", 0),
            longValue(obj, "feeAmount", 0),
            longValue(obj, "sellerProceeds", 0),
            uuidList(obj, "affectedInventoryEntryIds"),
            instant(obj, "completedAt")
        );
    }

    private UUID uuid(@NotNull JsonObject obj, @NotNull String key) {
        return UUID.fromString(string(obj, key, "00000000-0000-0000-0000-000000000000"));
    }

    private @Nullable UUID nullableUuid(@NotNull JsonObject obj, @NotNull String key) {
        String value = nullableString(obj, key);
        return value == null ? null : UUID.fromString(value);
    }

    private String string(@NotNull JsonObject obj, @NotNull String key, @NotNull String fallback) {
        String value = nullableString(obj, key);
        return value == null ? fallback : value;
    }

    private @Nullable String nullableString(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private long longValue(@NotNull JsonObject obj, @NotNull String key, long fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : fallback;
    }

    private @Nullable Long nullableLong(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : null;
    }

    private int intValue(@NotNull JsonObject obj, @NotNull String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private @Nullable Double nullableDouble(@NotNull JsonObject obj, @NotNull String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : null;
    }

    private @NotNull List<UUID> uuidList(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>();
        for (var element : obj.getAsJsonArray(key)) {
            if (element.isJsonNull()) {
                continue;
            }
            try {
                result.add(UUID.fromString(element.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // API の追加フィールドに不正値が混ざっても、約定結果自体は扱えるようにします。
            }
        }
        return List.copyOf(result);
    }

    private Instant instant(@NotNull JsonObject obj, @NotNull String key) {
        Instant parsed = nullableInstant(obj, key);
        return parsed == null ? Instant.EPOCH : parsed;
    }

    private @Nullable Instant nullableInstant(@NotNull JsonObject obj, @NotNull String key) {
        String value = nullableString(obj, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC);
            }
        }
    }

    private void addString(@NotNull JsonObject body, @NotNull String key, @Nullable String value) {
        if (value != null) {
            body.addProperty(key, value);
        }
    }

    private void addUuid(@NotNull JsonObject body, @NotNull String key, @Nullable UUID value) {
        if (value != null) {
            body.addProperty(key, value.toString());
        }
    }

    private interface CacheLoader<T> {
        T load();
    }
}
