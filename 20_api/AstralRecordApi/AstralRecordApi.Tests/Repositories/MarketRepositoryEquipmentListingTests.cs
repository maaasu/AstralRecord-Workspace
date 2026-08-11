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
    public async Task CreateListing_RejectsEquipmentWithoutCurrentMembershipBeforeQuote()
    {
        await using var harness = await MarketHarness.CreateAsync(addMembership: false);

        var result = await harness.Repository.CreateListingAsync(harness.CreateRequest());

        Assert.False(result.Succeeded);
        Assert.Equal("market.instance_not_present", result.ErrorCode);
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

    private sealed class MarketHarness : IAsyncDisposable
    {
        private readonly SqliteConnection connection;

        private MarketHarness(
            SqliteConnection connection,
            AstralRecordDbContext dbContext,
            RecordingPriceService priceService,
            Guid accountId,
            Guid equipmentInstanceId)
        {
            this.connection = connection;
            DbContext = dbContext;
            PriceService = priceService;
            AccountId = accountId;
            EquipmentInstanceId = equipmentInstanceId;
            Repository = new MarketRepository(dbContext, priceService, new FixedLimitService());
        }

        public AstralRecordDbContext DbContext { get; }
        public RecordingPriceService PriceService { get; }
        public MarketRepository Repository { get; }
        public Guid AccountId { get; }
        public Guid EquipmentInstanceId { get; }

        public static async Task<MarketHarness> CreateAsync(
            bool addMembership,
            CommitResultUnknownInterceptor? commitInterceptor = null)
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
                dbContext.InventoryEntries.Add(new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = inventoryId,
                    SlotIndex = 1,
                    ItemCategory = "equipment",
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
                equipmentInstanceId);
        }

        public MarketListingCreateRequest CreateRequest() => new()
        {
            SellerAccountId = AccountId,
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
            SourceInventoryEntryId = entryId,
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

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }
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

    private sealed class FixedLimitService : IMarketListingLimitService
    {
        public MarketAccountSummaryResponse BuildSummary(
            MarketAccountStateEntity state,
            int activeListingCount) => new()
        {
            AccountId = state.AccountId,
            ActiveListingCount = activeListingCount,
            MaxActiveListingCount = 10,
            CompletedTradeCount = state.CompletedTradeCount,
            Tier = "T0",
            UpdatedAt = state.UpdatedAt,
        };

        public (string Tier, int MaxActiveListingCount) ResolveLimit(int completedTradeCount) =>
            ("T0", 10);
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
