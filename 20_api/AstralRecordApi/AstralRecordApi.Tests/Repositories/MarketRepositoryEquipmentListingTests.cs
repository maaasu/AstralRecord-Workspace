using System.Data;
using System.Data.Common;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MarketRepositoryEquipmentListingTests
{
    [Fact]
    public async Task MarketPriceQuote_RejectsUntradeableItems()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var priceService = new MarketPriceService(
            harness.DbContext,
            new StaticItemRepository(CreateMarketItem(true, false, saleValue: 100)));

        var quote = await priceService.CreateQuoteAsync(new MarketPriceQuoteRequest
        {
            ItemCategory = "material",
            ItemId = "market_material",
            Quantity = 1,
            UnitPrice = 101,
        });

        Assert.Null(quote);
    }

    [Fact]
    public async Task MarketPriceQuote_AllowsUnsellableItemWithPriceAboveZeroSellValue()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var priceService = new MarketPriceService(
            harness.DbContext,
            new StaticItemRepository(CreateMarketItem(false, true, saleValue: 0)));

        var quote = await priceService.CreateQuoteAsync(new MarketPriceQuoteRequest
        {
            ItemCategory = "material",
            ItemId = "market_material",
            Quantity = 1,
            UnitPrice = 1,
        });

        Assert.NotNull(quote);
        Assert.Equal("LOW_CONFIDENCE_ALLOW", quote.Judgement);
    }

    [Fact]
    public async Task MarketPriceQuote_RejectsPriceAtOrBelowSellValue()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var priceService = new MarketPriceService(
            harness.DbContext,
            new StaticItemRepository(CreateMarketItem(false, false, saleValue: 100)));

        var quote = await priceService.CreateQuoteAsync(new MarketPriceQuoteRequest
        {
            ItemCategory = "material",
            ItemId = "market_material",
            Quantity = 1,
            UnitPrice = 100,
        });

        Assert.NotNull(quote);
        Assert.Equal("BLOCK_AT_OR_BELOW_SELL_VALUE", quote.Judgement);
    }

    [Fact]
    public async Task ListingResponsesIncludeSellerAccountName()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true);

        var created = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.True(created.Succeeded);
        Assert.Equal("market-test", created.Value!.SellerAccountName);

        var listings = await harness.Repository.GetListingsAsync(new MarketListingQuery
        {
            SellerAccountId = harness.AccountId,
            Page = 1,
            PageSize = 10,
        });

        var listing = Assert.Single(listings);
        Assert.Equal("market-test", listing.SellerAccountName);

        var detail = await harness.Repository.GetListingAsync(listing.ListingId);
        Assert.Equal("market-test", detail!.SellerAccountName);
    }

    [Fact]
    public async Task CreateListing_RejectsEquipmentWithoutEscrowSourceBeforeQuote()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.False(result.Succeeded);
        Assert.Equal("market.invalid_payload", result.ErrorCode);
        Assert.Equal(0, harness.PriceService.CallCount);
        Assert.Equal(0, await harness.DbContext.MarketListings.CountAsync());
    }

    [Fact]
    public async Task CreateListing_QuotesInsideSerializableTransactionAfterMembershipReservation()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true);

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.True(result.Succeeded);
        Assert.Equal(1, harness.PriceService.CallCount);
        Assert.Equal(IsolationLevel.Serializable, harness.PriceService.ObservedIsolationLevel);
        var listing = Assert.Single(await harness.DbContext.MarketListings.AsNoTracking().ToListAsync());
        Assert.Equal("EQUIPMENT", listing.InstanceType);
        Assert.Equal(harness.EquipmentInstanceId, listing.InstanceId);
    }

    [Fact]
    public async Task CreateListing_RejectsActiveListingBeforeQuote()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true);
        await harness.AddActiveListingAsync();

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.False(result.Succeeded);
        Assert.Equal("market.instance_already_listed", result.ErrorCode);
        Assert.Equal(0, harness.PriceService.CallCount);
        Assert.Equal(1, await harness.DbContext.MarketListings.CountAsync());
    }

    [Fact]
    public async Task CreateListing_CommitResultUnknown_ReplaysCommittedEquipmentListingWithSameId()
    {
        var interceptor = new CommitResultUnknownInterceptor();
        await using var harness = await MarketHarness.CreateAsync(
            addMembership: true,
            commitInterceptor: interceptor);

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.True(result.Succeeded);
        Assert.True(interceptor.WasThrown);
        Assert.Equal(1, harness.PriceService.CallCount);
        var listing = Assert.Single(await harness.DbContext.MarketListings.AsNoTracking().ToListAsync());
        Assert.NotNull(result.Value);
        Assert.Equal(listing.ListingId, result.Value.ListingId);
        Assert.Equal(1, await harness.DbContext.MarketPriceSnapshots.CountAsync());
    }

    [Fact]
    public async Task CreateListing_CommitResultUnknown_ReplaysCommittedStackListingWithoutDoubleReservation()
    {
        var interceptor = new CommitResultUnknownInterceptor();
        await using var harness = await MarketHarness.CreateAsync(
            addMembership: false,
            commitInterceptor: interceptor);
        var entryId = await harness.AddStackEntryAsync(quantity: 10);

        var result = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(entryId, quantity: 3));

        Assert.True(result.Succeeded);
        Assert.True(interceptor.WasThrown);
        Assert.Equal(1, harness.PriceService.CallCount);
        var listing = Assert.Single(await harness.DbContext.MarketListings.AsNoTracking().ToListAsync());
        Assert.NotNull(result.Value);
        Assert.Equal(listing.ListingId, result.Value.ListingId);
        Assert.Equal(entryId, listing.SourceInventoryEntryId);
        var entry = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(candidate => candidate.InventoryEntryId == entryId);
        Assert.Equal(7, entry.Quantity);
        Assert.False(entry.IsDeleted);
        Assert.Equal(1, await harness.DbContext.MarketPriceSnapshots.CountAsync());
    }

    [Fact]
    public async Task CreateListing_CanceledListingReleasesListingSlot()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true, maxListingSlots: 1);

        var created = await harness.Repository.CreateListingAsync(harness.CreateRequest());
        Assert.True(created.Succeeded);
        var canceled = await harness.Repository.CancelListingAsync(created.Value!.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });
        Assert.True(canceled.Succeeded);
        var restored = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == harness.EquipmentEntryId);
        Assert.False(restored.IsDeleted);
        Assert.Equal(1, restored.Quantity);

        var retry = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.True(retry.Succeeded);
        var source = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == harness.EquipmentEntryId);
        Assert.True(source.IsDeleted);
        Assert.Equal(0, source.Quantity);
    }

    [Fact]
    public async Task CancelListing_RestoresStackQuantityWithoutOverwritingRemainingSource()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var entryId = await harness.AddStackEntryAsync(quantity: 10);
        var listing = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(entryId, quantity: 3));
        Assert.True(listing.Succeeded);

        var canceled = await harness.Repository.CancelListingAsync(listing.Value!.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });

        Assert.True(canceled.Succeeded);
        var source = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == entryId);
        Assert.False(source.IsDeleted);
        Assert.Equal(10, source.Quantity);
    }

    [Fact]
    public async Task CancelListing_ReleasesTheOriginalSlotWhenItWasReusedWhileListed()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var entryId = await harness.AddStackEntryAsync(quantity: 3);
        var listing = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(entryId, quantity: 3));
        Assert.True(listing.Succeeded);

        var sourceInventoryId = await harness.DbContext.InventoryEntries
            .Where(entry => entry.InventoryEntryId == entryId)
            .Select(entry => entry.InventoryId)
            .SingleAsync();
        var now = DateTime.UtcNow;
        harness.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = sourceInventoryId,
            SlotIndex = 1,
            ItemCategory = "material",
            ItemId = "another_material",
            Quantity = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });
        await harness.DbContext.SaveChangesAsync();

        var canceled = await harness.Repository.CancelListingAsync(listing.Value!.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });

        Assert.True(canceled.Succeeded);
        var restored = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == entryId);
        Assert.False(restored.IsDeleted);
        Assert.Equal(3, restored.Quantity);
        Assert.Null(restored.SlotIndex);
    }

    [Fact]
    public async Task CancelListing_MovesHotbarSourceToBagWhenOriginalSlotWasReused()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var bagInventoryId = Guid.NewGuid();
        var hotbarInventoryId = Guid.NewGuid();
        var entryId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        harness.DbContext.Inventories.AddRange(
            new InventoryEntity
            {
                InventoryId = bagInventoryId,
                AccountId = harness.AccountId,
                InventoryType = "BAG",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = harness.AccountId,
                UpdatedBy = harness.AccountId,
            },
            new InventoryEntity
            {
                InventoryId = hotbarInventoryId,
                AccountId = harness.AccountId,
                InventoryType = "HOTBAR",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = harness.AccountId,
                UpdatedBy = harness.AccountId,
            });
        harness.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = entryId,
            InventoryId = hotbarInventoryId,
            SlotIndex = 1,
            ItemCategory = "material",
            ItemId = "market_material",
            Quantity = 3,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });
        await harness.DbContext.SaveChangesAsync();

        var listing = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(entryId, quantity: 3));
        Assert.True(listing.Succeeded);

        harness.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = hotbarInventoryId,
            SlotIndex = 1,
            ItemCategory = "material",
            ItemId = "another_material",
            Quantity = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });
        await harness.DbContext.SaveChangesAsync();

        var canceled = await harness.Repository.CancelListingAsync(listing.Value!.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });

        Assert.True(canceled.Succeeded);
        var restored = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == entryId);
        Assert.False(restored.IsDeleted);
        Assert.Equal(3, restored.Quantity);
        Assert.Equal(bagInventoryId, restored.InventoryId);
        Assert.Null(restored.SlotIndex);
    }

    [Fact]
    public async Task CreateListing_ReservesAndRestoresMultipleStackSources()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);
        var firstEntryId = await harness.AddStackEntryAsync(quantity: 2);
        var secondEntryId = await harness.AddStackEntryAsync(quantity: 6);
        var request = harness.CreateStackRequest(firstEntryId, quantity: 6);
        request.SourceEntries =
        [
            new MarketListingSourceRequest { InventoryEntryId = firstEntryId, Quantity = 2 },
            new MarketListingSourceRequest { InventoryEntryId = secondEntryId, Quantity = 4 },
        ];

        var created = await harness.Repository.CreateListingAsync(request);

        Assert.True(created.Succeeded);
        Assert.Equal(2, await harness.DbContext.MarketListingSources.CountAsync());
        var firstAfterCreate = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == firstEntryId);
        var secondAfterCreate = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == secondEntryId);
        Assert.True(firstAfterCreate.IsDeleted);
        Assert.Equal(2, secondAfterCreate.Quantity);

        var canceled = await harness.Repository.CancelListingAsync(created.Value!.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });

        Assert.True(canceled.Succeeded);
        var firstAfterCancel = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == firstEntryId);
        var secondAfterCancel = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == secondEntryId);
        Assert.False(firstAfterCancel.IsDeleted);
        Assert.Equal(2, firstAfterCancel.Quantity);
        Assert.Equal(6, secondAfterCancel.Quantity);
    }

    [Fact]
    public async Task CreateListing_RejectsDeletedEquipmentInstanceBeforeQuote()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true);
        var equipment = await harness.DbContext.EquipmentInstances
            .SingleAsync(instance => instance.EquipmentInstanceId == harness.EquipmentInstanceId);
        equipment.IsDeleted = true;
        await harness.DbContext.SaveChangesAsync();

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.False(result.Succeeded);
        Assert.Equal("market.instance_not_found", result.ErrorCode);
        Assert.Equal(0, harness.PriceService.CallCount);
    }

    [Fact]
    public async Task PurchaseListing_HoldsSellerProceedsUntilClaimAndTransfersEscrowedEquipment()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: true);
        var listing = await harness.Repository.CreateListingAsync(harness.CreateRequest());
        Assert.True(listing.Succeeded);
        await harness.AddGoldInventoryAsync(harness.AccountId, 0);
        var buyer = await harness.AddBuyerWithGoldAsync(500);

        var result = await harness.Repository.PurchaseListingAsync(listing.Value!.ListingId, new MarketPurchaseRequest
        {
            BuyerAccountId = buyer.AccountId,
            IdempotencyKey = Guid.NewGuid().ToString(),
            UpdatedBy = buyer.AccountId,
        });

        Assert.True(result.Succeeded);
        Assert.Equal(100, result.Value!.TotalPrice);
        Assert.NotEmpty(result.Value.AffectedInventoryEntryIds);
        Assert.Equal(400, await harness.TotalGoldAsync(buyer.AccountId));
        Assert.Equal(0, await harness.TotalGoldAsync(harness.AccountId));
        var source = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == harness.EquipmentEntryId);
        Assert.Equal(buyer.BagInventoryId, source.InventoryId);
        Assert.False(source.IsDeleted);
        var equipment = await harness.DbContext.EquipmentInstances.AsNoTracking()
            .SingleAsync(instance => instance.EquipmentInstanceId == harness.EquipmentInstanceId);
        Assert.Equal(buyer.AccountId, equipment.AccountId);

        var claimRequest = new MarketProceedsClaimRequest
        {
            SellerAccountId = harness.AccountId,
            IdempotencyKey = "claim-equipment-listing",
            UpdatedBy = harness.AccountId,
        };
        var claim = await harness.Repository.ClaimProceedsAsync(listing.Value.ListingId, claimRequest);
        Assert.True(claim.Succeeded);
        Assert.Equal(100, claim.Value!.Amount);
        Assert.Equal(100, await harness.TotalGoldAsync(harness.AccountId));

        // HTTP 応答喪失後の再送を表す。同一キーは確定済み receipt を返し、Gold を二重加算しない。
        var replay = await harness.Repository.ClaimProceedsAsync(listing.Value.ListingId, claimRequest);
        Assert.True(replay.Succeeded);
        Assert.Equal(claim.Value.Amount, replay.Value!.Amount);
        Assert.Equal(claim.Value.AffectedInventoryEntryIds, replay.Value.AffectedInventoryEntryIds);
        Assert.Equal(100, await harness.TotalGoldAsync(harness.AccountId));

        var duplicateWithAnotherKey = await harness.Repository.ClaimProceedsAsync(listing.Value.ListingId,
            new MarketProceedsClaimRequest
            {
                SellerAccountId = harness.AccountId,
                IdempotencyKey = "another-claim-key",
                UpdatedBy = harness.AccountId,
            });
        Assert.False(duplicateWithAnotherKey.Succeeded);
        Assert.Equal("market.claim_already_completed", duplicateWithAnotherKey.ErrorCode);
    }

    [Fact]
    public async Task PartialPurchase_CancelThenClaimKeepsSlotUntilProceedsAreReceived()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false, maxListingSlots: 1);
        var sourceEntryId = await harness.AddStackEntryAsync(quantity: 10);
        var created = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(sourceEntryId, quantity: 8));
        Assert.True(created.Succeeded);
        await harness.AddGoldInventoryAsync(harness.AccountId, 0);
        var buyer = await harness.AddBuyerWithGoldAsync(1_000);

        var purchased = await harness.Repository.PurchaseListingAsync(created.Value!.ListingId, new MarketPurchaseRequest
        {
            BuyerAccountId = buyer.AccountId,
            Quantity = 3,
            IdempotencyKey = Guid.NewGuid().ToString(),
            UpdatedBy = buyer.AccountId,
        });

        Assert.True(purchased.Succeeded);
        Assert.Equal(300, purchased.Value!.TotalPrice);
        Assert.Equal(700, await harness.TotalGoldAsync(buyer.AccountId));
        Assert.Equal(0, await harness.TotalGoldAsync(harness.AccountId));
        var active = await harness.Repository.GetListingAsync(created.Value.ListingId);
        Assert.NotNull(active);
        Assert.Equal("ACTIVE", active.Status);
        Assert.Equal(5, active.RemainingQuantity);
        Assert.Equal(300, active.PendingProceeds);

        var tooEarlyClaim = await harness.Repository.ClaimProceedsAsync(created.Value.ListingId, new MarketProceedsClaimRequest
        {
            SellerAccountId = harness.AccountId,
            IdempotencyKey = "early-claim",
            UpdatedBy = harness.AccountId,
        });
        Assert.False(tooEarlyClaim.Succeeded);
        Assert.Equal("market.claim_invalid_status", tooEarlyClaim.ErrorCode);

        var canceled = await harness.Repository.CancelListingAsync(created.Value.ListingId, new MarketCancelRequest
        {
            SellerAccountId = harness.AccountId,
            UpdatedBy = harness.AccountId,
        });
        Assert.True(canceled.Succeeded);
        Assert.Equal("SOLD", canceled.Value!.Status);
        Assert.Equal(0, canceled.Value.RemainingQuantity);
        Assert.Equal(300, canceled.Value.PendingProceeds);

        var sourceAfterCancel = await harness.DbContext.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == sourceEntryId);
        Assert.Equal(7, sourceAfterCancel.Quantity);
        var blockedByUnclaimedSale = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(sourceEntryId, quantity: 1));
        Assert.False(blockedByUnclaimedSale.Succeeded);
        Assert.Equal("market.listing_slot_limit_exceeded", blockedByUnclaimedSale.ErrorCode);

        var claimed = await harness.Repository.ClaimProceedsAsync(created.Value.ListingId, new MarketProceedsClaimRequest
        {
            SellerAccountId = harness.AccountId,
            IdempotencyKey = "partial-claim",
            UpdatedBy = harness.AccountId,
        });
        Assert.True(claimed.Succeeded);
        Assert.Equal(300, claimed.Value!.Amount);
        Assert.Equal(300, await harness.TotalGoldAsync(harness.AccountId));

        var retry = await harness.Repository.CreateListingAsync(
            harness.CreateStackRequest(sourceEntryId, quantity: 1));
        Assert.True(retry.Succeeded);
    }

    private sealed class MarketHarness : IAsyncDisposable
    {
        private readonly SqliteConnection connection;

        private MarketHarness(
            SqliteConnection connection,
            AstralRecordDbContext dbContext,
            RecordingPriceService priceService,
            Guid accountId,
            Guid equipmentInstanceId,
            Guid? equipmentEntryId,
            int maxListingSlots)
        {
            this.connection = connection;
            DbContext = dbContext;
            PriceService = priceService;
            AccountId = accountId;
            EquipmentInstanceId = equipmentInstanceId;
            EquipmentEntryId = equipmentEntryId;
            Repository = new MarketRepository(dbContext, priceService, new FixedLimitService(maxListingSlots));
        }

        public AstralRecordDbContext DbContext { get; }
        public RecordingPriceService PriceService { get; }
        public MarketRepository Repository { get; }
        public Guid AccountId { get; }
        public Guid EquipmentInstanceId { get; }
        public Guid? EquipmentEntryId { get; }

        public static async Task<MarketHarness> CreateAsync(
            bool addMembership,
            CommitResultUnknownInterceptor? commitInterceptor = null,
            int maxListingSlots = 10)
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var optionsBuilder = new DbContextOptionsBuilder<AstralRecordDbContext>();
            if (commitInterceptor is null)
            {
                optionsBuilder.UseSqlite(connection);
            }
            else
            {
                optionsBuilder
                    .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                        dependencies => new CommitResultUnknownRetryingExecutionStrategy(dependencies)))
                    .AddInterceptors(commitInterceptor);
            }
            var dbContext = new AstralRecordDbContext(optionsBuilder.Options);
            await dbContext.Database.EnsureCreatedAsync();
            var accountId = Guid.NewGuid();
            var equipmentInstanceId = Guid.NewGuid();
            var inventoryId = Guid.NewGuid();
            Guid? equipmentEntryId = null;
            var now = DateTime.UtcNow;
            dbContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = Guid.NewGuid(),
                AccountName = "market-test",
                IsActive = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            dbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentInstanceId,
                AccountId = accountId,
                ItemId = "market_equipment",
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            if (addMembership)
            {
                dbContext.Inventories.Add(new InventoryEntity
                {
                    InventoryId = inventoryId,
                    AccountId = accountId,
                    InventoryType = "BAG",
                    InventoryProfile = "GAME",
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
                equipmentEntryId = Guid.NewGuid();
                dbContext.InventoryEntries.Add(new InventoryEntryEntity
                {
                    InventoryEntryId = equipmentEntryId.Value,
                    InventoryId = inventoryId,
                    SlotIndex = 1,
                    ItemCategory = "equipment",
                    ItemId = "market_equipment",
                    InstanceType = "EQUIPMENT",
                    InstanceId = equipmentInstanceId,
                    Quantity = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
            }
            await dbContext.SaveChangesAsync();
            var priceService = new RecordingPriceService(dbContext, commitInterceptor);
            return new MarketHarness(
                connection,
                dbContext,
                priceService,
                accountId,
                equipmentInstanceId,
                equipmentEntryId,
                maxListingSlots);
        }

        public MarketListingCreateRequest CreateRequest() => new()
        {
            SellerAccountId = AccountId,
            SourceEntries =
            [
                new MarketListingSourceRequest
                {
                    InventoryEntryId = EquipmentEntryId ?? Guid.Empty,
                    Quantity = 1,
                },
            ],
            ItemCategory = "equipment",
            ItemId = "market_equipment",
            InstanceType = "equipment",
            InstanceId = EquipmentInstanceId,
            Quantity = 1,
            CurrencyId = "gold",
            UnitPrice = 100,
            CreatedBy = AccountId,
        };

        public MarketListingCreateRequest CreateStackRequest(Guid entryId, int quantity) => new()
        {
            SellerAccountId = AccountId,
            SourceEntries =
            [
                new MarketListingSourceRequest
                {
                    InventoryEntryId = entryId,
                    Quantity = quantity,
                },
            ],
            ItemCategory = "material",
            ItemId = "market_material",
            Quantity = quantity,
            CurrencyId = "gold",
            UnitPrice = 100,
            CreatedBy = AccountId,
        };

        public async Task<Guid> AddStackEntryAsync(long quantity)
        {
            var inventoryId = Guid.NewGuid();
            var entryId = Guid.NewGuid();
            var now = DateTime.UtcNow;
            DbContext.Inventories.Add(new InventoryEntity
            {
                InventoryId = inventoryId,
                AccountId = AccountId,
                InventoryType = "BAG",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            DbContext.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = entryId,
                InventoryId = inventoryId,
                SlotIndex = 1,
                ItemCategory = "material",
                ItemId = "market_material",
                Quantity = quantity,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            await DbContext.SaveChangesAsync();
            return entryId;
        }

        public async Task AddActiveListingAsync()
        {
            var now = DateTime.UtcNow;
            DbContext.MarketListings.Add(new MarketListingEntity
            {
                ListingId = Guid.NewGuid(),
                SellerAccountId = AccountId,
                ItemCategory = "equipment",
                ItemId = "market_equipment",
                InstanceType = "EQUIPMENT",
                InstanceId = EquipmentInstanceId,
                Quantity = 1,
                RemainingQuantity = 1,
                CurrencyId = "gold",
                UnitPrice = 100,
                TotalPrice = 100,
                PriceFloor = 1,
                PriceConfidence = "HIGH",
                Status = "ACTIVE",
                ListedAt = now,
                ExpiresAt = now.AddDays(1),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            await DbContext.SaveChangesAsync();
        }

        public async Task AddGoldInventoryAsync(Guid accountId, long gold)
        {
            var inventoryId = Guid.NewGuid();
            var now = DateTime.UtcNow;
            DbContext.Inventories.Add(new InventoryEntity
            {
                InventoryId = inventoryId,
                AccountId = accountId,
                InventoryType = "CURRENCY",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            if (gold > 0)
            {
                DbContext.InventoryEntries.Add(new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = inventoryId,
                    ItemCategory = "currency",
                    ItemId = "gold",
                    Quantity = gold,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
            }
            await DbContext.SaveChangesAsync();
        }

        public async Task<BuyerSetup> AddBuyerWithGoldAsync(long gold)
        {
            var accountId = Guid.NewGuid();
            var bagInventoryId = Guid.NewGuid();
            var now = DateTime.UtcNow;
            DbContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = Guid.NewGuid(),
                AccountName = "market-buyer",
                IsActive = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            DbContext.Inventories.Add(new InventoryEntity
            {
                InventoryId = bagInventoryId,
                AccountId = accountId,
                InventoryType = "BAG",
                InventoryProfile = "GAME",
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
            await DbContext.SaveChangesAsync();
            await AddGoldInventoryAsync(accountId, gold);
            return new BuyerSetup(accountId, bagInventoryId);
        }

        public async Task<long> TotalGoldAsync(Guid accountId)
        {
            var entries = await (from entry in DbContext.InventoryEntries.AsNoTracking()
                                 join inventory in DbContext.Inventories.AsNoTracking()
                                     on entry.InventoryId equals inventory.InventoryId
                                 where inventory.AccountId == accountId
                                       && inventory.InventoryType == "CURRENCY"
                                       && !inventory.IsDeleted
                                       && !entry.IsDeleted
                                 select entry).ToListAsync();
            return entries.Sum(entry => entry.ItemId switch
            {
                "gold" or "ast_gold" => entry.Quantity,
                "gold_coin" => entry.Quantity * 10L,
                "gold_ingot" => entry.Quantity * 100L,
                "gold_block" => entry.Quantity * 1_000L,
                "gold_diamond" => entry.Quantity * 10_000L,
                "gold_diamond_block" => entry.Quantity * 100_000L,
                "yggdrasil_star_core" => entry.Quantity * 1_000_000L,
                _ => 0L,
            });
        }

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }

        public sealed record BuyerSetup(Guid AccountId, Guid BagInventoryId);
    }

    private sealed class RecordingPriceService(
        AstralRecordDbContext dbContext,
        CommitResultUnknownInterceptor? commitInterceptor = null) : IMarketPriceService
    {
        public int CallCount { get; private set; }
        public IsolationLevel? ObservedIsolationLevel { get; private set; }

        public Task<MarketPriceQuoteResponse?> CreateQuoteAsync(MarketPriceQuoteRequest request)
        {
            CallCount++;
            ObservedIsolationLevel = dbContext.Database.CurrentTransaction?
                .GetDbTransaction().IsolationLevel;
            commitInterceptor?.Arm();
            return Task.FromResult<MarketPriceQuoteResponse?>(new MarketPriceQuoteResponse
            {
                ItemCategory = request.ItemCategory,
                ItemId = request.ItemId,
                InstanceType = request.InstanceType,
                InstanceId = request.InstanceId,
                SellPrice = 10,
                SuggestedUnitPrice = 100,
                Confidence = "HIGH",
                AllowedMinUnitPrice = 10,
                AllowedMaxUnitPrice = 1_000,
                Judgement = "ALLOW",
                EvaluatedAt = DateTime.UtcNow,
            });
        }
    }

    private static ItemResponse CreateMarketItem(bool unTradeable, bool unSellable, int saleValue) => new()
    {
        SchemaVersion = 1,
        Id = "market_material",
        Category = "material",
        Name = "Market Material",
        Icon = "STONE",
        Rarity = "COMMON",
        SaleValue = saleValue,
        UnTradeable = unTradeable,
        UnSellable = unSellable,
    };

    private sealed class StaticItemRepository(ItemResponse item) : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() =>
        [
            new ItemSummaryResponse { Id = item.Id, Category = item.Category },
        ];

        public ItemResponse? GetById(string itemId) =>
            string.Equals(itemId, item.Id, StringComparison.OrdinalIgnoreCase) ? item : null;
    }

    private sealed class FixedLimitService(int maxListingSlots) : IMarketListingLimitService
    {
        public MarketAccountSummaryResponse BuildSummary(
            MarketAccountStateEntity state,
            int activeListingCount,
            int usedListingSlotCount) => new()
        {
            AccountId = state.AccountId,
            ActiveListingCount = activeListingCount,
            MaxActiveListingCount = maxListingSlots,
            UsedListingSlotCount = usedListingSlotCount,
            MaxListingSlotCount = maxListingSlots,
            CompletedTradeCount = state.CompletedTradeCount,
            Tier = "T0",
            UpdatedAt = state.UpdatedAt,
        };

        public (string Tier, int MaxActiveListingCount) ResolveLimit(int completedTradeCount) =>
            ("T0", maxListingSlots);
    }

    private sealed class CommitResultUnknownRetryingExecutionStrategy(
        ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) =>
            exception is CommitResultUnknownException;
    }

    private sealed class CommitResultUnknownInterceptor : DbTransactionInterceptor
    {
        private bool armed;

        public bool WasThrown { get; private set; }

        public void Arm() => armed = true;

        public override Task TransactionCommittedAsync(
            DbTransaction transaction,
            TransactionEndEventData eventData,
            CancellationToken cancellationToken = default)
        {
            if (armed && !WasThrown)
            {
                armed = false;
                WasThrown = true;
                throw new CommitResultUnknownException();
            }
            return Task.CompletedTask;
        }
    }

    private sealed class CommitResultUnknownException : Exception;
}
