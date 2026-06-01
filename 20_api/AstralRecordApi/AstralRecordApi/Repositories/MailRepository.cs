using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class MailRepository(AstralRecordDbContext dbContext, MasterDataDbContext masterDataDbContext) : IMailRepository
{
    private const string MasterTypeMail = "mail";

    public async Task<IReadOnlyList<MailResponse>> GetAvailableByUserIdAsync(Guid userId, string? filter)
    {
        var now = DateTime.UtcNow;
        var masters = await GetMailMastersAsync();
        var states = await dbContext.PlayerMailStates
            .AsNoTracking()
            .Where(state => state.UserId == userId)
            .ToDictionaryAsync(state => state.MailId);

        var normalizedFilter = (filter ?? "all").Trim().ToLowerInvariant();
        return masters
            .Select(master => Merge(master, states.GetValueOrDefault(master.Id)))
            .Where(mail => mail.PublishFrom <= now && (mail.PublishTo is null || mail.PublishTo >= now))
            .Where(mail => !mail.IsDeleted)
            .Where(mail => normalizedFilter switch
            {
                "read" => mail.IsRead,
                "unread" => !mail.IsRead,
                _ => true,
            })
            .OrderByDescending(mail => !mail.IsRead)
            .ThenByDescending(mail => mail.PublishFrom)
            .ThenBy(mail => mail.Id)
            .ToArray();
    }

    public async Task<MailResponse?> MarkReadAsync(string mailId, MailActionRequest request)
    {
        var master = await GetMailMasterByIdAsync(mailId);
        if (master is null)
            return null;

        var state = await GetOrCreateStateAsync(mailId, request.UserId, request.UpdatedBy);
        if (!state.IsRead)
        {
            var now = DateTime.UtcNow;
            state.IsRead = true;
            state.ReadAt = now;
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            state.Version += 1;
        }

        await dbContext.SaveChangesAsync();
        return Merge(master, state);
    }

    public async Task<bool> DeleteAsync(string mailId, MailActionRequest request)
    {
        var master = await GetMailMasterByIdAsync(mailId);
        if (master is null)
            return false;

        var state = await GetOrCreateStateAsync(mailId, request.UserId, request.UpdatedBy);
        if (!state.IsDeleted)
        {
            var now = DateTime.UtcNow;
            state.IsDeleted = true;
            state.DeletedAt = now;
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            state.Version += 1;
        }

        await dbContext.SaveChangesAsync();
        return true;
    }

    private async Task<PlayerMailStateEntity> GetOrCreateStateAsync(string mailId, Guid userId, Guid actor)
    {
        var state = await dbContext.PlayerMailStates
            .FirstOrDefaultAsync(x => x.UserId == userId && x.MailId == mailId);
        if (state is not null)
            return state;

        var now = DateTime.UtcNow;
        state = new PlayerMailStateEntity
        {
            PlayerMailStateId = Guid.NewGuid(),
            UserId = userId,
            MailId = mailId,
            IsRead = false,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = actor,
            UpdatedBy = actor,
            IsDeleted = false,
        };
        await dbContext.PlayerMailStates.AddAsync(state);
        return state;
    }

    private async Task<IReadOnlyList<MailResponse>> GetMailMastersAsync()
    {
        var payloads = await masterDataDbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterTypeMail)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArrayAsync();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<MailResponse>)
            .Where(mail => mail is not null)
            .Select(mail => mail!)
            .ToArray();
    }

    private async Task<MailResponse?> GetMailMasterByIdAsync(string mailId)
    {
        var payload = await masterDataDbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterTypeMail && entry.MasterId == mailId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefaultAsync();

        return payload is null ? null : MasterDataPayloadJson.Deserialize<MailResponse>(payload);
    }

    private static MailResponse Merge(MailResponse master, PlayerMailStateEntity? state) => new()
    {
        SchemaVersion = master.SchemaVersion,
        Id = master.Id,
        Icon = master.Icon,
        Title = master.Title,
        Body = master.Body,
        PublishFrom = master.PublishFrom,
        PublishTo = master.PublishTo,
        ReceiveOnRead = master.ReceiveOnRead,
        Rewards = master.Rewards,
        IsRead = state?.IsRead ?? false,
        ReadAt = state?.ReadAt,
        IsDeleted = state?.IsDeleted ?? false,
    };
}
