using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.WebUtilities;

namespace SkillTreeEditor.Server.Services;

internal sealed class MobileAuthentication(MobileDeviceRegistry registry, TimeProvider clock)
{
    private static readonly TimeSpan ChallengeLifetime = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan SessionIdleTimeout = TimeSpan.FromMinutes(30);

    private readonly object _sync = new();
    private readonly Dictionary<string, Challenge> _challenges = new();
    private readonly Dictionary<string, Session> _sessions = new();

    public static bool IsDeviceId(string? value) =>
        value is { Length: 32 } && value.All(c => char.IsAsciiHexDigitLower(c));

    public static string? NormalizePublicKey(string? value)
    {
        if (value is not { Length: > 0 and <= 128 })
            return null;

        try
        {
            var bytes = Convert.FromBase64String(value);
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(bytes, out var consumed);
            if (consumed != bytes.Length || key.ExportParameters(false).Curve.Oid.Value != ECCurve.NamedCurves.nistP256.Oid.Value)
                return null;

            return Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        }
        catch (Exception exception) when (exception is FormatException or CryptographicException or ArgumentException)
        {
            return null;
        }
    }

    public string? IssueChallenge(string deviceId)
    {
        lock (_sync)
        {
            if (registry.FindPublicKey(deviceId) is null)
                return null;

            var issued = clock.GetTimestamp();
            foreach (var id in _challenges.Where(entry => clock.GetElapsedTime(entry.Value.IssuedAt, issued) >= ChallengeLifetime)
                         .Select(entry => entry.Key).ToArray())
                _challenges.Remove(id);

            var bytes = RandomNumberGenerator.GetBytes(32);
            _challenges[deviceId] = new Challenge(bytes, issued);
            return Convert.ToBase64String(bytes);
        }
    }

    public string? IssueSession(string deviceId, byte[] suppliedChallenge, byte[] signature)
    {
        lock (_sync)
        {
            if (!_challenges.TryGetValue(deviceId, out var pending)
                || !CryptographicOperations.FixedTimeEquals(suppliedChallenge, pending.Bytes))
                return null;

            // Consume the exact challenge before verifying the signature, regardless of success.
            _challenges.Remove(deviceId);
            var now = clock.GetTimestamp();
            if (clock.GetElapsedTime(pending.IssuedAt, now) >= ChallengeLifetime
                || registry.FindPublicKey(deviceId) is not { } publicKey
                || !Verify(publicKey, pending.Bytes, signature))
                return null;

            foreach (var oldToken in _sessions.Where(entry => entry.Value.DeviceId == deviceId || IsExpired(entry.Value, now))
                         .Select(entry => entry.Key).ToArray())
                _sessions.Remove(oldToken);

            var token = WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
            _sessions.Add(HashToken(token), new Session(deviceId, now));
            return token;
        }
    }

    public bool Authorize(string? token)
    {
        if (token is not { Length: 43 } || !token.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))
            return false;

        var tokenHash = HashToken(token);
        lock (_sync)
        {
            if (!_sessions.TryGetValue(tokenHash, out var session))
                return false;

            var now = clock.GetTimestamp();
            if (IsExpired(session, now) || registry.FindPublicKey(session.DeviceId) is null)
            {
                _sessions.Remove(tokenHash);
                return false;
            }

            _sessions[tokenHash] = session with { LastActivity = now };
            return true;
        }
    }

    private static bool Verify(string spki, byte[] challenge, byte[] signature)
    {
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(spki), out _);
            return key.VerifyData(challenge, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException)
        {
            return false;
        }
    }

    private bool IsExpired(Session session, long now) =>
        clock.GetElapsedTime(session.LastActivity, now) >= SessionIdleTimeout;

    private static string HashToken(string token) => Convert.ToHexString(SHA256.HashData(Encoding.ASCII.GetBytes(token)));

    private sealed record Challenge(byte[] Bytes, long IssuedAt);
    private sealed record Session(string DeviceId, long LastActivity);
}
