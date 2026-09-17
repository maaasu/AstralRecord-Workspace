using System.Security.Cryptography;
using System.Text;
using System.Data.Common;
using AstralRecordApi.Authentication;
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
    IOptions<WebAuthOptions> options,
    WebCodeProofProtector codeProof) : IWebAuthRepository
{
    private const string LoginCodeAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private const string LoginIdAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private const int LoginAttemptLimit = 10;
    private static readonly TimeSpan LoginAttemptWindow = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan LoginAttemptLock = TimeSpan.FromMinutes(15);
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
            var challengedUser = await dbContext.WebLoginChallenges
                .AsNoTracking()
                .Where(challenge => challenge.LoginCodeHash == hash)
                .Select(challenge => (Guid?)challenge.UserId)
                .SingleOrDefaultAsync();
            if (request.ExpectedUserUuid.HasValue && challengedUser != request.ExpectedUserUuid)
            {
                await transaction.RollbackAsync();
                return null;
            }
            var claimed = await dbContext.WebLoginChallenges
                .Where(challenge =>
                    challenge.LoginCodeHash == hash &&
                    challenge.ConsumedAt == null &&
                    challenge.RevokedAt == null &&
                    challenge.ExpiresAt > now &&
                    (!request.ExpectedUserUuid.HasValue || challenge.UserId == request.ExpectedUserUuid.Value))
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
            var sessionVersion = await EnsureCredentialAsync(user.Uuid, now);
            await transaction.CommitAsync();
            return new ConsumedChallenge(user.Uuid, user.Mcid, user.Permission, user.AccountId, accountIds, webAdmin, sessionVersion);
        });

        if (consumed is null)
            return null;

        var codeAt = DateTimeOffset.UtcNow;
        return new WebLoginChallengeConsumeResponse
        {
            CodeAuthenticatedAt = codeAt,
            CodeAuthenticationProof = codeProof.Issue(consumed.UserUuid, consumed.SessionVersion, codeAt),
            UserUuid = consumed.UserUuid,
            Mcid = consumed.Mcid,
            Permission = consumed.Permission,
            WebAdmin = consumed.WebAdmin,
            CurrentAccountId = consumed.CurrentAccountId,
            AccountIds = consumed.AccountIds,
            SessionVersion = consumed.SessionVersion,
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

    public async Task<WebCredentialResponse?> GetCredentialAsync(Guid userUuid)
    {
        var gameUser = await dbContext.Users
            .AsNoTracking()
            .Where(item => item.Uuid == userUuid)
            .Select(item => new { item.IsDeleted })
            .SingleOrDefaultAsync();
        if (gameUser?.IsDeleted == true)
            return null;

        var credential = await managementDbContext.WebCredentials
            .AsNoTracking()
            .Where(item => item.PlayerUuid == userUuid)
            .Select(item => new WebCredentialResponse
            {
                LoginId = item.LoginId,
                Enabled = item.Enabled,
                SessionVersion = item.SessionVersion,
            })
            .SingleOrDefaultAsync();
        return credential;
    }

    public async Task<WebPasswordLoginResult> LoginWithPasswordAsync(WebPasswordLoginRequest request)
    {
        var loginId = NormalizeLoginId(request.LoginId);
        if (loginId.Length == 0 || loginId.Length > 64 || request.Password is null || request.Password.Length > 256)
            return new() { Status = WebPasswordLoginStatus.Invalid };

        var now = DateTime.UtcNow;
        var attempt = await managementDbContext.WebCredentialLoginAttempts
            .AsNoTracking()
            .SingleOrDefaultAsync(item => item.LoginId == loginId);
        if (attempt?.LockedUntilUtc > now)
            return new() { Status = WebPasswordLoginStatus.Throttled };

        var credential = await managementDbContext.WebCredentials
            .AsNoTracking()
            .SingleOrDefaultAsync(item => item.LoginId == loginId);
        if (credential is null || !credential.Enabled || credential.PasswordHash is null)
        {
            WebPasswordHasher.VerifyDummy(request.Password);
            await RecordFailedPasswordAttemptAsync(loginId, now);
            return new() { Status = WebPasswordLoginStatus.Invalid };
        }

        // This is intentionally captured before verification. A concurrent credential mutation then
        // makes this newly issued cookie stale instead of granting it the newer session version.
        var sessionVersion = credential.SessionVersion;
        var verification = WebPasswordHasher.Verify(request.Password, credential.PasswordHash);
        if (verification == WebPasswordVerification.Failed)
        {
            await RecordFailedPasswordAttemptAsync(loginId, now);
            return new() { Status = WebPasswordLoginStatus.Invalid };
        }

        if (verification == WebPasswordVerification.NeedsUpgrade)
        {
            var upgradedHash = WebPasswordHasher.Hash(request.Password);
            await managementDbContext.WebCredentials
                .Where(item => item.PlayerUuid == credential.PlayerUuid &&
                    item.Enabled &&
                    item.SessionVersion == sessionVersion &&
                    item.PasswordHash == credential.PasswordHash)
                .ExecuteUpdateAsync(setters => setters
                    .SetProperty(item => item.PasswordHash, upgradedHash)
                    .SetProperty(item => item.UpdatedAtUtc, now));
        }

        await ClearPasswordAttemptsAsync(loginId);
        var response = await CreatePasswordLoginResponseAsync(credential.PlayerUuid, sessionVersion);
        if (response is null)
            return new() { Status = WebPasswordLoginStatus.Invalid };
        return new() { Status = WebPasswordLoginStatus.Succeeded, Response = response };
    }

    public async Task<WebCredentialUpdateResult> UpdateCredentialAsync(Guid userUuid, WebCredentialUpdateRequest request)
    {
        var credential = await managementDbContext.WebCredentials
            .AsNoTracking()
            .SingleOrDefaultAsync(item => item.PlayerUuid == userUuid);
        if (credential is null)
            return new() { Status = WebCredentialUpdateStatus.NotFound };
        if (request.SessionVersion == Guid.Empty || request.SessionVersion != credential.SessionVersion)
            return new() { Status = WebCredentialUpdateStatus.Stale };

        var now = DateTime.UtcNow;
        var codeAt = codeProof.Validate(request.CodeAuthenticationProof, userUuid, credential.SessionVersion, new DateTimeOffset(now));
        var hasRecentCode = codeAt.HasValue;
        var action = request.Action?.Trim().ToLowerInvariant();
        var isEnable = action == "enable";
        var isChange = action == "change";
        var isDisable = action == "disable";
        if (!isEnable && !isChange && !isDisable)
            return new() { Status = WebCredentialUpdateStatus.Invalid };

        if (isEnable)
        {
            if (!hasRecentCode || credential.Enabled || !IsPasswordAllowed(request.NewPassword, credential.LoginId))
                return new() { Status = WebCredentialUpdateStatus.Invalid };
        }
        else
        {
            if (!credential.Enabled)
                return new() { Status = WebCredentialUpdateStatus.Invalid };
            if (!hasRecentCode)
            {
                var attempt = await managementDbContext.WebCredentialLoginAttempts.AsNoTracking()
                    .SingleOrDefaultAsync(item => item.LoginId == credential.LoginId);
                if (attempt?.LockedUntilUtc > now) return new() { Status = WebCredentialUpdateStatus.Throttled };
                var currentPasswordValid = credential.PasswordHash is not null && request.CurrentPassword is { Length: <= 256 } &&
                    WebPasswordHasher.Verify(request.CurrentPassword, credential.PasswordHash) != WebPasswordVerification.Failed;
                if (!currentPasswordValid)
                {
                    await RecordFailedPasswordAttemptAsync(credential.LoginId!, now);
                    return new() { Status = WebCredentialUpdateStatus.Invalid };
                }
            }
            if (isChange && !IsPasswordAllowed(request.NewPassword, credential.LoginId))
                return new() { Status = WebCredentialUpdateStatus.Invalid };
            if (isDisable && (request.NewPassword is not null || request.CurrentPassword is null && !hasRecentCode))
                return new() { Status = WebCredentialUpdateStatus.Invalid };
        }

        for (var generatedLoginIdAttempts = 0; generatedLoginIdAttempts < 3; generatedLoginIdAttempts++)
        {
            var nextLoginId = credential.LoginId ?? GenerateLoginId();
            var nextVersion = Guid.NewGuid();
            var nextHash = isDisable ? null : WebPasswordHasher.Hash(request.NewPassword!);
            try
            {
                var updated = await managementDbContext.WebCredentials
                    .Where(item => item.PlayerUuid == userUuid && item.SessionVersion == request.SessionVersion)
                    .ExecuteUpdateAsync(setters => setters
                        .SetProperty(item => item.LoginId, nextLoginId)
                        .SetProperty(item => item.PasswordHash, nextHash)
                        .SetProperty(item => item.Enabled, !isDisable)
                        .SetProperty(item => item.SessionVersion, nextVersion)
                        .SetProperty(item => item.UpdatedAtUtc, now));
                if (updated == 0)
                    return new() { Status = WebCredentialUpdateStatus.Stale };

                await ClearPasswordAttemptsAsync(nextLoginId);
                return new()
                {
                    Status = WebCredentialUpdateStatus.Succeeded,
                    Response = new WebCredentialResponse
                    {
                        LoginId = nextLoginId, Enabled = !isDisable, SessionVersion = nextVersion,
                        // 世代だけを更新し、本人確認の期限は延長しない。
                        CodeAuthenticatedAt = codeAt,
                        CodeAuthenticationProof = codeAt.HasValue ? codeProof.Issue(userUuid, nextVersion, codeAt.Value) : null,
                    },
                };
            }
            catch (DbException) when (credential.LoginId is null)
            {
                managementDbContext.ChangeTracker.Clear();
            }
        }
        return new() { Status = WebCredentialUpdateStatus.Invalid };
    }

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

    private async Task<Guid> EnsureCredentialAsync(Guid userUuid, DateTime now)
    {
        var existing = await managementDbContext.WebCredentials
            .AsNoTracking()
            .Where(item => item.PlayerUuid == userUuid)
            .Select(item => (Guid?)item.SessionVersion)
            .SingleOrDefaultAsync();
        if (existing.HasValue)
            return existing.Value;

        var created = new WebCredentialEntity
        {
            PlayerUuid = userUuid,
            Enabled = false,
            SessionVersion = Guid.NewGuid(),
            CreatedAtUtc = now,
            UpdatedAtUtc = now,
        };
        managementDbContext.WebCredentials.Add(created);
        try
        {
            await managementDbContext.SaveChangesAsync();
            return created.SessionVersion;
        }
        catch (DbUpdateException)
        {
            managementDbContext.ChangeTracker.Clear();
            return await managementDbContext.WebCredentials
                .AsNoTracking()
                .Where(item => item.PlayerUuid == userUuid)
                .Select(item => item.SessionVersion)
                .SingleAsync();
        }
    }

    private async Task<WebLoginChallengeConsumeResponse?> CreatePasswordLoginResponseAsync(Guid userUuid, Guid sessionVersion)
    {
        var gameUser = await dbContext.Users
            .AsNoTracking()
            .FirstOrDefaultAsync(user => user.Uuid == userUuid);
        if (gameUser is { IsDeleted: true })
            return null;

        if (gameUser is null)
        {
            var managementPlayer = await managementDbContext.Players
                .AsNoTracking()
                .SingleOrDefaultAsync(player => player.PlayerUuid == userUuid);
            if (managementPlayer is null)
                return null;
            return new WebLoginChallengeConsumeResponse
            {
                UserUuid = userUuid,
                Mcid = managementPlayer.Mcid,
                Permission = 0,
                WebAdmin = managementPlayer.WebAdmin,
                AccountIds = [],
                SessionVersion = sessionVersion,
            };
        }

        var accountIds = await dbContext.Accounts
            .AsNoTracking()
            .Where(account => account.UserId == userUuid && !account.IsDeleted)
            .OrderBy(account => account.SlotIndex)
            .Select(account => account.Uuid)
            .ToListAsync();
        var webAdmin = await IsWebAdminAsync(userUuid);
        return new WebLoginChallengeConsumeResponse
        {
            UserUuid = userUuid,
            Mcid = gameUser.Mcid,
            Permission = gameUser.Permission,
            WebAdmin = webAdmin,
            CurrentAccountId = gameUser.AccountId,
            AccountIds = accountIds,
            SessionVersion = sessionVersion,
        };
    }

    private async Task RecordFailedPasswordAttemptAsync(string loginId, DateTime now)
    {
        for (var retry = 0; retry < 4; retry++)
        {
            var attempt = await managementDbContext.WebCredentialLoginAttempts
                .SingleOrDefaultAsync(item => item.LoginId == loginId);
            if (attempt is null)
            {
                managementDbContext.WebCredentialLoginAttempts.Add(new WebCredentialLoginAttemptEntity
                {
                    LoginId = loginId,
                    FailedAttempts = 1,
                    WindowStartedAtUtc = now,
                    Revision = 1,
                });
            }
            else if (attempt.WindowStartedAtUtc.Add(LoginAttemptWindow) <= now)
            {
                attempt.FailedAttempts = 1;
                attempt.WindowStartedAtUtc = now;
                attempt.LockedUntilUtc = null;
                attempt.Revision++;
            }
            else
            {
                attempt.FailedAttempts++;
                if (attempt.FailedAttempts >= LoginAttemptLimit)
                    attempt.LockedUntilUtc = now.Add(LoginAttemptLock);
                attempt.Revision++;
            }

            try
            {
                await managementDbContext.SaveChangesAsync();
                return;
            }
            catch (DbUpdateException)
            {
                managementDbContext.ChangeTracker.Clear();
            }
        }
    }

    private async Task ClearPasswordAttemptsAsync(string loginId)
    {
        await managementDbContext.WebCredentialLoginAttempts
            .Where(item => item.LoginId == loginId)
            .ExecuteDeleteAsync();
        foreach (var entry in managementDbContext.ChangeTracker.Entries<WebCredentialLoginAttemptEntity>()
                     .Where(entry => entry.Entity.LoginId == loginId).ToList())
            entry.State = EntityState.Detached;
    }

    private static string NormalizeLoginId(string? loginId) => loginId?.Trim().ToUpperInvariant() ?? string.Empty;

    private static string GenerateLoginId()
    {
        Span<byte> bytes = stackalloc byte[10];
        RandomNumberGenerator.Fill(bytes);
        var chars = new char[10];
        for (var index = 0; index < chars.Length; index++)
            chars[index] = LoginIdAlphabet[bytes[index] % LoginIdAlphabet.Length];
        return $"AR-{new string(chars)}";
    }

    private static bool IsPasswordAllowed(string? password, string? loginId)
    {
        if (password is null)
            return false;
        var scalarLength = password.EnumerateRunes().Count();
        if (scalarLength is < 15 or > 128)
            return false;

        var folded = password.ToUpperInvariant();
        var common = new[] { "PASSWORD", "QWERTY", "LETMEIN", "WELCOME", "ADMIN", "ILOVEYOU", "123456", "ABCDEFG" };
        if (common.Any(value => folded.Contains(value, StringComparison.Ordinal)) ||
            (loginId is not null && folded.Contains(loginId, StringComparison.Ordinal)) ||
            password.Distinct().Take(2).Count() < 2 ||
            HasPredictableRun(password))
            return false;
        return true;
    }

    private static bool HasPredictableRun(string password)
    {
        var run = 1;
        for (var index = 1; index < password.Length; index++)
        {
            if (password[index] == password[index - 1] + 1 || password[index] == password[index - 1] - 1)
            {
                if (++run >= 6) return true;
            }
            else run = 1;
        }
        return false;
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
        bool WebAdmin,
        Guid SessionVersion);
}
