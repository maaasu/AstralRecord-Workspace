using System.Security.Cryptography;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MobileAuthenticationTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), $"astral-mobile-auth-{Guid.NewGuid():N}");
    private readonly TestClock _clock = new();

    [Fact]
    public async Task CodeCanOnlyBeConsumedOnceAcrossRegistryInstances()
    {
        var local = Registry();
        var server = Registry();
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        var code = local.CreatePairingCode();
        Assert.Matches("^[0-9]{8}$", code.Code);

        var results = await Task.WhenAll(
            local.PairAsync(code.Code, publicKey, "One", default),
            server.PairAsync(code.Code, publicKey, "Two", default));

        var device = Assert.Single(results, result => result is not null);
        Assert.Equal(device, Assert.Single(local.ListDevices()));
        Assert.Equal(device, Assert.Single(server.ListDevices()));
        Assert.Empty(Registry("different-workspace").ListDevices());
        Assert.Null(await Registry("different-workspace").PairAsync(code.Code, publicKey, "Other", default));
    }

    [Fact]
    public async Task PairingCodesExpireAndIncorrectGuessesExhaustTheCurrentCode()
    {
        var registry = Registry();
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());
        var expired = registry.CreatePairingCode();
        _clock.Advance(TimeSpan.FromMinutes(5));
        Assert.Null(await registry.PairAsync(expired.Code, publicKey, "Device", default));

        var code = registry.CreatePairingCode().Code;
        for (var attempt = 0; attempt < 12; attempt++)
            Assert.Null(await registry.PairAsync(code == "00000000" ? "11111111" : "00000000", publicKey, "Device", default));
        Assert.Null(await registry.PairAsync(code, publicKey, "Device", default));
        Assert.Empty(registry.ListDevices());
    }

    [Fact]
    public async Task SignaturesRequireThePairedKeyAndAnUnexpiredSingleUseChallenge()
    {
        var registry = Registry();
        var auth = new MobileAuthentication(registry, _clock);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var otherKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var device = await PairAsync(registry, key);
        Assert.Null(auth.IssueChallenge(Guid.NewGuid().ToString("N")));

        var challenge = Convert.FromBase64String(auth.IssueChallenge(device.DeviceId)!);
        Assert.Equal(32, challenge.Length);
        Assert.Null(auth.IssueSession(device.DeviceId, challenge, Sign(otherKey, challenge)));
        Assert.Null(auth.IssueSession(device.DeviceId, challenge, Sign(key, challenge)));

        var expired = Convert.FromBase64String(auth.IssueChallenge(device.DeviceId)!);
        _clock.Advance(TimeSpan.FromMinutes(1));
        Assert.Null(auth.IssueSession(device.DeviceId, expired, Sign(key, expired)));

        challenge = Convert.FromBase64String(auth.IssueChallenge(device.DeviceId)!);
        Assert.NotNull(auth.IssueSession(device.DeviceId, challenge, Sign(key, challenge)));
        Assert.Null(auth.IssueSession(device.DeviceId, challenge, Sign(key, challenge)));
    }

    [Fact]
    public async Task SessionsSlideWithActivityAndObserveDesktopRevocation()
    {
        var registry = Registry();
        var auth = new MobileAuthentication(registry, _clock);
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var device = await PairAsync(registry, key);
        var token = Session(auth, device.DeviceId, key);
        _clock.Advance(TimeSpan.FromMinutes(29));
        Assert.True(auth.Authorize(token));
        _clock.Advance(TimeSpan.FromMinutes(29));
        Assert.True(auth.Authorize(token));
        _clock.Advance(TimeSpan.FromMinutes(30));
        Assert.False(auth.Authorize(token));

        var renewed = Session(auth, device.DeviceId, key);
        Assert.True(Registry().Revoke(device.DeviceId));
        Assert.False(auth.Authorize(renewed));
        Assert.Null(auth.IssueChallenge(device.DeviceId));
    }

    private MobileDeviceRegistry Registry(string workspace = "workspace") =>
        new(Path.Combine(_root, workspace), _root, _clock);

    private static async Task<MobileDevice> PairAsync(MobileDeviceRegistry registry, ECDsa key) =>
        (await registry.PairAsync(registry.CreatePairingCode().Code, Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()), "Android", default))!;

    private static string Session(MobileAuthentication auth, string deviceId, ECDsa key)
    {
        var challenge = Convert.FromBase64String(auth.IssueChallenge(deviceId)!);
        return auth.IssueSession(deviceId, challenge, Sign(key, challenge))!;
    }

    internal static byte[] Sign(ECDsa key, byte[] challenge) =>
        key.SignData(challenge, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }

    private sealed class TestClock : TimeProvider
    {
        private TimeSpan _elapsed;
        public void Advance(TimeSpan time) => _elapsed += time;
        public override long GetTimestamp() => _elapsed.Ticks;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override DateTimeOffset GetUtcNow() => DateTimeOffset.UnixEpoch + _elapsed;
    }
}
