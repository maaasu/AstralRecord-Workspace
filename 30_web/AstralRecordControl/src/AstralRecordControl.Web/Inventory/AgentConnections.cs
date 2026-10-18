using System.Collections.Concurrent;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.Extensions.Logging;

namespace AstralRecordControl.Web.Inventory;

public sealed record AgentIdentity(Guid HostId, Guid CredentialId)
{
    public static AgentIdentity From(ClaimsPrincipal principal)
    {
        if (principal.Identity?.AuthenticationType != AgentBearerHandler.SchemeName || !Guid.TryParse(principal.FindFirstValue("host_id"), out var host) || !Guid.TryParse(principal.FindFirstValue("credential_id"), out var credential)) throw new InventoryFault(403, "AgentOnly");
        return new(host, credential);
    }
}
public sealed class AgentCredentialService(ControlDbContext db, InventoryStore store, AgentConnectionRegistry connections, TimeProvider clock)
{
    public async Task<CredentialIssued> IssueAsync(Guid hostId) => await store.WriteAsync(async () =>
    {
        var host = await store.HostAsync(hostId);
        if (!host.Enabled) throw new InventoryFault(409, "AgentDisabled");
        var token = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        var credential = new InventoryCredential { CredentialId = Guid.NewGuid(), HostId = hostId, TokenHashHex = ControlProtocol.Hash(token), IssuedAtUtc = clock.GetUtcNow() };
        db.AgentCredentials.Add(credential); return new CredentialIssued(credential.CredentialId, token);
    });
    public async Task RevokeAsync(Guid hostId, Guid credentialId)
    {
        await store.WriteAsync(async () =>
        {
            var row = await db.AgentCredentials.SingleOrDefaultAsync(x => x.HostId == hostId && x.CredentialId == credentialId) ?? throw new InventoryFault(404, "NotFound");
            row.RevokedAtUtc ??= clock.GetUtcNow();
            var host = await store.HostAsync(hostId);
            if (connections.IsCredentialActive(hostId, credentialId)) { host.ConnectionEpoch = null; host.ConnectionState = HostConnectionState.Offline; }
            return true;
        });
        connections.RevokeCredential(credentialId);
    }
    public async Task<AgentIdentity?> AuthenticateAsync(string token)
    {
        if (token.Length != 64 || token.Any(c => !char.IsAsciiHexDigit(c))) return null;
        var hash = ControlProtocol.Hash(token);
        var row = await db.AgentCredentials.AsNoTracking().SingleOrDefaultAsync(x => x.TokenHashHex == hash && x.RevokedAtUtc == null);
        return row != null && await db.Hosts.AsNoTracking().AnyAsync(x => x.HostId == row.HostId && x.Enabled) ? new(row.HostId, row.CredentialId) : null;
    }
    public async Task EnsureActiveAsync(AgentIdentity identity)
    {
        if (!await db.AgentCredentials.AsNoTracking().AnyAsync(x => x.CredentialId == identity.CredentialId && x.HostId == identity.HostId && x.RevokedAtUtc == null) || !await db.Hosts.AsNoTracking().AnyAsync(x => x.HostId == identity.HostId && x.Enabled)) throw new InventoryFault(401, "AgentDisabled");
    }
}

public sealed class AgentBearerHandler(IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger, UrlEncoder encoder, AgentCredentialService credentials) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "AgentBearer";
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (!header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return AuthenticateResult.NoResult();
        var identity = await credentials.AuthenticateAsync(header[7..]);
        if (identity == null) return AuthenticateResult.Fail("Invalid Agent credential.");
        var principal = new ClaimsPrincipal(new ClaimsIdentity([new Claim("host_id", identity.HostId.ToString()), new Claim("credential_id", identity.CredentialId.ToString())], SchemeName));
        return AuthenticateResult.Success(new AuthenticationTicket(principal, SchemeName));
    }
}

public sealed record AgentConnection(Guid HostId, Guid CredentialId, Guid Epoch, string ConnectionId, Action Abort);
public sealed class AgentConnectionRegistry
{
    private readonly ConcurrentDictionary<Guid, AgentConnection> entries = new();
    public void Register(AgentConnection connection)
    {
        AgentConnection? replaced = null;
        entries.AddOrUpdate(connection.HostId, connection, (_, old) => { replaced = old; return connection; });
        replaced?.Abort();
    }
    public AgentConnection? Current(Guid host) => entries.GetValueOrDefault(host);
    public bool IsCredentialActive(Guid host, Guid credential) => Current(host)?.CredentialId == credential;
    public void Require(Guid host, Guid epoch, string? connectionId = null)
    {
        var current = Current(host);
        if (current == null || current.Epoch != epoch || connectionId != null && current.ConnectionId != connectionId) throw new InventoryFault(409, "StateConflict");
    }
    public bool Remove(Guid host, string connectionId)
    {
        var current = Current(host);
        return current != null && current.ConnectionId == connectionId && ((ICollection<KeyValuePair<Guid, AgentConnection>>)entries).Remove(new(host, current));
    }
    public void RevokeHost(Guid host)
    {
        if (entries.TryRemove(host, out var connection)) connection.Abort();
    }
    public void RevokeCredential(Guid credential)
    {
        foreach (var connection in entries.Values.Where(x => x.CredentialId == credential))
            if (((ICollection<KeyValuePair<Guid, AgentConnection>>)entries).Remove(new(connection.HostId, connection))) connection.Abort();
    }
}
