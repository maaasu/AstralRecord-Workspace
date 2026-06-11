package io.github.maaasu.astralRecord.feature.market.model;

import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record MarketListingQuery(
    @Nullable UUID sellerAccountId,
    @Nullable String itemCategory,
    @Nullable String itemId,
    @Nullable String status,
    @Nullable Long minPrice,
    @Nullable Long maxPrice,
    @Nullable String sort,
    int page,
    int pageSize
) {
    public static MarketListingQuery activeFirstPage() {
        return new MarketListingQuery(null, null, null, "ACTIVE", null, null, "listed_desc", 1, 50);
    }

    public String toQueryString() {
        List<String> params = new ArrayList<>();
        add(params, "seller_account_id", sellerAccountId == null ? null : sellerAccountId.toString());
        add(params, "item_category", itemCategory);
        add(params, "item_id", itemId);
        add(params, "status", status);
        add(params, "min_price", minPrice == null ? null : minPrice.toString());
        add(params, "max_price", maxPrice == null ? null : maxPrice.toString());
        add(params, "sort", sort);
        add(params, "page", Integer.toString(Math.max(1, page)));
        add(params, "page_size", Integer.toString(Math.max(1, Math.min(100, pageSize))));
        return String.join("&", params);
    }

    private static void add(List<String> params, String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        params.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"));
    }
}
