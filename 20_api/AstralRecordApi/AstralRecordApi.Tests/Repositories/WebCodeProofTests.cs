using AstralRecordApi.Authentication;
using Microsoft.AspNetCore.DataProtection;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class WebCodeProofTests
{
    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, true)]
    [InlineData(300, true)]
    [InlineData(301, false)]
    public void Proof_IsBoundToIdentityVersionAndFiveMinuteWindow(int ageSeconds, bool valid)
    {
        var proof = new WebCodeProofProtector(new EphemeralDataProtectionProvider());
        var user = Guid.NewGuid();
        var version = Guid.NewGuid();
        var now = DateTimeOffset.UtcNow;
        var token = proof.Issue(user, version, now.AddSeconds(-ageSeconds));
        Assert.Equal(valid, proof.Validate(token, user, version, now).HasValue);
        Assert.Null(proof.Validate(token, Guid.NewGuid(), version, now));
        Assert.Null(proof.Validate(token, user, Guid.NewGuid(), now));
        Assert.Null(proof.Validate(token[..^8] + "tampered", user, version, now));
        Assert.Null(new WebCodeProofProtector(new EphemeralDataProtectionProvider()).Validate(token, user, version, now));
    }
}
