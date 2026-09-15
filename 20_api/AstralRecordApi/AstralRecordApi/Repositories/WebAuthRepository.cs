using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Repositories;

public class WebAuthRepository(
    AstralRecordDbContext dbContext,
    ManagementDbContext managementDbContext,
    IOptions<WebAuthOptions> options) : IWebAuthRepository
{
    private const string LoginCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private readonly WebAuthOptions webAuthOptions = options.Value;

    public async Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request)
    {
        var user = await dbContext.Users
            .FirstOrDefaultAsync(x => x.Uuid == request.UserUuid && !x.IsDeleted);

        if (user is null)
            return null;

        var now = DateTime.UtcNow;
        var activeChallenges = await dbContext.WebLoginChallenges
            .Where(x =>
                x.UserId == request.UserUuid &&
                x.ConsumedAt == null &&
                x.RevokedAt == null &&
                x.ExpiresAt > now)
            .ToListAsync();

        foreach (var activeChallenge in activeChallenges)
            activeChallenge.RevokedAt = now;

        var loginCode = GenerateLoginCode();
        var entity = new WebLoginChallengeEntity
        {
            ChallengeId = Guid.NewGuid(),
            UserId = user.Uuid,
            LoginCodeHash = HashLoginCode(loginCode),
            IssuedAt = now,
            ExpiresAt = now.AddMinutes(Math.Max(1, webAuthOptions.ChallengeMinutes)),
            FailedAttempts = 0,
            IssuedByServer = request.ServerId.Trim(),
            CreatedAt = now,
        };

        await dbContext.WebLoginChallenges.AddAsync(entity);
        await dbContext.SaveChangesAsync();

        return new WebLoginChallengeCreateResponse
        {
            ChallengeId = entity.ChallengeId,
            LoginCode = loginCode,
            ExpiresAt = entity.ExpiresAt,
            LoginUrl = webAuthOptions.LoginUrl,
        };
    }

    public async Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request)
    {
        var normalizedCode = NormalizeLoginCode(request.LoginCode);
        if (string.IsNullOrWhiteSpace(normalizedCode))
            return null;

        var hash = HashLoginCode(normalizedCode);
        var gameDatabaseStrategy = dbContext.Database.CreateExecutionStrategy();
        var consumed = await gameDatabaseStrategy.ExecuteAsync(async () =>
        {
            await using var transaction = await dbContext.Database.BeginTransactionAsync();
            var now = DateTime.UtcNow;
            var claimed = await dbContext.WebLoginChallenges
                .Where(challenge =>
                    challenge.LoginCodeHash == hash &&
                    challenge.ConsumedAt == null &&
                    challenge.RevokedAt == null &&
                    challenge.ExpiresAt > now)
                .ExecuteUpdateAsync(setters => setters
                    .SetProperty(challenge => challenge.ConsumedAt, now));

            if (claimed != 1)
            {
                await dbContext.WebLoginChallenges
                    .Where(challenge => challenge.LoginCodeHash == hash)
                    .ExecuteUpdateAsync(setters => setters
                        .SetProperty(challenge => challenge.FailedAttempts, challenge => challenge.FailedAttempts + 1));
                await transaction.CommitAsync();
                return null;
            }

            var challenge = await dbContext.WebLoginChallenges
                .AsNoTracking()
                .SingleAsync(item => item.LoginCodeHash == hash);
            var user = await dbContext.Users
                .AsNoTracking()
                .FirstOrDefaultAsync(item => item.Uuid == challenge.UserId && !item.IsDeleted);

            if (user is null)
            {
                await transaction.RollbackAsync();
                return null;
            }

            var accountIds = await dbContext.Accounts
                .AsNoTracking()
                .Where(account => account.UserId == user.Uuid && !account.IsDeleted)
                .OrderBy(account => account.SlotIndex)
                .Select(account => account.Uuid)
                .ToListAsync();

            // Web利用者の保存失敗時は、未確定のコード消費をrollbackして再試行を許可する。
            var webAdmin = await RecordWebLoginAsync(user.Uuid, user.Mcid, now);
            await transaction.CommitAsync();
            return new ConsumedChallenge(user.Uuid, user.Mcid, user.Permission, user.AccountId, accountIds, webAdmin);
        });

        if (consumed is null)
            return null;

        return new WebLoginChallengeConsumeResponse
        {
            UserUuid = consumed.UserUuid,
            Mcid = consumed.Mcid,
            Permission = consumed.Permission,
            WebAdmin = consumed.WebAdmin,
            CurrentAccountId = consumed.CurrentAccountId,
            AccountIds = consumed.AccountIds,
        };
    }

    public async Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid)
    {
        var normalizedMcid = mcid.Trim();
        if (normalizedMcid.Length == 0)
            return WebLoginChallengeUserResolveResult.NotFound();

        var users = await dbContext.Users
            .AsNoTracking()
            .Where(user => user.Mcid == normalizedMcid && !user.IsDeleted)
            .OrderBy(user => user.Uuid)
            .Take(2)
            .ToListAsync();

        return users.Count switch
        {
            0 => WebLoginChallengeUserResolveResult.NotFound(),
            1 => WebLoginChallengeUserResolveResult.Found(new WebLoginChallengeUserResolveResponse
            {
                UserUuid = users[0].Uuid,
                Mcid = users[0].Mcid,
            }),
            _ => WebLoginChallengeUserResolveResult.Ambiguous(),
        };
    }

    public async Task<bool> IsWebAdminAsync(Guid userUuid) =>
        await managementDbContext.Players
            .AsNoTracking()
            .Where(user => user.PlayerUuid == userUuid)
            .Select(user => (bool?)user.WebAdmin)
            .SingleOrDefaultAsync() == true;

    private async Task<bool> RecordWebLoginAsync(Guid userUuid, string mcid, DateTime loginAt)
    {
        var updated = await managementDbContext.Players
            .Where(user => user.PlayerUuid == userUuid)
            .ExecuteUpdateAsync(setters => setters
                .SetProperty(user => user.Mcid, mcid)
                .SetProperty(user => user.FirstWebLoginAt, user => user.FirstWebLoginAt ?? loginAt)
                .SetProperty(user => user.LastWebLoginAt, user => user.LastWebLoginAt > loginAt ? user.LastWebLoginAt : loginAt)
                .SetProperty(user => user.UpdatedAt, user => user.UpdatedAt > loginAt ? user.UpdatedAt : loginAt));

        if (updated == 1)
        {
            return await managementDbContext.Players
                .AsNoTracking()
                .Where(user => user.PlayerUuid == userUuid)
                .Select(user => user.WebAdmin)
                .SingleAsync();
        }

        var created = new ManagementPlayerEntity
        {
            PlayerUuid = userUuid,
            Mcid = mcid,
            WebAdmin = false,
            CreatedAt = loginAt,
            UpdatedAt = loginAt,
            FirstWebLoginAt = loginAt,
            LastWebLoginAt = loginAt,
        };
        await managementDbContext.Players.AddAsync(created);

        try
        {
            await managementDbContext.SaveChangesAsync();
            return false;
        }
        catch (DbUpdateException)
        {
            managementDbContext.ChangeTracker.Clear();
            var updatedAfterInsertRace = await managementDbContext.Players
                .Where(user => user.PlayerUuid == userUuid)
                .ExecuteUpdateAsync(setters => setters
                    .SetProperty(user => user.Mcid, mcid)
                    .SetProperty(user => user.FirstWebLoginAt, user => user.FirstWebLoginAt ?? loginAt)
                    .SetProperty(user => user.LastWebLoginAt, user => user.LastWebLoginAt > loginAt ? user.LastWebLoginAt : loginAt)
                    .SetProperty(user => user.UpdatedAt, user => user.UpdatedAt > loginAt ? user.UpdatedAt : loginAt));
            if (updatedAfterInsertRace != 1)
                throw;

            return await managementDbContext.Players
                .AsNoTracking()
                .Where(user => user.PlayerUuid == userUuid)
                .Select(user => user.WebAdmin)
                .SingleAsync();
        }
    }

    private static string GenerateLoginCode()
    {
        Span<char> chars = stackalloc char[8];
        Span<byte> bytes = stackalloc byte[8];
        RandomNumberGenerator.Fill(bytes);

        for (var i = 0; i < chars.Length; i++)
            chars[i] = LoginCodeAlphabet[bytes[i] % LoginCodeAlphabet.Length];

        return $"{new string(chars[..4])}-{new string(chars[4..])}";
    }

    private static string HashLoginCode(string loginCode)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(NormalizeLoginCode(loginCode)));
        return Convert.ToHexString(bytes);
    }

    private static string NormalizeLoginCode(string loginCode) =>
        loginCode.Trim().Replace(" ", string.Empty).Replace("-", string.Empty).ToUpperInvariant();

    private sealed record ConsumedChallenge(
        Guid UserUuid,
        string Mcid,
        int Permission,
        Guid? CurrentAccountId,
        IReadOnlyList<Guid> AccountIds,
        bool WebAdmin);
}
