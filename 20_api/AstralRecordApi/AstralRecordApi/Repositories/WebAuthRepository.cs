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
        var challenge = await dbContext.WebLoginChallenges
            .FirstOrDefaultAsync(x => x.LoginCodeHash == hash);

        if (challenge is null)
            return null;

        var now = DateTime.UtcNow;
        if (challenge.ConsumedAt is not null || challenge.RevokedAt is not null || challenge.ExpiresAt <= now)
        {
            challenge.FailedAttempts++;
            await dbContext.SaveChangesAsync();
            return null;
        }

        var user = await dbContext.Users
            .AsNoTracking()
            .FirstOrDefaultAsync(x => x.Uuid == challenge.UserId && !x.IsDeleted);

        if (user is null)
            return null;

        var accountIds = await dbContext.Accounts
            .AsNoTracking()
            .Where(x => x.UserId == user.Uuid && !x.IsDeleted)
            .OrderBy(x => x.SlotIndex)
            .Select(x => x.Uuid)
            .ToListAsync();

        challenge.ConsumedAt = now;
        await dbContext.SaveChangesAsync();

        return new WebLoginChallengeConsumeResponse
        {
            UserUuid = user.Uuid,
            Mcid = user.Mcid,
            Permission = user.Permission,
            CurrentAccountId = user.AccountId,
            AccountIds = accountIds,
        };
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
}
