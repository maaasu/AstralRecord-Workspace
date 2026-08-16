using System.Data;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class MarketRepository(
    AstralRecordDbContext dbContext,
    IMarketPriceService marketPriceService,
    IMarketListingLimitService listingLimitService
) : IMarketRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;
    public async Task<IReadOnlyList<MarketListingResponse>> GetListingsAsync(MarketListingQuery query)
    {
        var page = Math.Max(1, query.Page);
        var pageSize = Math.Clamp(query.PageSize, 1, 100);
        var status = string.IsNullOrWhiteSpace(query.Status) ? "ACTIVE" : query.Status;

        var listings = dbContext.MarketListings
            .AsNoTracking()
            .Where(listing => !listing.IsDeleted);

        if (!string.Equals(status, "ALL", StringComparison.OrdinalIgnoreCase))
            listings = listings.Where(listing => listing.Status == status);
        if (query.SellerAccountId.HasValue)
            listings = listings.Where(listing => listing.SellerAccountId == query.SellerAccountId.Value);
        if (!string.IsNullOrWhiteSpace(query.ItemCategory))
            listings = listings.Where(listing => listing.ItemCategory == query.ItemCategory);
        if (!string.IsNullOrWhiteSpace(query.ItemId))
            listings = listings.Where(listing => listing.ItemId == query.ItemId);
        if (query.MinPrice.HasValue)
            listings = listings.Where(listing => listing.UnitPrice >= query.MinPrice.Value);
        if (query.MaxPrice.HasValue)
            listings = listings.Where(listing => listing.UnitPrice <= query.MaxPrice.Value);

        listings = query.Sort switch
        {
            "price_asc" => listings.OrderBy(listing => listing.UnitPrice).ThenByDescending(listing => listing.ListedAt),
            "price_desc" => listings.OrderByDescending(listing => listing.UnitPrice).ThenByDescending(listing => listing.ListedAt),
            _ => listings.OrderByDescending(listing => listing.ListedAt),
        };

        var result = await listings
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToListAsync();

        var sellerAccountIds = result
            .Select(listing => listing.SellerAccountId)
            .Distinct()
            .ToArray();
        var sellerAccountNames = sellerAccountIds.Length == 0
            ? new Dictionary<Guid, string>()
            : await dbContext.Accounts
                .AsNoTracking()
                .Where(account => !account.IsDeleted && sellerAccountIds.Contains(account.Uuid))
                .ToDictionaryAsync(account => account.Uuid, account => account.AccountName);
        var listingIds = result.Select(listing => listing.ListingId).ToArray();
        var pendingProceedsByListing = await LoadPendingProceedsByListingAsync(listingIds);

        return result
            .Select(listing => MapListing(
                listing,
                sellerAccountNames.GetValueOrDefault(listing.SellerAccountId, string.Empty),
                Array.Empty<Guid>(),
                pendingProceedsByListing.GetValueOrDefault(listing.ListingId)))
            .ToList();
    }

    public async Task<MarketListingResponse?> GetListingAsync(Guid listingId)
    {
        var listing = await dbContext.MarketListings
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.ListingId == listingId && !x.IsDeleted);

        if (listing is null)
            return null;

        var pendingProceedsByListing = await LoadPendingProceedsByListingAsync([listingId]);
        return MapListing(
            listing,
            await GetSellerAccountNameAsync(listing.SellerAccountId),
            Array.Empty<Guid>(),
            pendingProceedsByListing.GetValueOrDefault(listing.ListingId));
    }

    public async Task<MarketAccountSummaryResponse?> GetAccountSummaryAsync(Guid accountId)
    {
        var accountExists = await dbContext.Accounts.AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            return null;

        var state = await EnsureAccountStateAsync(accountId, accountId);
        var activeCount = await CountActiveListingsAsync(accountId);
        var usedSlotCount = await CountUsedListingSlotsAsync(accountId);
        return listingLimitService.BuildSummary(state, activeCount, usedSlotCount);
    }

    public async Task<MarketOperationResult<MarketListingResponse>> CreateListingAsync(MarketListingCreateRequest request)
    {
        if (!IsValidListingPayload(request))
            return MarketOperationResult<MarketListingResponse>.Failure(400, "market.invalid_payload", "Listing payload is invalid.");
        if (!GoldCurrencyBalanceSupport.IsMarketGoldCurrency(request.CurrencyId))
            return MarketOperationResult<MarketListingResponse>.Failure(
                400,
                "market.unsupported_currency",
                "Market listings must use the configured Gold currency.");

        var sellerAccountName = await dbContext.Accounts
            .AsNoTracking()
            .Where(account => account.Uuid == request.SellerAccountId && !account.IsDeleted)
            .Select(account => account.AccountName)
            .FirstOrDefaultAsync();
        if (sellerAccountName is null)
            return MarketOperationResult<MarketListingResponse>.Failure(404, "market.seller_not_found", "Seller account was not found.");

        var listingId = Guid.NewGuid();
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            var committedListing = await dbContext.MarketListings
                .AsNoTracking()
                .FirstOrDefaultAsync(listing => listing.ListingId == listingId && !listing.IsDeleted);
            if (committedListing is not null)
                return MarketOperationResult<MarketListingResponse>.Success(MapListing(
                    committedListing,
                    sellerAccountName,
                    request.SourceEntries.Select(source => source.InventoryEntryId).ToArray(),
                    0L));

            var now = DateTime.UtcNow;
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);

            async Task<MarketOperationResult<MarketListingResponse>> RollbackFailureAsync(
                int statusCode,
                string errorCode,
                string detail)
            {
                await transaction.RollbackAsync();
                dbContext.ChangeTracker.Clear();
                return MarketOperationResult<MarketListingResponse>.Failure(
                    statusCode,
                    errorCode,
                    detail);
            }

            var state = await EnsureAccountStateAsync(request.SellerAccountId, request.CreatedBy);
            var usedSlotCount = await CountUsedListingSlotsAsync(request.SellerAccountId);
            var limit = listingLimitService.ResolveLimit(state.CompletedTradeCount);
            if (usedSlotCount >= limit.MaxActiveListingCount)
                return await RollbackFailureAsync(
                    400,
                    "market.listing_slot_limit_exceeded",
                    "Listing slot limit exceeded. Active, suspended, and unclaimed sold listings use slots.");

            var escrow = await ReserveEscrowAsync(request);
            if (!escrow.Succeeded)
                return await RollbackFailureAsync(escrow.StatusCode, escrow.ErrorCode!, escrow.Detail!);

            var normalizedInstanceType = request.InstanceType?.Trim().ToUpperInvariant();
            var quote = await marketPriceService.CreateQuoteAsync(new MarketPriceQuoteRequest
            {
                AccountId = request.SellerAccountId,
                ItemCategory = request.ItemCategory,
                ItemId = request.ItemId,
                InstanceType = normalizedInstanceType,
                InstanceId = request.InstanceId,
                Quantity = request.Quantity,
                UnitPrice = request.UnitPrice,
            });

            if (quote is null)
                return await RollbackFailureAsync(404, "market.item_not_found", "Item or instance was not found.");
            if (quote.Judgement is "BLOCK_AT_OR_BELOW_SELL_VALUE"
                or "BLOCK_OUT_OF_MARKET_RANGE")
                return await RollbackFailureAsync(400, "market.price_guard_rejected", quote.Judgement);

            long totalPrice;
            try
            {
                totalPrice = checked(request.UnitPrice * request.Quantity);
            }
            catch (OverflowException)
            {
                return await RollbackFailureAsync(400, "market.total_price_overflow", "Total price is too large.");
            }

            var listing = new MarketListingEntity
            {
                ListingId = listingId,
                SellerAccountId = request.SellerAccountId,
                SourceInventoryEntryId = request.SourceEntries[0].InventoryEntryId,
                ItemCategory = request.ItemCategory,
                ItemId = request.ItemId,
                InstanceType = normalizedInstanceType,
                InstanceId = request.InstanceId,
                Quantity = request.Quantity,
                RemainingQuantity = request.Quantity,
                CurrencyId = GoldCurrencyBalanceSupport.MarketCurrencyId,
                UnitPrice = request.UnitPrice,
                TotalPrice = totalPrice,
                PriceFloor = quote.SellPrice,
                ReferenceUnitPrice = quote.ReferenceUnitPrice,
                PriceDeviationRate = quote.ReferenceUnitPrice.HasValue && quote.ReferenceUnitPrice.Value > 0
                    ? Math.Round(request.UnitPrice / (decimal)quote.ReferenceUnitPrice.Value, 6)
                    : null,
                PriceConfidence = quote.Confidence,
                ValuationSignature = quote.ValuationSignature,
                ValuationSnapshotJson = JsonSerializer.Serialize(quote),
                Status = "ACTIVE",
                ListedAt = now,
                ExpiresAt = request.ExpiresAt ?? now.AddDays(7),
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.CreatedBy,
                UpdatedBy = request.CreatedBy,
                IsDeleted = false,
            };
            await dbContext.MarketListings.AddAsync(listing);
            await dbContext.MarketListingSources.AddRangeAsync(request.SourceEntries.Select(source =>
                new MarketListingSourceEntity
                {
                    ListingId = listingId,
                    InventoryEntryId = source.InventoryEntryId,
                    Quantity = source.Quantity,
                }));
            await dbContext.MarketPriceSnapshots.AddAsync(CreateSnapshot(quote, listing.ListingId, null, now));
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();

            return MarketOperationResult<MarketListingResponse>.Success(MapListing(
                listing,
                sellerAccountName,
                request.SourceEntries.Select(source => source.InventoryEntryId).ToArray(),
                0L));
        });
    }

    public async Task<MarketOperationResult<MarketTransactionResponse>> PurchaseListingAsync(Guid listingId, MarketPurchaseRequest request)
    {
        if (request.Quantity < 1L || request.Quantity > int.MaxValue || string.IsNullOrWhiteSpace(request.IdempotencyKey))
            return MarketOperationResult<MarketTransactionResponse>.Failure(
                400,
                "market.invalid_purchase_request",
                "Purchase quantity and idempotency key are required.");

        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transactionScope = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);

            async Task<MarketOperationResult<MarketTransactionResponse>> RollbackFailureAsync(
                int statusCode,
                string errorCode,
                string detail)
            {
                await transactionScope.RollbackAsync();
                dbContext.ChangeTracker.Clear();
                return MarketOperationResult<MarketTransactionResponse>.Failure(statusCode, errorCode, detail);
            }

            var existingTransaction = await dbContext.MarketTransactions
                .AsNoTracking()
                .FirstOrDefaultAsync(transaction =>
                    transaction.BuyerAccountId == request.BuyerAccountId
                    && transaction.IdempotencyKey == request.IdempotencyKey);
            if (existingTransaction is not null)
            {
                await transactionScope.CommitAsync();
                return MarketOperationResult<MarketTransactionResponse>.Success(MapTransaction(existingTransaction));
            }

            var listing = await FindListingForUpdateAsync(listingId);
            if (listing is null)
                return await RollbackFailureAsync(404, "market.listing_not_found", "Listing was not found.");
            if (listing.Status != "ACTIVE")
                return await RollbackFailureAsync(409, "market.listing_not_active", "Listing is not active.");
            if (listing.SellerAccountId == request.BuyerAccountId)
                return await RollbackFailureAsync(400, "market.self_purchase", "Seller cannot buy own listing.");
            if (request.Quantity > listing.RemainingQuantity)
                return await RollbackFailureAsync(409, "market.insufficient_listing_quantity", "Listing does not have enough remaining quantity.");
            if (listing.InstanceId.HasValue && request.Quantity != 1L)
                return await RollbackFailureAsync(400, "market.instance_quantity_invalid", "Instance listings must be purchased one at a time.");
            if (!GoldCurrencyBalanceSupport.IsMarketGoldCurrency(listing.CurrencyId))
                return await RollbackFailureAsync(409, "market.unsupported_currency", "Listing currency is not Gold.");

            var buyerExists = await dbContext.Accounts.AnyAsync(account =>
                account.Uuid == request.BuyerAccountId && !account.IsDeleted);
            if (!buyerExists)
                return await RollbackFailureAsync(404, "market.buyer_not_found", "Buyer account was not found.");

            long totalPrice;
            try
            {
                totalPrice = checked(listing.UnitPrice * request.Quantity);
            }
            catch (OverflowException)
            {
                return await RollbackFailureAsync(400, "market.total_price_overflow", "Purchase total price is too large.");
            }

            var now = DateTime.UtcNow;
            var payment = await DebitGoldAsync(
                request.BuyerAccountId,
                totalPrice,
                request.UpdatedBy,
                now);
            if (!payment.Succeeded)
                return await RollbackFailureAsync(payment.StatusCode, payment.ErrorCode!, payment.Detail!);

            var transfer = await TransferListingItemAsync(
                listing,
                request.Quantity,
                request.BuyerAccountId,
                request.UpdatedBy);
            if (!transfer.Succeeded)
                return await RollbackFailureAsync(transfer.StatusCode, transfer.ErrorCode!, transfer.Detail!);

            var transaction = new MarketTransactionEntity
            {
                TransactionId = Guid.NewGuid(),
                ListingId = listing.ListingId,
                SellerAccountId = listing.SellerAccountId,
                BuyerAccountId = request.BuyerAccountId,
                ItemCategory = listing.ItemCategory,
                ItemId = listing.ItemId,
                InstanceType = listing.InstanceType,
                InstanceId = listing.InstanceId,
                Quantity = request.Quantity,
                CurrencyId = GoldCurrencyBalanceSupport.MarketCurrencyId,
                UnitPrice = listing.UnitPrice,
                TotalPrice = totalPrice,
                FeeAmount = 0,
                SellerProceeds = totalPrice,
                ValuationSignature = listing.ValuationSignature,
                ValuationSnapshotJson = listing.ValuationSnapshotJson,
                IdempotencyKey = request.IdempotencyKey,
                CompletedAt = now,
                CreatedAt = now,
                CreatedBy = request.UpdatedBy,
            };

            listing.BuyerAccountId = request.BuyerAccountId;
            listing.RemainingQuantity -= request.Quantity;
            if (listing.RemainingQuantity == 0L)
            {
                listing.Status = "SOLD";
                listing.SoldAt = now;
            }
            listing.UpdatedAt = now;
            listing.UpdatedBy = request.UpdatedBy;
            listing.Version += 1;

            await dbContext.MarketTransactions.AddAsync(transaction);
            await IncrementTradeCountAsync(listing.SellerAccountId, request.UpdatedBy, now);
            await IncrementTradeCountAsync(request.BuyerAccountId, request.UpdatedBy, now);
            await dbContext.SaveChangesAsync();
            await transactionScope.CommitAsync();

            return MarketOperationResult<MarketTransactionResponse>.Success(MapTransaction(
                transaction,
                payment.Value!.Concat(transfer.Value!).Distinct().ToArray()));
        });
    }

    public async Task<MarketOperationResult<MarketListingResponse>> CancelListingAsync(Guid listingId, MarketCancelRequest request)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transactionScope = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);

            async Task<MarketOperationResult<MarketListingResponse>> RollbackFailureAsync(
                int statusCode,
                string errorCode,
                string detail)
            {
                await transactionScope.RollbackAsync();
                dbContext.ChangeTracker.Clear();
                return MarketOperationResult<MarketListingResponse>.Failure(statusCode, errorCode, detail);
            }

            var listing = await FindListingForUpdateAsync(listingId);
            if (listing is null)
                return await RollbackFailureAsync(404, "market.listing_not_found", "Listing was not found.");
            if (listing.SellerAccountId != request.SellerAccountId)
                return await RollbackFailureAsync(403, "market.not_seller", "Only seller can cancel listing.");
            if (listing.Status is not ("ACTIVE" or "SUSPENDED"))
                return await RollbackFailureAsync(400, "market.cancel_invalid_status", "Listing cannot be canceled.");

            var sellerAccountName = await GetSellerAccountNameAsync(listing.SellerAccountId);
            var sources = await GetListingSourcesAsync(listing.ListingId);
            if (sources.Count == 0)
                return await RollbackFailureAsync(409, "market.source_inventory_missing", "Listing escrow sources are missing.");

            var restore = await RestoreEscrowAsync(listing, sources, request.UpdatedBy);
            if (!restore.Succeeded)
                return await RollbackFailureAsync(restore.StatusCode, restore.ErrorCode!, restore.Detail!);

            var now = DateTime.UtcNow;
            var transactionProceeds = await LoadPendingProceedsByListingAsync([listing.ListingId]);
            var pendingProceeds = transactionProceeds.GetValueOrDefault(listing.ListingId);
            listing.RemainingQuantity = 0L;
            if (pendingProceeds > 0L)
            {
                listing.Status = "SOLD";
                listing.StatusReason = request.Reason ?? "CANCELED_AFTER_PARTIAL_SALE";
                listing.SoldAt = now;
            }
            else
            {
                listing.Status = "CANCELED";
                listing.StatusReason = request.Reason;
                listing.IsDeleted = true;
            }
            listing.CanceledAt = now;
            listing.UpdatedAt = now;
            listing.UpdatedBy = request.UpdatedBy;
            listing.Version += 1;

            await dbContext.SaveChangesAsync();
            await transactionScope.CommitAsync();

            return MarketOperationResult<MarketListingResponse>.Success(MapListing(
                listing,
                sellerAccountName,
                sources.Select(source => source.InventoryEntryId).ToArray(),
                pendingProceeds));
        });
    }

    public async Task<MarketOperationResult<MarketProceedsClaimResponse>> ClaimProceedsAsync(
        Guid listingId,
        MarketProceedsClaimRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.IdempotencyKey) || request.IdempotencyKey.Length > 128)
            return MarketOperationResult<MarketProceedsClaimResponse>.Failure(
                400,
                "market.invalid_claim_request",
                "Claim idempotency key is required.");

        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var transactionScope = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);

            async Task<MarketOperationResult<MarketProceedsClaimResponse>> RollbackFailureAsync(
                int statusCode,
                string errorCode,
                string detail)
            {
                await transactionScope.RollbackAsync();
                dbContext.ChangeTracker.Clear();
                return MarketOperationResult<MarketProceedsClaimResponse>.Failure(statusCode, errorCode, detail);
            }

            var listing = await FindListingForClaimForUpdateAsync(listingId);
            if (listing is null)
                return await RollbackFailureAsync(404, "market.listing_not_found", "Listing was not found.");
            if (listing.SellerAccountId != request.SellerAccountId)
                return await RollbackFailureAsync(403, "market.not_seller", "Only seller can claim listing proceeds.");
            if (listing.IsDeleted)
            {
                if (listing.Status == "SOLD"
                    && string.Equals(
                        listing.ProceedsClaimIdempotencyKey,
                        request.IdempotencyKey,
                        StringComparison.Ordinal)
                    && listing.ProceedsClaimAmount is > 0L
                    && listing.ProceedsClaimAffectedInventoryEntryIdsJson is not null)
                {
                    IReadOnlyList<Guid> affectedInventoryEntryIds;
                    try
                    {
                        affectedInventoryEntryIds = JsonSerializer.Deserialize<List<Guid>>(
                            listing.ProceedsClaimAffectedInventoryEntryIdsJson) ?? [];
                    }
                    catch (JsonException)
                    {
                        return await RollbackFailureAsync(
                            409,
                            "market.claim_receipt_invalid",
                            "Claim receipt is invalid.");
                    }

                    await transactionScope.CommitAsync();
                    return MarketOperationResult<MarketProceedsClaimResponse>.Success(new MarketProceedsClaimResponse
                    {
                        ListingId = listingId,
                        Amount = listing.ProceedsClaimAmount.Value,
                        AffectedInventoryEntryIds = affectedInventoryEntryIds,
                    });
                }

                if (!string.IsNullOrWhiteSpace(listing.ProceedsClaimIdempotencyKey))
                    return await RollbackFailureAsync(
                        409,
                        "market.claim_already_completed",
                        "Listing proceeds were already claimed with another idempotency key.");

                return await RollbackFailureAsync(404, "market.listing_not_found", "Listing was not found.");
            }
            if (listing.Status != "SOLD")
                return await RollbackFailureAsync(400, "market.claim_invalid_status", "Listing proceeds cannot be claimed yet.");

            var proceeds = await dbContext.MarketTransactions
                .Where(transaction => transaction.ListingId == listingId)
                .Select(transaction => transaction.SellerProceeds)
                .ToListAsync();
            long amount;
            try
            {
                amount = proceeds.Aggregate(0L, (sum, value) => checked(sum + value));
            }
            catch (OverflowException)
            {
                return await RollbackFailureAsync(409, "market.proceeds_overflow", "Pending proceeds are too large.");
            }
            if (amount < 1L)
                return await RollbackFailureAsync(409, "market.proceeds_missing", "Listing has no pending proceeds.");

            var now = DateTime.UtcNow;
            var credit = await CreditGoldAsync(listing.SellerAccountId, amount, request.UpdatedBy, now);
            if (!credit.Succeeded)
                return await RollbackFailureAsync(credit.StatusCode, credit.ErrorCode!, credit.Detail!);

            listing.IsDeleted = true;
            listing.StatusReason = "PROCEEDS_CLAIMED";
            listing.ProceedsClaimIdempotencyKey = request.IdempotencyKey;
            listing.ProceedsClaimAmount = amount;
            listing.ProceedsClaimAffectedInventoryEntryIdsJson = JsonSerializer.Serialize(credit.Value!);
            listing.ProceedsClaimedAt = now;
            listing.UpdatedAt = now;
            listing.UpdatedBy = request.UpdatedBy;
            listing.Version += 1;
            await dbContext.SaveChangesAsync();
            await transactionScope.CommitAsync();

            return MarketOperationResult<MarketProceedsClaimResponse>.Success(new MarketProceedsClaimResponse
            {
                ListingId = listingId,
                Amount = amount,
                AffectedInventoryEntryIds = credit.Value!,
            });
        });
    }

    private async Task<MarketOperationResult<bool>> ReserveEscrowAsync(MarketListingCreateRequest request)
    {
        foreach (var source in request.SourceEntries.OrderBy(source => source.InventoryEntryId))
        {
            var entry = await FindInventoryEntryForUpdateAsync(source.InventoryEntryId, includeDeleted: false);
            if (entry is null)
                return MarketOperationResult<bool>.Failure(404, "market.source_inventory_not_found", "Source inventory entry was not found.");

            var inventory = await dbContext.Inventories
                .FirstOrDefaultAsync(x => x.InventoryId == entry.InventoryId && !x.IsDeleted);
            if (inventory is null
                || inventory.AccountId != request.SellerAccountId
                || !inventory.IsEnabled
                || !string.Equals(inventory.InventoryProfile, "GAME", StringComparison.OrdinalIgnoreCase)
                || inventory.InventoryType is not ("BAG" or "HOTBAR"))
                return MarketOperationResult<bool>.Failure(409, "market.source_inventory_owner_mismatch", "Source inventory owner mismatch.");
            if (!KeyComparer.Equals(entry.ItemId, request.ItemId) || !KeyComparer.Equals(entry.ItemCategory, request.ItemCategory))
                return MarketOperationResult<bool>.Failure(400, "market.source_item_mismatch", "Source inventory item mismatch.");

            if (request.InstanceId.HasValue)
            {
                var instanceReservation = await ReserveInstanceAsync(request, entry);
                if (!instanceReservation.Succeeded)
                    return instanceReservation;

                entry.Quantity = 0;
                entry.IsDeleted = true;
                entry.UpdatedAt = DateTime.UtcNow;
                entry.UpdatedBy = request.CreatedBy;
                continue;
            }

            if (entry.InstanceId.HasValue || !string.IsNullOrWhiteSpace(entry.InstanceType))
                return MarketOperationResult<bool>.Failure(400, "market.source_instance_mismatch", "Stack listing source must not be an instance.");
            if (entry.Quantity < source.Quantity)
                return MarketOperationResult<bool>.Failure(400, "market.quantity_shortage", "Source inventory quantity is not enough.");

            entry.Quantity -= source.Quantity;
            entry.UpdatedAt = DateTime.UtcNow;
            entry.UpdatedBy = request.CreatedBy;
            if (entry.Quantity == 0)
                entry.IsDeleted = true;
        }

        return MarketOperationResult<bool>.Success(true);
    }

    private async Task<MarketOperationResult<bool>> ReserveInstanceAsync(
        MarketListingCreateRequest request,
        InventoryEntryEntity sourceEntry)
    {
        var instanceId = request.InstanceId!.Value;
        var instanceType = request.InstanceType!.Trim().ToUpperInvariant();
        if (sourceEntry.Quantity != 1
            || !KeyComparer.Equals(sourceEntry.InstanceType, instanceType)
            || sourceEntry.InstanceId != instanceId)
        {
            return MarketOperationResult<bool>.Failure(
                400,
                "market.source_instance_mismatch",
                "Source inventory entry does not match the listed instance.");
        }
        var hasActiveListing = await MarketListingRangeLock.HasActiveOrSuspendedAsync(
            dbContext,
            instanceType,
            instanceId);

        if (KeyComparer.Equals(instanceType, "EQUIPMENT"))
        {
            var equipment = await FindEquipmentForUpdateAsync(instanceId);
            if (hasActiveListing)
                return MarketOperationResult<bool>.Failure(409, "market.instance_already_listed", "Instance is already listed.");
            if (equipment is null)
                return MarketOperationResult<bool>.Failure(404, "market.instance_not_found", "Equipment instance was not found.");
            if (equipment.AccountId != request.SellerAccountId)
                return MarketOperationResult<bool>.Failure(409, "market.instance_owner_mismatch", "Equipment owner mismatch.");
            if (await IsEquipmentEquippedForUpdateAsync(request.SellerAccountId, instanceId))
                return MarketOperationResult<bool>.Failure(409, "market.instance_equipped", "Equipped equipment cannot be listed.");
            return MarketOperationResult<bool>.Success(true);
        }

        if (KeyComparer.Equals(instanceType, "RUNE"))
        {
            var rune = await dbContext.RuneInstances
                .FirstOrDefaultAsync(x => x.RuneInstanceId == instanceId && !x.IsDeleted);
            if (hasActiveListing)
                return MarketOperationResult<bool>.Failure(409, "market.instance_already_listed", "Instance is already listed.");
            if (rune is null)
                return MarketOperationResult<bool>.Failure(404, "market.instance_not_found", "Rune instance was not found.");
            if (rune.AccountId != request.SellerAccountId)
                return MarketOperationResult<bool>.Failure(409, "market.instance_owner_mismatch", "Rune owner mismatch.");
            return MarketOperationResult<bool>.Success(true);
        }

        return MarketOperationResult<bool>.Failure(400, "market.unsupported_instance_type", "Unsupported instance type.");
    }

    private async Task<EquipmentInstanceEntity?> FindEquipmentForUpdateAsync(Guid equipmentInstanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentInstances
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[equipment_instance] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [equipment_instance_id] = {equipmentInstanceId}
                      AND [is_deleted] = 0
                    """)
                .SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentInstances
            .SingleOrDefaultAsync(instance => instance.EquipmentInstanceId == equipmentInstanceId && !instance.IsDeleted);
    }

    private async Task<IReadOnlyList<MarketListingSourceEntity>> GetListingSourcesAsync(Guid listingId)
    {
        return await dbContext.MarketListingSources
            .AsNoTracking()
            .Where(source => source.ListingId == listingId)
            .OrderBy(source => source.InventoryEntryId)
            .ToListAsync();
    }

    private async Task<IReadOnlyDictionary<Guid, long>> LoadPendingProceedsByListingAsync(
        IReadOnlyCollection<Guid> listingIds)
    {
        if (listingIds.Count == 0)
            return new Dictionary<Guid, long>();
        var proceeds = await dbContext.MarketTransactions
            .AsNoTracking()
            .Where(transaction => listingIds.Contains(transaction.ListingId))
            .Select(transaction => new { transaction.ListingId, transaction.SellerProceeds })
            .ToListAsync();
        return proceeds
            .GroupBy(transaction => transaction.ListingId)
            .ToDictionary(
                group => group.Key,
                group => group.Aggregate(0L, (sum, transaction) => checked(sum + transaction.SellerProceeds)));
    }

    private async Task<MarketListingEntity?> FindListingForUpdateAsync(Guid listingId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.MarketListings
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[market_listing] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [listing_id] = {listingId}
                      AND [is_deleted] = 0
                    """)
                .SingleOrDefaultAsync();
        }

        return await dbContext.MarketListings
            .SingleOrDefaultAsync(listing => listing.ListingId == listingId && !listing.IsDeleted);
    }

    /// <summary>
    /// 売上受取の再送を処理するため、論理削除済みの受取済み出品も更新ロック付きで取得します。
    /// </summary>
    private async Task<MarketListingEntity?> FindListingForClaimForUpdateAsync(Guid listingId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.MarketListings
                .FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[market_listing] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [listing_id] = {listingId}
                    """)
                .SingleOrDefaultAsync();
        }

        return await dbContext.MarketListings
            .SingleOrDefaultAsync(listing => listing.ListingId == listingId);
    }

    private async Task<InventoryEntryEntity?> FindInventoryEntryForUpdateAsync(
        Guid inventoryEntryId,
        bool includeDeleted)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [inventory_entry_id] = {inventoryEntryId}
                    """)
                .SingleOrDefaultAsync(entry => includeDeleted || !entry.IsDeleted);
        }

        return await dbContext.InventoryEntries.SingleOrDefaultAsync(entry =>
            entry.InventoryEntryId == inventoryEntryId
            && (includeDeleted || !entry.IsDeleted));
    }

    private async Task<bool> IsEquipmentEquippedForUpdateAsync(Guid accountId, Guid equipmentInstanceId)
    {
        if (dbContext.Database.IsSqlServer())
        {
            return await dbContext.EquipmentLoadoutSlots.FromSqlInterpolated($"""
                    SELECT slot.*
                    FROM [dbo].[equipment_loadout_slot] AS slot WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[equipment_loadout] AS loadout WITH (HOLDLOCK)
                        ON loadout.[equipment_loadout_id] = slot.[equipment_loadout_id]
                    WHERE loadout.[account_id] = {accountId}
                      AND loadout.[loadout_profile] = 'GAME'
                      AND loadout.[is_active] = 1
                      AND loadout.[is_deleted] = 0
                      AND slot.[is_deleted] = 0
                      AND slot.[equipment_instance_id] = {equipmentInstanceId}
                    """)
                .AnyAsync();
        }
        return await (from slot in dbContext.EquipmentLoadoutSlots
                      join loadout in dbContext.EquipmentLoadouts
                          on slot.EquipmentLoadoutId equals loadout.EquipmentLoadoutId
                      where loadout.AccountId == accountId
                            && loadout.LoadoutProfile == "GAME"
                            && loadout.IsActive
                            && !loadout.IsDeleted
                            && !slot.IsDeleted
                            && slot.EquipmentInstanceId == equipmentInstanceId
                      select slot).AnyAsync();
    }

    private async Task<MarketOperationResult<IReadOnlyList<Guid>>> TransferListingItemAsync(
        MarketListingEntity listing,
        long quantity,
        Guid buyerAccountId,
        Guid updatedBy
    )
    {
        var inventory = await FindBagInventoryAsync(buyerAccountId);
        if (inventory is null)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                400,
                "market.buyer_inventory_missing",
                "Buyer BAG inventory was not found.");

        if (listing.InstanceId.HasValue)
        {
            if (!listing.SourceInventoryEntryId.HasValue)
                return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                    409,
                    "market.source_inventory_missing",
                    "Listed instance has no escrow inventory entry.");

            var sourceEntry = await FindInventoryEntryForUpdateAsync(
                listing.SourceInventoryEntryId.Value,
                includeDeleted: true);
            if (sourceEntry is null || !sourceEntry.IsDeleted)
                return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                    409,
                    "market.escrow_not_found",
                    "Listed instance is not held in escrow.");

            if (KeyComparer.Equals(listing.InstanceType, "EQUIPMENT"))
            {
                var equipment = await FindEquipmentForUpdateAsync(listing.InstanceId.Value);
                if (equipment is null)
                    return MarketOperationResult<IReadOnlyList<Guid>>.Failure(404, "market.instance_not_found", "Equipment instance was not found.");
                equipment.AccountId = buyerAccountId;
                equipment.UpdatedAt = DateTime.UtcNow;
                equipment.UpdatedBy = updatedBy;
            }
            else if (KeyComparer.Equals(listing.InstanceType, "RUNE"))
            {
                var rune = await dbContext.RuneInstances
                    .FirstOrDefaultAsync(x => x.RuneInstanceId == listing.InstanceId && !x.IsDeleted);
                if (rune is null)
                    return MarketOperationResult<IReadOnlyList<Guid>>.Failure(404, "market.instance_not_found", "Rune instance was not found.");
                rune.AccountId = buyerAccountId;
                rune.UpdatedAt = DateTime.UtcNow;
                rune.UpdatedBy = updatedBy;
            }
            else
            {
                return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                    400,
                    "market.unsupported_instance_type",
                    "Unsupported instance type.");
            }

            sourceEntry.InventoryId = inventory.InventoryId;
            sourceEntry.SlotIndex = null;
            sourceEntry.Quantity = 1;
            sourceEntry.IsDeleted = false;
            sourceEntry.UpdatedAt = DateTime.UtcNow;
            sourceEntry.UpdatedBy = updatedBy;
            return MarketOperationResult<IReadOnlyList<Guid>>.Success([sourceEntry.InventoryEntryId]);
        }

        var now = DateTime.UtcNow;
        var existing = await dbContext.InventoryEntries.FirstOrDefaultAsync(entry =>
            entry.InventoryId == inventory.InventoryId
            && entry.ItemCategory == listing.ItemCategory
            && entry.ItemId == listing.ItemId
            && entry.InstanceId == null
            && !entry.IsDeleted);
        if (existing is not null)
        {
            existing.Quantity += quantity;
            existing.UpdatedAt = now;
            existing.UpdatedBy = updatedBy;
            return MarketOperationResult<IReadOnlyList<Guid>>.Success([existing.InventoryEntryId]);
        }

        var created = new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = inventory.InventoryId,
            ItemCategory = listing.ItemCategory,
            ItemId = listing.ItemId,
            Quantity = quantity,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        };
        await dbContext.InventoryEntries.AddAsync(created);
        return MarketOperationResult<IReadOnlyList<Guid>>.Success([created.InventoryEntryId]);
    }

    private async Task<MarketOperationResult<IReadOnlyList<Guid>>> RestoreEscrowAsync(
        MarketListingEntity listing,
        IReadOnlyList<MarketListingSourceEntity> sources,
        Guid updatedBy)
    {
        var remainingToRestore = listing.RemainingQuantity;
        var affectedEntryIds = new List<Guid>();
        foreach (var source in sources.OrderBy(source => source.InventoryEntryId))
        {
            if (remainingToRestore <= 0L)
                break;
            var restoreQuantity = Math.Min(source.Quantity, remainingToRestore);
            var entry = await FindInventoryEntryForUpdateAsync(source.InventoryEntryId, includeDeleted: true);
            if (entry is null)
                return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                    404,
                    "market.source_inventory_not_found",
                    "Source inventory entry was not found.");

            if (listing.InstanceId.HasValue)
            {
                entry.Quantity = 1;
            }
            else
            {
                try
                {
                    entry.Quantity = checked(entry.Quantity + restoreQuantity);
                }
                catch (OverflowException)
                {
                    return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                        409,
                        "market.escrow_restore_overflow",
                        "Escrow quantity is too large to restore.");
                }
            }
            if (entry.IsDeleted && entry.SlotIndex.HasValue)
            {
                var slotIsOccupied = await dbContext.InventoryEntries.AnyAsync(candidate =>
                    candidate.InventoryId == entry.InventoryId
                    && candidate.InventoryEntryId != entry.InventoryEntryId
                    && candidate.SlotIndex == entry.SlotIndex
                    && !candidate.IsDeleted);
                if (slotIsOccupied)
                {
                    // HOTBAR の slot 未指定 entry はクライアントへ描画されないため、競合時は
                    // 返却先を BAG に切り替える。Plugin は cancel 前の保存 lane で BAG 容量を
                    // 再確認しており、BAG の slot 未指定 entry は正本再同期で表示 slot へ詰め直す。
                    var sourceInventory = await dbContext.Inventories
                        .FirstOrDefaultAsync(inventory => inventory.InventoryId == entry.InventoryId
                            && inventory.AccountId == listing.SellerAccountId
                            && inventory.IsEnabled
                            && !inventory.IsDeleted);
                    if (sourceInventory is null)
                        return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                            409,
                            "market.source_inventory_not_found",
                            "Source inventory was not found.");

                    if (KeyComparer.Equals(sourceInventory.InventoryType, "HOTBAR"))
                    {
                        var bag = await FindBagInventoryAsync(listing.SellerAccountId);
                        if (bag is null)
                            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                                409,
                                "market.seller_inventory_missing",
                                "Seller BAG inventory was not found.");
                        entry.InventoryId = bag.InventoryId;
                    }
                    entry.SlotIndex = null;
                }
            }
            entry.IsDeleted = false;
            entry.UpdatedAt = DateTime.UtcNow;
            entry.UpdatedBy = updatedBy;
            affectedEntryIds.Add(entry.InventoryEntryId);
            remainingToRestore -= restoreQuantity;
        }

        if (remainingToRestore > 0L)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                409,
                "market.escrow_restore_shortage",
                "Listing escrow sources cannot restore the remaining quantity.");
        return MarketOperationResult<IReadOnlyList<Guid>>.Success(affectedEntryIds);
    }

    private async Task<InventoryEntity?> FindBagInventoryAsync(Guid accountId)
    {
        return await dbContext.Inventories
            .Where(inventory => inventory.AccountId == accountId
                && inventory.IsEnabled
                && !inventory.IsDeleted
                && inventory.InventoryProfile == "GAME"
                && inventory.InventoryType == "BAG")
            .FirstOrDefaultAsync();
    }

    private async Task<MarketOperationResult<IReadOnlyList<Guid>>> DebitGoldAsync(
        Guid buyerAccountId,
        long amount,
        Guid updatedBy,
        DateTime now)
    {
        if (amount <= 0L)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                400,
                "market.invalid_total_price",
                "Listing total price is invalid.");

        var buyerBalance = await GoldCurrencyBalanceSupport.LoadForUpdateAsync(dbContext, buyerAccountId);
        if (buyerBalance is null)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                400,
                "market.buyer_currency_inventory_missing",
                "Buyer Gold inventory was not found.");

        var buyerGold = GoldCurrencyBalanceSupport.TotalGold(buyerBalance.Entries);
        if (buyerGold < amount)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                400,
                "market.insufficient_gold",
                "Buyer does not have enough Gold.");

        var buyerAffected = await GoldCurrencyBalanceSupport.RewriteBalanceAsync(
            dbContext,
            buyerBalance,
            buyerGold - amount,
            updatedBy,
            now);
        return MarketOperationResult<IReadOnlyList<Guid>>.Success(buyerAffected);
    }

    private async Task<MarketOperationResult<IReadOnlyList<Guid>>> CreditGoldAsync(
        Guid sellerAccountId,
        long amount,
        Guid updatedBy,
        DateTime now)
    {
        if (amount <= 0L)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                400,
                "market.invalid_proceeds",
                "Listing proceeds are invalid.");

        var sellerBalance = await GoldCurrencyBalanceSupport.LoadForUpdateAsync(dbContext, sellerAccountId);
        if (sellerBalance is null)
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                409,
                "market.seller_currency_inventory_missing",
                "Seller Gold inventory was not found.");
        long sellerGold;
        try
        {
            sellerGold = checked(GoldCurrencyBalanceSupport.TotalGold(sellerBalance.Entries) + amount);
        }
        catch (OverflowException)
        {
            return MarketOperationResult<IReadOnlyList<Guid>>.Failure(
                409,
                "market.seller_gold_overflow",
                "Seller Gold balance is too large.");
        }

        var affected = await GoldCurrencyBalanceSupport.RewriteBalanceAsync(
            dbContext,
            sellerBalance,
            sellerGold,
            updatedBy,
            now);
        return MarketOperationResult<IReadOnlyList<Guid>>.Success(affected);
    }

    private async Task<MarketAccountStateEntity> EnsureAccountStateAsync(Guid accountId, Guid updatedBy)
    {
        var state = await dbContext.MarketAccountStates
            .FirstOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if (state is not null)
            return state;

        var now = DateTime.UtcNow;
        var limit = listingLimitService.ResolveLimit(0);
        state = new MarketAccountStateEntity
        {
            AccountId = accountId,
            CompletedTradeCount = 0,
            Tier = limit.Tier,
            MaxActiveListingCount = limit.MaxActiveListingCount,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        };
        await dbContext.MarketAccountStates.AddAsync(state);
        await dbContext.SaveChangesAsync();
        return state;
    }

    private async Task IncrementTradeCountAsync(Guid accountId, Guid updatedBy, DateTime now)
    {
        var state = await EnsureAccountStateAsync(accountId, updatedBy);
        state.CompletedTradeCount += 1;
        var limit = listingLimitService.ResolveLimit(state.CompletedTradeCount);
        state.Tier = limit.Tier;
        state.MaxActiveListingCount = limit.MaxActiveListingCount;
        state.UpdatedAt = now;
        state.UpdatedBy = updatedBy;
    }

    private async Task<int> CountActiveListingsAsync(Guid accountId)
    {
        return await dbContext.MarketListings.CountAsync(listing =>
            listing.SellerAccountId == accountId
            && !listing.IsDeleted
            && (listing.Status == "ACTIVE" || listing.Status == "SUSPENDED"));
    }

    private async Task<int> CountUsedListingSlotsAsync(Guid accountId)
    {
        return await dbContext.MarketListings.CountAsync(listing =>
            listing.SellerAccountId == accountId
            && !listing.IsDeleted
            && (listing.Status == "ACTIVE"
                || listing.Status == "SUSPENDED"
                || listing.Status == "SOLD"));
    }

    private static bool IsValidListingPayload(MarketListingCreateRequest request)
    {
        if (request.Quantity < 1
            || request.Quantity > int.MaxValue
            || request.UnitPrice < 1
            || string.IsNullOrWhiteSpace(request.CurrencyId)
            || string.IsNullOrWhiteSpace(request.ItemCategory)
            || string.IsNullOrWhiteSpace(request.ItemId)
            || request.SourceEntries is null
            || request.SourceEntries.Count == 0
            || request.SourceEntries.Any(source => source.InventoryEntryId == Guid.Empty
                || source.Quantity < 1L
                || source.Quantity > int.MaxValue)
            || request.SourceEntries.Select(source => source.InventoryEntryId).Distinct().Count() != request.SourceEntries.Count)
            return false;

        long sourceQuantity;
        try
        {
            sourceQuantity = request.SourceEntries.Aggregate(0L, (sum, source) => checked(sum + source.Quantity));
        }
        catch (OverflowException)
        {
            return false;
        }
        if (sourceQuantity != request.Quantity)
            return false;

        var hasStackPayload = string.IsNullOrWhiteSpace(request.InstanceType)
            && !request.InstanceId.HasValue;
        var hasInstancePayload = !string.IsNullOrWhiteSpace(request.InstanceType)
            && request.InstanceId.HasValue
            && request.Quantity == 1
            && request.SourceEntries.Count == 1
            && request.SourceEntries[0].Quantity == 1L;

        return hasStackPayload || hasInstancePayload;
    }

    private static MarketPriceSnapshotEntity CreateSnapshot(
        MarketPriceQuoteResponse quote,
        Guid? listingId,
        Guid? transactionId,
        DateTime now
    ) => new()
    {
        SnapshotId = Guid.NewGuid(),
        ListingId = listingId,
        TransactionId = transactionId,
        ItemCategory = quote.ItemCategory,
        ItemId = quote.ItemId,
        InstanceType = quote.InstanceType,
        InstanceId = quote.InstanceId,
        ValuationSignature = quote.ValuationSignature,
        ReferenceScope = quote.ReferenceScope,
        SampleCount = quote.SampleCount,
        Confidence = quote.Confidence,
        SellPrice = quote.SellPrice,
        SuggestedUnitPrice = quote.SuggestedUnitPrice,
        ReferenceUnitPrice = quote.ReferenceUnitPrice,
        AllowedMinUnitPrice = quote.AllowedMinUnitPrice,
        AllowedMaxUnitPrice = quote.AllowedMaxUnitPrice,
        Judgement = quote.Judgement,
        RollQualityScore = quote.RollQualityScore,
        RollQualityBucket = quote.RollQualityBucket,
        EvaluatedAt = quote.EvaluatedAt,
        CreatedAt = now,
    };

    private async Task<string> GetSellerAccountNameAsync(Guid sellerAccountId)
    {
        return await dbContext.Accounts
            .AsNoTracking()
            .Where(account => account.Uuid == sellerAccountId && !account.IsDeleted)
            .Select(account => account.AccountName)
            .FirstOrDefaultAsync() ?? string.Empty;
    }

    private static MarketListingResponse MapListing(
        MarketListingEntity entity,
        string sellerAccountName,
        IReadOnlyList<Guid>? sourceInventoryEntryIds = null,
        long pendingProceeds = 0L) => new()
    {
        ListingId = entity.ListingId,
        SellerAccountId = entity.SellerAccountId,
        SellerAccountName = sellerAccountName,
        BuyerAccountId = entity.BuyerAccountId,
        SourceInventoryEntryId = entity.SourceInventoryEntryId,
        ItemCategory = entity.ItemCategory,
        ItemId = entity.ItemId,
        InstanceType = entity.InstanceType,
        InstanceId = entity.InstanceId,
        SourceInventoryEntryIds = sourceInventoryEntryIds ?? Array.Empty<Guid>(),
        Quantity = entity.Quantity,
        RemainingQuantity = entity.RemainingQuantity,
        CurrencyId = entity.CurrencyId,
        UnitPrice = entity.UnitPrice,
        TotalPrice = entity.TotalPrice,
        PriceFloor = entity.PriceFloor,
        ReferenceUnitPrice = entity.ReferenceUnitPrice,
        PriceDeviationRate = entity.PriceDeviationRate,
        PriceConfidence = entity.PriceConfidence,
        ValuationSignature = entity.ValuationSignature,
        ValuationSnapshotJson = entity.ValuationSnapshotJson,
        Status = entity.Status,
        StatusReason = entity.StatusReason,
        ListedAt = entity.ListedAt,
        ExpiresAt = entity.ExpiresAt,
        SoldAt = entity.SoldAt,
        CanceledAt = entity.CanceledAt,
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        PendingProceeds = pendingProceeds,
    };

    private static MarketTransactionResponse MapTransaction(
        MarketTransactionEntity entity,
        IReadOnlyList<Guid>? affectedInventoryEntryIds = null) => new()
    {
        TransactionId = entity.TransactionId,
        ListingId = entity.ListingId,
        SellerAccountId = entity.SellerAccountId,
        BuyerAccountId = entity.BuyerAccountId,
        ItemCategory = entity.ItemCategory,
        ItemId = entity.ItemId,
        InstanceType = entity.InstanceType,
        InstanceId = entity.InstanceId,
        Quantity = entity.Quantity,
        CurrencyId = entity.CurrencyId,
        UnitPrice = entity.UnitPrice,
        TotalPrice = entity.TotalPrice,
        FeeAmount = entity.FeeAmount,
        SellerProceeds = entity.SellerProceeds,
        AffectedInventoryEntryIds = affectedInventoryEntryIds ?? Array.Empty<Guid>(),
        CompletedAt = entity.CompletedAt,
    };
}
