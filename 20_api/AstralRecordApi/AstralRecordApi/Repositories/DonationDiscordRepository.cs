using System.Security.Cryptography;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class DonationDiscordRepository : IDonationDiscordRepository
{
    private readonly ManagementDbContext db;
    private readonly DiscordDonationClient discord;
    private readonly IDataProtector protector;
    private readonly TimeProvider clock;

    public DonationDiscordRepository(
        ManagementDbContext db, DiscordDonationClient discord,
        IDataProtectionProvider protection, TimeProvider clock)
    {
        this.db = db;
        this.discord = discord;
        protector = protection.CreateProtector("AstralRecordApi.Donations.DiscordTokens.v1");
        this.clock = clock;
    }

    public async Task<DonationDiscordLinkResponse?> GetAsync(Guid userUuid)
    {
        ValidateUser(userUuid);
        var row = await db.Set<DonationDiscordLinkEntity>().AsNoTracking()
            .SingleOrDefaultAsync(x => x.UserUuid == userUuid);
        return row is null ? null : Response(row);
    }

    public async Task<DonationDiscordLinkResponse> LinkAsync(Guid userUuid, DonationDiscordLinkRequest request)
    {
        ValidateUser(userUuid);
        if (request is null || string.IsNullOrWhiteSpace(request.AccessToken)
            || string.IsNullOrWhiteSpace(request.RefreshToken)
            || request.AccessToken.Length > 2048 || request.RefreshToken.Length > 2048)
            throw new ArgumentException("Discord OAuth tokens are required.", nameof(request));
        discord.EnsureConfigured();
        DiscordDonationUser identity;
        bool member;
        try
        {
            identity = await discord.GetCurrentUserAsync(request.AccessToken);
            member = await discord.IsGuildMemberAsync(request.AccessToken);
        }
        catch (DiscordDonationTokenRejectedException)
        {
            throw new UnauthorizedAccessException("Discord authorization is invalid.");
        }
        if (!member) throw new UnauthorizedAccessException("Discord guild membership is required.");

        var links = db.Set<DonationDiscordLinkEntity>();
        if (await links.AsNoTracking().AnyAsync(x => x.DiscordUserId == identity.Id && x.UserUuid != userUuid))
            throw new ArgumentException("This Discord account is already linked to another user.", nameof(request));
        var row = await links.SingleOrDefaultAsync(x => x.UserUuid == userUuid);
        if (row is null)
        {
            row = new DonationDiscordLinkEntity { UserUuid = userUuid };
            links.Add(row);
        }
        row.DiscordUserId = identity.Id;
        row.DiscordName = identity.Name;
        row.ProtectedAccessToken = protector.Protect(request.AccessToken);
        row.ProtectedRefreshToken = protector.Protect(request.RefreshToken);
        row.IsGuildMember = true;
        row.VerifiedAtUtc = clock.GetUtcNow().UtcDateTime;
        row.Revision++;
        try { await db.SaveChangesAsync(); }
        catch (DbUpdateConcurrencyException)
        {
            db.ChangeTracker.Clear();
            throw new InvalidOperationException("Discord link changed concurrently. Please retry.");
        }
        catch (DbUpdateException ex) when (IsUniqueViolation(ex))
        {
            db.ChangeTracker.Clear();
            throw new ArgumentException("This Discord account is already linked to another user.", nameof(request));
        }
        return Response(row);
    }

    public async Task<DonationDiscordLinkResponse> VerifyMembershipAsync(Guid userUuid)
    {
        ValidateUser(userUuid);
        discord.EnsureConfigured();
        for (var attempt = 0; attempt < 3; attempt++)
        {
            db.ChangeTracker.Clear();
            var row = await db.Set<DonationDiscordLinkEntity>().SingleOrDefaultAsync(x => x.UserUuid == userUuid)
                ?? throw new UnauthorizedAccessException("Discord account is not linked.");
            string access;
            try { access = protector.Unprotect(row.ProtectedAccessToken); }
            catch (CryptographicException)
            {
                throw new InvalidOperationException("Stored Discord authorization cannot be read.");
            }
            try
            {
                var member = await discord.IsGuildMemberAsync(access);
                row.IsGuildMember = member;
                row.VerifiedAtUtc = clock.GetUtcNow().UtcDateTime;
                row.Revision++;
                try { await db.SaveChangesAsync(); }
                catch (DbUpdateConcurrencyException) { continue; }
                if (!member) throw new UnauthorizedAccessException("Discord guild membership is required.");
                return Response(row);
            }
            catch (DiscordDonationTokenRejectedException)
            {
                // A second API instance may have rotated this token. Reload before attempting refresh.
                if (await HasNewerRevisionAsync(userUuid, row.Revision)) continue;
                DiscordDonationTokens refreshed;
                try { refreshed = await discord.RefreshAsync(UnprotectRefresh(row)); }
                catch (DiscordDonationTokenRejectedException)
                {
                    // Discord may reject a concurrently consumed refresh token before its replacement is committed.
                    for (var wait = 0; wait < 3; wait++)
                    {
                        await Task.Delay(100);
                        if (await HasNewerRevisionAsync(userUuid, row.Revision))
                            goto Retry;
                    }
                    // Do not overwrite a replacement token that may still be in flight on another instance.
                    throw new UnauthorizedAccessException("Discord authorization has expired. Reconnect Discord.");
                }
                DiscordDonationUser identity;
                bool member;
                try
                {
                    identity = await discord.GetCurrentUserAsync(refreshed.AccessToken);
                    member = await discord.IsGuildMemberAsync(refreshed.AccessToken);
                }
                catch (DiscordDonationTokenRejectedException)
                {
                    throw new UnauthorizedAccessException("Discord authorization is invalid.");
                }
                if (identity.Id != row.DiscordUserId)
                    throw new UnauthorizedAccessException("Discord account identity changed. Reconnect Discord.");
                row.DiscordName = identity.Name;
                row.ProtectedAccessToken = protector.Protect(refreshed.AccessToken);
                row.ProtectedRefreshToken = protector.Protect(refreshed.RefreshToken);
                row.IsGuildMember = member;
                row.VerifiedAtUtc = clock.GetUtcNow().UtcDateTime;
                row.Revision++;
                try { await db.SaveChangesAsync(); }
                catch (DbUpdateConcurrencyException) { continue; }
                if (!member) throw new UnauthorizedAccessException("Discord guild membership is required.");
                return Response(row);
            }
            Retry:;
        }
        throw new InvalidOperationException("Discord authorization changed concurrently. Please retry.");
    }

    private async Task<bool> HasNewerRevisionAsync(Guid userUuid, int revision)
        => await db.Set<DonationDiscordLinkEntity>().AsNoTracking()
            .AnyAsync(x => x.UserUuid == userUuid && x.Revision > revision);

    private string UnprotectRefresh(DonationDiscordLinkEntity row)
    {
        try { return protector.Unprotect(row.ProtectedRefreshToken); }
        catch (CryptographicException)
        {
            throw new InvalidOperationException("Stored Discord authorization cannot be read.");
        }
    }

    private static bool IsUniqueViolation(DbUpdateException ex)
        => ex.InnerException is SqlException { Number: 2601 or 2627 };

    private static void ValidateUser(Guid userUuid)
    {
        if (userUuid == Guid.Empty) throw new ArgumentException("A user UUID is required.", nameof(userUuid));
    }

    private static DonationDiscordLinkResponse Response(DonationDiscordLinkEntity row)
        => new(row.DiscordUserId, row.DiscordName, row.IsGuildMember, row.VerifiedAtUtc);
}
