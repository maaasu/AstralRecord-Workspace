using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.AspNetCore.DataProtection;

namespace AstralRecordApi.Authentication;

/// <summary>コード消費の証明をユーザー・セッション世代・元の認証日時へ結び付けます。</summary>
public sealed class WebCodeProofProtector(IDataProtectionProvider provider)
{
    private readonly IDataProtector protector = provider.CreateProtector("AstralRecord.WebCodeProof.v1");

    public string Issue(Guid userUuid, Guid version, DateTimeOffset authenticatedAt) =>
        protector.Protect(JsonSerializer.Serialize(new Proof(userUuid, version, authenticatedAt)));

    public DateTimeOffset? Validate(string? token, Guid userUuid, Guid version, DateTimeOffset now)
    {
        if (string.IsNullOrEmpty(token) || token.Length > 2048) return null;
        try
        {
            var proof = JsonSerializer.Deserialize<Proof>(protector.Unprotect(token));
            return proof is not null && proof.UserUuid == userUuid && proof.Version == version &&
                proof.AuthenticatedAt <= now && proof.AuthenticatedAt >= now.AddMinutes(-5)
                ? proof.AuthenticatedAt : null;
        }
        catch (Exception ex) when (ex is CryptographicException or JsonException)
        {
            return null;
        }
    }

    private sealed record Proof(Guid UserUuid, Guid Version, DateTimeOffset AuthenticatedAt);
}
