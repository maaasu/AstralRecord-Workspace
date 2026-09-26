using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IDonationDiscordRepository
{
    Task<DonationDiscordLinkResponse?> GetAsync(Guid userUuid);
    Task<DonationDiscordLinkResponse> LinkAsync(Guid userUuid, DonationDiscordLinkRequest request);
    Task<DonationDiscordLinkResponse> VerifyMembershipAsync(Guid userUuid);
}
