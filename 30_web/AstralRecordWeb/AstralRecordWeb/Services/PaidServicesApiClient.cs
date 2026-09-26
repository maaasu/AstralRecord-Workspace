using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed record PaidApiResult<T>(T? Value, HttpStatusCode Status)
{
    public bool Succeeded => (int)Status is >= 200 and < 300 && Value is not null;
}

public sealed class PaidServicesApiClient(HttpClient client, ILogger<PaidServicesApiClient> logger)
{
    public Task<PaidApiResult<AstraldShopCatalog>> CatalogAsync(CancellationToken ct) =>
        SendAsync<AstraldShopCatalog>(HttpMethod.Get, "/api/web/astrald-shop/catalog", null, ct);

    public Task<PaidApiResult<ChannelBoostSnapshot>> ChannelBoostsAsync(CancellationToken ct) =>
        SendAsync<ChannelBoostSnapshot>(HttpMethod.Get, "/api/channel-boosts", null, ct);

    public Task<PaidApiResult<AccountBenefits>> BenefitsAsync(Guid actor, Guid accountId, CancellationToken ct) =>
        SendAsync<AccountBenefits>(HttpMethod.Get, $"/api/web/account-benefits?actor_user_uuid={actor:D}&account_id={accountId:D}", null, ct);

    public Task<PaidApiResult<IReadOnlyList<VipSupporter>>> VipSupportersAsync(Guid actor, CancellationToken ct) =>
        SendAsync<IReadOnlyList<VipSupporter>>(HttpMethod.Get, $"/api/web/vip-supporters?actor_user_uuid={actor:D}", null, ct);

    public Task<PaidApiResult<AstraldPurchase>> PurchaseAsync(Guid actor, Guid operationId, Guid accountId,
        string itemId, string? channelId, long expectedPricePaidAstrald, CancellationToken ct) =>
        SendAsync<AstraldPurchase>(HttpMethod.Post, $"/api/web/astrald-shop/purchases?actor_user_uuid={actor:D}",
            new { operationId, accountId, itemId, channelId, expectedPricePaidAstrald }, ct);

    public Task<PaidApiResult<AstraldPurchase>> PurchaseResultAsync(Guid actor, Guid operationId, CancellationToken ct) =>
        SendAsync<AstraldPurchase>(HttpMethod.Get, $"/api/web/astrald-shop/purchases/{operationId:D}?actor_user_uuid={actor:D}", null, ct);

    public Task<PaidApiResult<IReadOnlyList<WebMail>>> MailAsync(Guid actor, Guid accountId, CancellationToken ct) =>
        SendAsync<IReadOnlyList<WebMail>>(HttpMethod.Get, $"/api/web/mail?actor_user_uuid={actor:D}&account_id={accountId:D}", null, ct);

    public Task<PaidApiResult<WebMailClaim>> ClaimCurrencyAsync(Guid actor, string mailId, Guid operationId, Guid accountId, CancellationToken ct) =>
        SendAsync<WebMailClaim>(HttpMethod.Post, $"/api/web/mail/{Uri.EscapeDataString(mailId)}/claim-currency?actor_user_uuid={actor:D}",
            new { operationId, accountId }, ct);

    public Task<PaidApiResult<WebMailClaim>> ClaimResultAsync(Guid actor, Guid operationId, CancellationToken ct) =>
        SendAsync<WebMailClaim>(HttpMethod.Get, $"/api/web/mail/claims/{operationId:D}?actor_user_uuid={actor:D}", null, ct);

    private async Task<PaidApiResult<T>> SendAsync<T>(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        try
        {
            using var request = new HttpRequestMessage(method, path);
            if (body is not null) request.Content = JsonContent.Create(body);
            using var response = await client.SendAsync(request, ct);
            T? value;
            try { value = await response.Content.ReadFromJsonAsync<T>(ct); }
            catch (JsonException) when (!response.IsSuccessStatusCode) { return new(default, response.StatusCode); }
            if (!response.IsSuccessStatusCode) return new(value, response.StatusCode);
            return new(value, value is null ? HttpStatusCode.ServiceUnavailable : response.StatusCode);
        }
        catch (Exception ex) when (!ct.IsCancellationRequested && ex is HttpRequestException or TaskCanceledException or JsonException or NotSupportedException)
        {
            logger.LogWarning(ex, "Paid services API request failed.");
            return new(default, HttpStatusCode.ServiceUnavailable);
        }
    }
}
