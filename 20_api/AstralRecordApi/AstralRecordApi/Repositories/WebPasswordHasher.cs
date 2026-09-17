using System.Security.Cryptography;
using System.Text;

namespace AstralRecordApi.Repositories;

/// <summary>PBKDF2-HMAC-SHA512 (310,000 iterations) の自己記述型パスワードハッシュです。</summary>
internal static class WebPasswordHasher
{
    private const string Format = "AR-PBKDF2-SHA512";
    private const int Iterations = 310_000;
    private const int SaltSize = 16;
    private const int HashSize = 32;
    private static readonly string DummyHash = Hash(Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));

    public static void VerifyDummy(string password) => Verify(password, DummyHash);

    public static string Hash(string password)
    {
        var salt = RandomNumberGenerator.GetBytes(SaltSize);
        var derived = Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(password), salt, Iterations, HashAlgorithmName.SHA512, HashSize);
        return $"{Format}${Iterations}${Convert.ToBase64String(salt)}${Convert.ToBase64String(derived)}";
    }

    public static WebPasswordVerification Verify(string password, string encoded)
    {
        var parts = encoded.Split('$');
        if (parts.Length != 4 || !string.Equals(parts[0], Format, StringComparison.Ordinal) ||
            !int.TryParse(parts[1], out var iterations) || iterations < 1)
            return WebPasswordVerification.Failed;

        try
        {
            var salt = Convert.FromBase64String(parts[2]);
            var expected = Convert.FromBase64String(parts[3]);
            var actual = Rfc2898DeriveBytes.Pbkdf2(Encoding.UTF8.GetBytes(password), salt, iterations, HashAlgorithmName.SHA512, expected.Length);
            if (!CryptographicOperations.FixedTimeEquals(actual, expected))
                return WebPasswordVerification.Failed;
            return iterations < Iterations ? WebPasswordVerification.NeedsUpgrade : WebPasswordVerification.Succeeded;
        }
        catch (FormatException)
        {
            return WebPasswordVerification.Failed;
        }
    }
}

internal enum WebPasswordVerification { Failed, Succeeded, NeedsUpgrade }
