using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AstralRecordApi.Utilities;

/// <summary>Plugin AccountService と同じ通常レベル累積経験値を計算します。</summary>
public static class PlayerAdminLevelExperience
{
    public const int MaximumLevel = 100;
    private static readonly int[] WavePattern = [0, 90, 35, 140, 60, 185, 95, 230];

    public static long TotalRequired(Guid accountId, int targetLevel)
    {
        if (targetLevel is < 1 or > MaximumLevel)
            throw new ArgumentOutOfRangeException(nameof(targetLevel));
        long total = 0;
        for (var level = 1; level < targetLevel; level++)
            total = checked(total + RequiredForNext(accountId, level));
        return total;
    }

    public static int RequiredForNext(Guid accountId, int currentLevel)
    {
        if (currentLevel is < 1 or > MaximumLevel)
            throw new ArgumentOutOfRangeException(nameof(currentLevel));
        var baseValue = 500 + currentLevel * currentLevel * 120;
        var tierBonus = currentLevel / 10 * 850;
        var waveBonus = WavePattern[(currentLevel - 1) % WavePattern.Length];
        var milestoneBonus = currentLevel % 5 == 0 ? 600 + currentLevel * 80 : 0;
        var hashModulo = 90 + currentLevel * 4;
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes($"{accountId:D}:{currentLevel}"));
        var signed = BinaryPrimitives.ReadInt32BigEndian(digest);
        var nonnegative = (int)((((long)signed % int.MaxValue) + int.MaxValue) % int.MaxValue);
        return baseValue + tierBonus + waveBonus + milestoneBonus + nonnegative % hashModulo;
    }
}
