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

        if (!string.IsNullOrWhiteSpace(status))
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

        return result.Select(MapListing).ToList();
    }

    public async Task<MarketListingResponse?> GetListingAsync(Guid listingId)
    {
        var listing = await dbContext.MarketListings
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.ListingId == listingId && !x.IsDeleted);

        return listing is null ? null : MapListing(listing);
    }

    public async Task<MarketAccountSummaryResponse?> GetAccountSummaryAsync(Guid accountId)
    {
        var accountExists = await dbContext.Accounts.AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            return null;

        var state = await EnsureAccountStateAsync(accountId, accountId);
        var activeCount = await CountActiveListingsAsync(accountId);
        return listingLimitService.BuildSummary(state, activeCount);
    }

    public async Task<MarketOperationResult<MarketListingResponse>> CreateListingAsync(MarketListingCreateRequest request)
    {
        if (!IsValidListingPayload(request))
            return MarketOperationResult<MarketListingResponse>.Failure(400, "market.invalid_payload", "Listing payload is invalid.");

        var sellerExists = await dbContext.Accounts.AnyAsync(account => account.Uuid == request.SellerAccountId && !account.IsDeleted);
        if (!sellerExists)
            return MarketOperationResult<MarketListingResponse>.Failure(404, "market.seller_not_found", "Seller account was not found.");

        var state = await EnsureAccountStateAsync(request.SellerAccountId, request.CreatedBy);
        var activeCount = await CountActiveListingsAsync(request.SellerAccountId);
        var limit = listingLimitService.ResolveLimit(state.CompletedTradeCount);
        if (activeCount >= limit.MaxActiveListingCount)
            return MarketOperationResult<MarketListingResponse>.Failure(400, "market.listing_limit_exceeded", "Active listing limit exceeded.");

        var listingId = Guid.NewGuid();
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            var committedListing = await dbContext.MarketListings
                .AsNoTracking()
                .FirstOrDefaultAsync(listing => listing.ListingId == listingId && !listing.IsDeleted);
            if (committedListing is not null)
                return MarketOperationResult<MarketListingResponse>.Success(MapListing(committedListing));

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
            if (quote.SellPrice <= 0)
                return await RollbackFailureAsync(400, "market.sell_price_missing", "Item sell price is missing.");
            if (quote.Judgement is "BLOCK_BELOW_SELL_VALUE" or "BLOCK_OUT_OF_MARKET_RANGE")
                return await RollbackFailureAsync(400, "market.price_guard_rejected", quote.Judgement);

            var listing = new MarketListingEntity
            {
                ListingId = listingId,
                SellerAccountId = request.SellerAccountId,
                SourceInventoryEntryId = request.SourceInventoryEntryId,
                ItemCategory = request.ItemCategory,
                ItemId = request.ItemId,
                InstanceType = normalizedInstanceType,
                InstanceId = request.InstanceId,
                Quantity = request.Quantity,
                CurrencyId = request.CurrencyId,
                UnitPrice = request.UnitPrice,
                TotalPrice = request.UnitPrice * request.Quantity,
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
            await dbContext.MarketPriceSnapshots.AddAsync(CreateSnapshot(quote, listing.ListingId, null, now));
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();

            return MarketOperationResult<MarketListingResponse>.Success(MapListing(listing));
        });
    }

    public async Task<MarketOperationResult<MarketTransactionResponse>> PurchaseListingAsync(Guid listingId, MarketPurchaseRequest request)
    {
        var existingTransaction = await dbContext.MarketTransactions
            .AsNoTracking()
            .FirstOrDefaultAsync(transaction =>
                transaction.BuyerAccountId == request.BuyerAccountId
                && transaction.IdempotencyKey == request.IdempotencyKey);
        if (existingTransaction is not null)
            return MarketOperationResult<MarketTransactionResponse>.Success(MapTransaction(existingTransaction));

        await using var transactionScope = await dbContext.Database.BeginTransactionAsync();

        var listing = await dbContext.MarketListings
            .FirstOrDefaultAsync(x => x.ListingId == listingId && !x.IsDeleted);
        if (listing is null)
            return MarketOperationResult<MarketTransactionResponse>.Failure(404, "market.listing_not_found", "Listing was not found.");
        if (listing.Status != "ACTIVE")
            return MarketOperationResult<MarketTransactionResponse>.Failure(409, "market.listing_not_active", "Listing is not active.");
        if (listing.SellerAccountId == request.BuyerAccountId)
            return MarketOperationResult<MarketTransactionResponse>.Failure(400, "market.self_purchase", "Seller cannot buy own listing.");

        var buyerExists = await dbContext.Accounts.AnyAsync(account => account.Uuid == request.BuyerAccountId && !account.IsDeleted);
        if (!buyerExists)
            return MarketOperationResult<MarketTransactionResponse>.Failure(404, "market.buyer_not_found", "Buyer account was not found.");

        var transfer = await TransferListingItemAsync(listing, request.BuyerAccountId, request.UpdatedBy);
        if (!transfer.Succeeded)
            return MarketOperationResult<MarketTransactionResponse>.Failure(transfer.StatusCode, transfer.ErrorCode!, transfer.Detail!);

        var now = DateTime.UtcNow;
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
            Quantity = listing.Quantity,
            CurrencyId = listing.CurrencyId,
            UnitPrice = listing.UnitPrice,
            TotalPrice = listing.TotalPrice,
            FeeAmount = 0,
            SellerProceeds = listing.TotalPrice,
            ValuationSignature = listing.ValuationSignature,
            ValuationSnapshotJson = listing.ValuationSnapshotJson,
            IdempotencyKey = request.IdempotencyKey,
            CompletedAt = now,
            CreatedAt = now,
            CreatedBy = request.UpdatedBy,
        };

        listing.BuyerAccountId = request.BuyerAccountId;
        listing.Status = "SOLD";
        listing.SoldAt = now;
        listing.UpdatedAt = now;
        listing.UpdatedBy = request.UpdatedBy;
        listing.Version += 1;

        await dbContext.MarketTransactions.AddAsync(transaction);
        await IncrementTradeCountAsync(listing.SellerAccountId, request.UpdatedBy, now);
        await IncrementTradeCountAsync(request.BuyerAccountId, request.UpdatedBy, now);
        await dbContext.SaveChangesAsync();
        await transactionScope.CommitAsync();

        return MarketOperationResult<MarketTransactionResponse>.Success(MapTransaction(transaction));
    }

    public async Task<MarketOperationResult<MarketListingResponse>> CancelListingAsync(Guid listingId, MarketCancelRequest request)
    {
        await using var transactionScope = await dbContext.Database.BeginTransactionAsync();

        var listing = await dbContext.MarketListings.FirstOrDefaultAsync(x => x.ListingId == listingId && !x.IsDeleted);
        if (listing is null)
            return MarketOperationResult<MarketListingResponse>.Failure(404, "market.listing_not_found", "Listing was not found.");
        if (listing.SellerAccountId != request.SellerAccountId)
            return MarketOperationResult<MarketListingResponse>.Failure(403, "market.not_seller", "Only seller can cancel listing.");
        if (listing.Status is not ("ACTIVE" or "SUSPENDED"))
            return MarketOperationResult<MarketListingResponse>.Failure(400, "market.cancel_invalid_status", "Listing cannot be canceled.");

        var restore = await RestoreEscrowAsync(listing, request.UpdatedBy);
        if (!restore.Succeeded)
            return MarketOperationResult<MarketListingResponse>.Failure(restore.StatusCode, restore.ErrorCode!, restore.Detail!);

        var now = DateTime.UtcNow;
        listing.Status = "CANCELED";
        listing.StatusReason = request.Reason;
        listing.CanceledAt = now;
        listing.UpdatedAt = now;
        listing.UpdatedBy = request.UpdatedBy;
        listing.Version += 1;

        await dbContext.SaveChangesAsync();
        await transactionScope.CommitAsync();

        return MarketOperationResult<MarketListingResponse>.Success(MapListing(listing));
    }

    private async Task<MarketOperationResult<bool>> ReserveEscrowAsync(MarketListingCreateRequest request)
    {
        if (request.InstanceId.HasValue)
            return await ReserveInstanceAsync(request);

        if (!request.SourceInventoryEntryId.HasValue)
            return MarketOperationResult<bool>.Failure(400, "market.source_inventory_required", "Source inventory entry is required.");

        var entry = await dbContext.InventoryEntries
            .FirstOrDefaultAsync(x => x.InventoryEntryId == request.SourceInventoryEntryId.Value && !x.IsDeleted);
        if (entry is null)
            return MarketOperationResult<bool>.Failure(404, "market.source_inventory_not_found", "Source inventory entry was not found.");

        var inventory = await dbContext.Inventories
            .FirstOrDefaultAsync(x => x.InventoryId == entry.InventoryId && !x.IsDeleted);
        if (inventory is null || inventory.AccountId != request.SellerAccountId)
            return MarketOperationResult<bool>.Failure(409, "market.source_inventory_owner_mismatch", "Source inventory owner mismatch.");
        if (!KeyComparer.Equals(entry.ItemId, request.ItemId) || !KeyComparer.Equals(entry.ItemCategory, request.ItemCategory))
            return MarketOperationResult<bool>.Failure(400, "market.source_item_mismatch", "Source inventory item mismatch.");
        if (entry.Quantity < request.Quantity)
            return MarketOperationResult<bool>.Failure(400, "market.quantity_shortage", "Source inventory quantity is not enough.");

        entry.Quantity -= request.Quantity;
        entry.UpdatedAt = DateTime.UtcNow;
        entry.UpdatedBy = request.CreatedBy;
        if (entry.Quantity == 0)
            entry.IsDeleted = true;

        return MarketOperationResult<bool>.Success(true);
    }

    private async Task<MarketOperationResult<bool>> ReserveInstanceAsync(MarketListingCreateRequest request)
    {
        var instanceId = request.InstanceId!.Value;
        var instanceType = request.InstanceType!.Trim().ToUpperInvariant();
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
            if (!await IsEquipmentPresentForUpdateAsync(request.SellerAccountId, instanceId))
                return MarketOperationResult<bool>.Failure(409, "market.instance_not_present", "Equipment is not present in BAG, HOTBAR, or the active loadout.");
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
                    """)
                .SingleOrDefaultAsync();
        }
        return await dbContext.EquipmentInstances
            .SingleOrDefaultAsync(instance => instance.EquipmentInstanceId == equipmentInstanceId);
    }

    private async Task<bool> IsEquipmentPresentForUpdateAsync(Guid accountId, Guid equipmentInstanceId)
    {
        bool inNormalInventory;
        if (dbContext.Database.IsSqlServer())
        {
            inNormalInventory = await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT entry.*
                    FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                        ON inventory.[inventory_id] = entry.[inventory_id]
                    WHERE inventory.[account_id] = {accountId}
                      AND inventory.[is_deleted] = 0
                      AND inventory.[is_enabled] = 1
                      AND inventory.[inventory_profile] = 'GAME'
                      AND inventory.[inventory_type] IN ('BAG', 'HOTBAR')
                      AND entry.[is_deleted] = 0
                      AND entry.[instance_type] = 'EQUIPMENT'
                      AND entry.[instance_id] = {equipmentInstanceId}
                    """)
                .AnyAsync();
        }
        else
        {
            inNormalInventory = await (from entry in dbContext.InventoryEntries
                                       join inventory in dbContext.Inventories
                                           on entry.InventoryId equals inventory.InventoryId
                                       where inventory.AccountId == accountId
                                             && !inventory.IsDeleted
                                             && inventory.IsEnabled
                                             && inventory.InventoryProfile == "GAME"
                                             && (inventory.InventoryType == "BAG"
                                                 || inventory.InventoryType == "HOTBAR")
                                             && !entry.IsDeleted
                                             && entry.InstanceType == "EQUIPMENT"
                                             && entry.InstanceId == equipmentInstanceId
                                       select entry).AnyAsync();
        }
        if (inNormalInventory)
            return true;

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

    private async Task<MarketOperationResult<bool>> TransferListingItemAsync(
        MarketListingEntity listing,
        Guid buyerAccountId,
        Guid updatedBy
    )
    {
        if (listing.InstanceId.HasValue)
        {
            if (KeyComparer.Equals(listing.InstanceType, "EQUIPMENT"))
            {
                var equipment = await dbContext.EquipmentInstances
                    .FirstOrDefaultAsync(x => x.EquipmentInstanceId == listing.InstanceId && !x.IsDeleted);
                if (equipment is null)
                    return MarketOperationResult<bool>.Failure(404, "market.instance_not_found", "Equipment instance was not found.");
                equipment.AccountId = buyerAccountId;
                equipment.UpdatedAt = DateTime.UtcNow;
                equipment.UpdatedBy = updatedBy;
                return MarketOperationResult<bool>.Success(true);
            }

            if (KeyComparer.Equals(listing.InstanceType, "RUNE"))
            {
                var rune = await dbContext.RuneInstances
                    .FirstOrDefaultAsync(x => x.RuneInstanceId == listing.InstanceId && !x.IsDeleted);
                if (rune is null)
                    return MarketOperationResult<bool>.Failure(404, "market.instance_not_found", "Rune instance was not found.");
                rune.AccountId = buyerAccountId;
                rune.UpdatedAt = DateTime.UtcNow;
                rune.UpdatedBy = updatedBy;
                return MarketOperationResult<bool>.Success(true);
            }
        }

        var inventory = await dbContext.Inventories
            .Where(x => x.AccountId == buyerAccountId && x.IsEnabled && !x.IsDeleted)
            .OrderBy(x => x.InventoryType)
            .FirstOrDefaultAsync();
        if (inventory is null)
            return MarketOperationResult<bool>.Failure(400, "market.buyer_inventory_missing", "Buyer inventory was not found.");

        var now = DateTime.UtcNow;
        var existing = await dbContext.InventoryEntries.FirstOrDefaultAsync(entry =>
            entry.InventoryId == inventory.InventoryId
            && entry.ItemCategory == listing.ItemCategory
            && entry.ItemId == listing.ItemId
            && entry.InstanceId == null
            && !entry.IsDeleted);
        if (existing is not null)
        {
            existing.Quantity += listing.Quantity;
            existing.UpdatedAt = now;
            existing.UpdatedBy = updatedBy;
        }
        else
        {
            await dbContext.InventoryEntries.AddAsync(new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(),
                InventoryId = inventory.InventoryId,
                ItemCategory = listing.ItemCategory,
                ItemId = listing.ItemId,
                Quantity = listing.Quantity,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            });
        }

        return MarketOperationResult<bool>.Success(true);
    }

    private async Task<MarketOperationResult<bool>> RestoreEscrowAsync(MarketListingEntity listing, Guid updatedBy)
    {
        if (listing.InstanceId.HasValue)
            return MarketOperationResult<bool>.Success(true);

        if (!listing.SourceInventoryEntryId.HasValue)
            return MarketOperationResult<bool>.Failure(409, "market.source_inventory_missing", "Source inventory entry is missing.");

        var entry = await dbContext.InventoryEntries
            .FirstOrDefaultAsync(x => x.InventoryEntryId == listing.SourceInventoryEntryId.Value);
        if (entry is null)
            return MarketOperationResult<bool>.Failure(404, "market.source_inventory_not_found", "Source inventory entry was not found.");

        entry.Quantity += listing.Quantity;
        entry.IsDeleted = false;
        entry.UpdatedAt = DateTime.UtcNow;
        entry.UpdatedBy = updatedBy;
        return MarketOperationResult<bool>.Success(true);
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

    private static bool IsValidListingPayload(MarketListingCreateRequest request)
    {
        if (request.Quantity < 1 || request.UnitPrice < 1 || string.IsNullOrWhiteSpace(request.CurrencyId))
            return false;

        var hasStackPayload = request.SourceInventoryEntryId.HasValue
            && string.IsNullOrWhiteSpace(request.InstanceType)
            && !request.InstanceId.HasValue;
        var hasInstancePayload = !string.IsNullOrWhiteSpace(request.InstanceType)
            && request.InstanceId.HasValue
            && request.Quantity == 1;

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

    private static MarketListingResponse MapListing(MarketListingEntity entity) => new()
    {
        ListingId = entity.ListingId,
        SellerAccountId = entity.SellerAccountId,
        BuyerAccountId = entity.BuyerAccountId,
        SourceInventoryEntryId = entity.SourceInventoryEntryId,
        ItemCategory = entity.ItemCategory,
        ItemId = entity.ItemId,
        InstanceType = entity.InstanceType,
        InstanceId = entity.InstanceId,
        Quantity = entity.Quantity,
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
    };

    private static MarketTransactionResponse MapTransaction(MarketTransactionEntity entity) => new()
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
        CompletedAt = entity.CompletedAt,
    };
}
