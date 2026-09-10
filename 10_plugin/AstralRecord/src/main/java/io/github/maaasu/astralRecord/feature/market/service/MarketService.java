package io.github.maaasu.astralRecord.feature.market.service;

import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
import io.github.maaasu.astralRecord.feature.market.model.MarketCancelRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingCreateRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingQuery;
import io.github.maaasu.astralRecord.feature.market.model.MarketPriceQuote;
import io.github.maaasu.astralRecord.feature.market.model.MarketPriceQuoteRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketProceedsClaim;
import io.github.maaasu.astralRecord.feature.market.model.MarketProceedsClaimRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketPurchaseRequest;
import io.github.maaasu.astralRecord.feature.market.model.MarketTransaction;
import io.github.maaasu.astralRecord.feature.market.repository.MarketRepository;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * マーケット機能から利用する API 集約サービスです。
 * 基本判定は API 側に委譲し、Plugin 側では repository のキャッシュを通じて参照負荷を抑えます。
 */
public class MarketService {
    private final MarketRepository repository;

    public MarketService(@NotNull MarketRepository repository) {
        this.repository = repository;
    }

    /**
     * 出品一覧を取得します。
     *
     * @param query 検索条件
     * @return 出品一覧
     */
    public @NotNull List<MarketListing> findListings(@NotNull MarketListingQuery query) {
        return repository.findListings(query);
    }

    /**
     * 出品を 1 件取得します。
     *
     * @param listingId 出品 ID
     * @return 出品。存在しない場合は空
     */
    public @NotNull Optional<MarketListing> findListing(@NotNull UUID listingId) {
        return repository.findListing(listingId);
    }

    /**
     * アカウントのマーケット概要を取得します。
     *
     * @param accountId アカウント ID
     * @return 概要。アカウントが存在しない場合は空
     */
    public @NotNull Optional<MarketAccountSummary> findAccountSummary(@NotNull UUID accountId) {
        return repository.findAccountSummary(accountId);
    }

    /**
     * 相場見積を取得します。
     *
     * @param request 相場見積リクエスト
     * @return 見積。対象が存在しない場合は空
     */
    public @NotNull Optional<MarketPriceQuote> createPriceQuote(@NotNull MarketPriceQuoteRequest request) {
        return repository.createPriceQuote(request);
    }

    /**
     * 出品を作成します。API 側の価格ガード・出品制限判定を正として扱います。
     *
     * @param request 出品作成リクエスト
     * @return 作成済み出品
     */
    public @NotNull MarketListing createListing(@NotNull MarketListingCreateRequest request) {
        return repository.createListing(request);
    }

    /**
     * 応答未受信の出品作成について、SQL に確定済みの結果を照会します。
     *
     * @param operationId 出品作成操作 ID
     * @param sellerAccountId 出品者アカウント ID
     * @return 確定済み出品。未確定なら空
     */
    public @NotNull Optional<MarketListing> findCreateListingResult(
        @NotNull UUID operationId,
        @NotNull UUID sellerAccountId
    ) {
        return repository.findCreateListingResult(operationId, sellerAccountId);
    }

    /**
     * 出品を購入します。
     *
     * @param listingId 出品 ID
     * @param request 購入リクエスト
     * @return 約定情報
     */
    public @NotNull MarketTransaction purchase(@NotNull UUID listingId, @NotNull MarketPurchaseRequest request) {
        return repository.purchase(listingId, request);
    }

    /**
     * 出品をキャンセルします。
     *
     * @param listingId 出品 ID
     * @param request キャンセルリクエスト
     * @return 更新後出品
     */
    public @NotNull MarketListing cancel(@NotNull UUID listingId, @NotNull MarketCancelRequest request) {
        return repository.cancel(listingId, request);
    }

    /**
     * 応答不明となった出品取消の SQL 確定結果を照会します。
     *
     * @param listingId 出品 ID
     * @param sellerAccountId 出品者アカウント ID
     * @param idempotencyKey 取消要求と同じ冪等キー
     * @return 確定済み取消。未確定の場合は空
     */
    public @NotNull Optional<MarketListing> findCancelResult(
        @NotNull UUID listingId,
        @NotNull UUID sellerAccountId,
        @NotNull String idempotencyKey
    ) {
        return repository.findCancelResult(listingId, sellerAccountId, idempotencyKey);
    }

    /**
     * 売却済み出品の売上を受け取り、出品枠を解放します。
     *
     * @param listingId 受取対象の出品 ID
     * @param request 出品者・再送用冪等キー・更新者
     * @return 受取金額と再同期対象の通貨 entry
     */
    public @NotNull MarketProceedsClaim claimProceeds(
        @NotNull UUID listingId,
        @NotNull MarketProceedsClaimRequest request
    ) {
        return repository.claimProceeds(listingId, request);
    }

    /**
     * Plugin 側マーケットキャッシュを全破棄します。
     */
    public void clearCache() {
        repository.clearCache();
    }
}
