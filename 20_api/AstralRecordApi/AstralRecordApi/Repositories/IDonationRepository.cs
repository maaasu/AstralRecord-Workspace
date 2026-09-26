using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IDonationRepository
{
    Task<DonationListResponse> ListAsync(Guid actor, bool all, int page, int pageSize);
    Task<DonationResponse?> GetAsync(Guid id, Guid actor, bool admin);
    Task<DonationResponse> CreateAsync(Guid user, DonationCreateRequest request);
    Task<DonationResponse> TransitionAsync(Guid id, Guid actor, string action, int? amount = null, string? reason = null);
    Task<IReadOnlyList<DonationNotificationResponse>> NotificationsAsync(Guid user);
    Task AcknowledgeAsync(Guid user, Guid id);
    Task ReconcileAsync(CancellationToken cancellationToken);
}
