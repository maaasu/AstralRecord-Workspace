using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class WebMailRepository(AstralRecordDbContext db, IMailRepository mail,
    IItemRepository items, TimeProvider clock) : IWebMailRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<IReadOnlyList<MailResponse>?> ListAsync(Guid actor, Guid accountId, string? filter) =>
        await IsCurrentAsync(actor, accountId) ? await mail.GetAvailableByAccountIdAsync(accountId, filter) : null;

    public async Task<WebMailCurrencyClaimResponse> CreateAsync(Guid actor, string mailId,
        WebMailCurrencyClaimRequest request)
    {
        mailId = mailId.Trim();
        var hash = Hash(actor, request.AccountId, mailId);
        if (actor == Guid.Empty || request.OperationId == Guid.Empty || request.AccountId == Guid.Empty || mailId.Length is < 1 or > 200)
            return Rejected("invalid_request");
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var tx = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var account = await LockedAccountAsync(request.AccountId);
            if (account is null || account.IsDeleted || account.UserId != actor)
                return Rejected("account_not_current");
            var existing = await LockedClaimAsync(request.OperationId);
            if (existing is not null)
                return existing.ActorUserUuid == actor && existing.RequestHash == hash
                    ? Receipt(existing) : Rejected("operation_conflict");
            if (!await IsCurrentAsync(actor, request.AccountId))
                return Rejected("account_not_current");
            var already = await db.WebMailCurrencyClaims.AsNoTracking()
                .AnyAsync(x => x.AccountId == request.AccountId && x.MailId == mailId && x.Status != "REJECTED");
            if (already) return Rejected("already_claimed");
            var letter = (await mail.GetAvailableByAccountIdAsync(request.AccountId, "unread"))
                .FirstOrDefault(x => x.Id == mailId);
            if (letter is null || !letter.CanClaimCurrency) return Rejected("mail_unavailable");
            if (letter.Rewards.Where(x => x.Category.Equals("CURRENCY", StringComparison.OrdinalIgnoreCase))
                .Any(x => x.Amount <= 0 || x.InstanceId is not null || items.GetById(x.ItemId)?.Category != "currency"))
                return Rejected("invalid_currency_reward");
            var now = Now();
            var operation = new WebMailCurrencyClaimEntity
            {
                OperationId = request.OperationId, ActorUserUuid = actor,
                AccountId = request.AccountId, MailId = mailId, RequestHash = hash,
                CurrencyRewardsJson = JsonSerializer.Serialize(letter.Rewards.Where(x =>
                    x.Category.Equals("CURRENCY", StringComparison.OrdinalIgnoreCase)).ToArray(), JsonOptions),
                HasNonCurrencyRewards = letter.Rewards.Any(x => !x.Category.Equals("CURRENCY", StringComparison.OrdinalIgnoreCase)),
                Status = "PENDING", CreatedAt = now, UpdatedAt = now,
            };
            db.WebMailCurrencyClaims.Add(operation);
            if (!await IsOnlineAsync(request.AccountId))
                await ApplyAsync(operation, account, letter, now);
            await db.SaveChangesAsync();
            await tx.CommitAsync();
            return Receipt(operation);
        });

        WebMailCurrencyClaimResponse Rejected(string reason) =>
            new(request.OperationId, "REJECTED", reason, mailId, request.AccountId);
    }

    public async Task<WebMailCurrencyClaimResponse?> GetAsync(Guid actor, Guid operationId)
    {
        var row = await db.WebMailCurrencyClaims.AsNoTracking()
            .SingleOrDefaultAsync(x => x.OperationId == operationId && x.ActorUserUuid == actor);
        return row is null ? null : Receipt(row);
    }

    public async Task<IReadOnlyList<WebMailClaimPendingResponse>> PendingAsync(Guid accountId) =>
        await db.WebMailCurrencyClaims.AsNoTracking().Where(x => x.AccountId == accountId && x.Status == "PENDING")
            .OrderBy(x => x.CreatedAt).Select(x => new WebMailClaimPendingResponse(x.OperationId, x.AccountId)).ToArrayAsync();

    public async Task<WebMailCurrencyClaimResponse?> ProcessAsync(Guid operationId, WebMailClaimProcessRequest request)
    {
        if (!request.PreparedOnline || request.AccountId == Guid.Empty) return null;
        return await db.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            db.ChangeTracker.Clear();
            await using var tx = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var account = await LockedAccountAsync(request.AccountId);
            if (account is null || account.IsDeleted) return null;
            var operation = await LockedClaimAsync(operationId);
            if (operation is null || operation.AccountId != account.Uuid || operation.ActorUserUuid != account.UserId)
                return null;
            if (operation.Status == "PENDING")
            {
                if (!await IsCurrentAsync(account.UserId, account.Uuid)) Fail(operation, "account_not_current");
                else
                {
                    var letter = (await mail.GetAvailableByAccountIdAsync(account.Uuid, "unread"))
                        .FirstOrDefault(x => x.Id == operation.MailId);
                    if (letter is null || !letter.CanClaimCurrency) Fail(operation, "mail_unavailable");
                    else await ApplyAsync(operation, account, letter, Now());
                }
                await db.SaveChangesAsync();
            }
            await tx.CommitAsync();
            return await FreshProcessReceiptAsync(operation);
        });
    }

    private async Task ApplyAsync(WebMailCurrencyClaimEntity operation, AccountEntity account,
        MailResponse letter, DateTime now)
    {
        var rewards = JsonSerializer.Deserialize<MailRewardResponse[]>(operation.CurrencyRewardsJson, JsonOptions) ?? [];
        if (rewards.Length == 0 || rewards.Any(x => x.Amount <= 0 || x.InstanceId is not null
            || !string.Equals(x.Category, "CURRENCY", StringComparison.OrdinalIgnoreCase)))
        { Fail(operation, "invalid_currency_reward"); return; }
        var inventory = db.Database.IsSqlServer()
            ? await db.Inventories.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory] WITH (UPDLOCK,HOLDLOCK) WHERE [account_id] = {account.Uuid} AND [inventory_profile] = 'GAME' AND [inventory_type] = 'CURRENCY' AND [is_deleted] = 0 AND [is_enabled] = 1").SingleOrDefaultAsync()
            : await db.Inventories.SingleOrDefaultAsync(x => x.AccountId == account.Uuid && x.InventoryProfile == "GAME" && x.InventoryType == "CURRENCY" && !x.IsDeleted && x.IsEnabled);
        if (inventory is null) { Fail(operation, "currency_inventory_missing"); return; }
        var entries = db.Database.IsSqlServer()
            ? await db.InventoryEntries.FromSqlInterpolated($"SELECT * FROM [dbo].[inventory_entry] WITH (UPDLOCK,HOLDLOCK) WHERE [inventory_id] = {inventory.InventoryId} AND [is_deleted] = 0").ToListAsync()
            : await db.InventoryEntries.Where(x => x.InventoryId == inventory.InventoryId && !x.IsDeleted).ToListAsync();
        var affected = new List<Guid>();
        foreach (var reward in rewards)
        {
            var entry = entries.FirstOrDefault(x => string.Equals(x.ItemId, reward.ItemId, StringComparison.OrdinalIgnoreCase));
            if (entry is null)
            {
                entry = new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(), InventoryId = inventory.InventoryId,
                    ItemCategory = "currency", ItemId = reward.ItemId, Quantity = 0,
                    CreatedAt = now, CreatedBy = account.Uuid,
                };
                entries.Add(entry);
                db.InventoryEntries.Add(entry);
            }
            entry.Quantity = checked(entry.Quantity + reward.Amount);
            entry.UpdatedAt = now;
            entry.UpdatedBy = account.Uuid;
            affected.Add(entry.InventoryEntryId);
        }
        if (!operation.HasNonCurrencyRewards)
        {
            var state = await db.PlayerMailStates.SingleOrDefaultAsync(x => x.AccountId == account.Uuid && x.MailId == operation.MailId);
            if (state is null)
            {
                state = new PlayerMailStateEntity
                {
                    PlayerMailStateId = Guid.NewGuid(), AccountId = account.Uuid, MailId = operation.MailId,
                    IsRead = true, ReadAt = now, Version = 2, CreatedAt = now,
                    UpdatedAt = now, CreatedBy = account.Uuid, UpdatedBy = account.Uuid,
                };
                db.PlayerMailStates.Add(state);
            }
            else
            {
                state.IsRead = true; state.ReadAt = now; state.Version++;
                state.UpdatedAt = now; state.UpdatedBy = account.Uuid;
            }
        }
        operation.Status = "COMPLETED";
        operation.UpdatedAt = now;
        await db.SaveChangesAsync();
        var snapshot = await InventoryOperationSnapshotReader.ReadAsync(db, account.Uuid, affected, includeCurrency: true);
        operation.ResponseJson = JsonSerializer.Serialize(new WebMailCurrencyClaimResponse(operation.OperationId,
            "COMPLETED", null, operation.MailId, account.Uuid, affected.Distinct().ToArray(), snapshot), JsonOptions);
    }

    private Task<bool> IsCurrentAsync(Guid actor, Guid accountId) => db.Users.AsNoTracking()
        .AnyAsync(x => x.Uuid == actor && !x.IsDeleted && x.AccountId == accountId);
    private Task<bool> IsOnlineAsync(Guid accountId) => db.SkillTreeAccountSessions.AsNoTracking()
        .AnyAsync(x => x.AccountId == accountId && !x.Closed);

    private async Task<WebMailCurrencyClaimResponse> FreshProcessReceiptAsync(WebMailCurrencyClaimEntity operation)
    {
        var receipt = Receipt(operation);
        if (receipt.Status != "COMPLETED" || receipt.AffectedInventoryEntryIds is null) return receipt;
        var snapshot = await InventoryOperationSnapshotReader.ReadAsync(db, operation.AccountId,
            receipt.AffectedInventoryEntryIds, includeCurrency: true);
        return receipt with { InventorySnapshot = snapshot };
    }
    private async Task<AccountEntity?> LockedAccountAsync(Guid id) => db.Database.IsSqlServer()
        ? await db.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {id}").SingleOrDefaultAsync()
        : await db.Accounts.SingleOrDefaultAsync(x => x.Uuid == id);
    private async Task<WebMailCurrencyClaimEntity?> LockedClaimAsync(Guid id) => db.Database.IsSqlServer()
        ? await db.WebMailCurrencyClaims.FromSqlInterpolated($"SELECT * FROM [dbo].[web_mail_currency_claim] WITH (UPDLOCK,HOLDLOCK) WHERE [operation_id] = {id}").SingleOrDefaultAsync()
        : await db.WebMailCurrencyClaims.SingleOrDefaultAsync(x => x.OperationId == id);
    private DateTime Now()
    {
        var now = clock.GetUtcNow().UtcDateTime;
        return new DateTime(now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }
    private static string Hash(Guid actor, Guid account, string mailId) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{actor:D}|{account:D}|{mailId}")));
    private static void Fail(WebMailCurrencyClaimEntity operation, string reason)
    { operation.Status = "REJECTED"; operation.Reason = reason; operation.UpdatedAt = DateTime.UtcNow; }
    private static WebMailCurrencyClaimResponse Receipt(WebMailCurrencyClaimEntity operation) =>
        operation.ResponseJson is { } json ? JsonSerializer.Deserialize<WebMailCurrencyClaimResponse>(json, JsonOptions)!
            : new(operation.OperationId, operation.Status, operation.Reason, operation.MailId, operation.AccountId);
}
