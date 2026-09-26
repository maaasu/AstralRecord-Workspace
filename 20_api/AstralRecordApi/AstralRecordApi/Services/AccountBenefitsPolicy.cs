using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

/// <summary>VIP期間はUTCの実時間。日次受取だけJST暦日で判定する。</summary>
public static class AccountBenefitsPolicy
{
    public static AccountBenefitsResponse Describe(AccountBenefitsEntity state, DateTime now)
    {
        var tier = state.AstralderExpiresAt > now ? "ASTRALDER" : state.DonerExpiresAt > now ? "DONER" : "NONE";
        var expiry = tier == "ASTRALDER" ? state.AstralderExpiresAt : tier == "DONER" ? state.DonerExpiresAt : null;
        return new(state.AccountId, state.InstancePriorityUses, tier, Utc(expiry), Utc(state.DonerExpiresAt),
            Utc(state.AstralderExpiresAt), expiry.HasValue ? (int)Math.Ceiling((expiry.Value - now).TotalDays) : 0,
            state.AstralderExpiresAt > now ? state.AstralderDailyCreditsRemaining : 0);
    }

    public static void AddVip(AccountBenefitsEntity state, string tier, int days, DateTime now)
    {
        if (days <= 0) throw new ArgumentOutOfRangeException(nameof(days));
        var astralderEnd = state.AstralderExpiresAt > now ? state.AstralderExpiresAt.Value : now;
        if (tier == "DONER")
            state.DonerExpiresAt = (state.DonerExpiresAt > astralderEnd ? state.DonerExpiresAt.Value : astralderEnd).AddDays(days);
        else if (tier == "ASTRALDER")
        {
            if (state.AstralderExpiresAt <= now || state.AstralderExpiresAt is null)
                state.AstralderDailyCreditsRemaining = 0;
            state.AstralderDailyCreditsRemaining = checked(state.AstralderDailyCreditsRemaining + days);
            state.AstralderExpiresAt = astralderEnd.AddDays(days);
            if (state.DonerExpiresAt > astralderEnd) state.DonerExpiresAt = state.DonerExpiresAt.Value.AddDays(days);
        }
        else throw new ArgumentException("Unknown VIP tier.", nameof(tier));
    }

    public static int ClaimDaily(AccountBenefitsEntity state, DateTime now)
    {
        var date = DateOnly.FromDateTime(now.AddHours(9));
        if (!(state.AstralderExpiresAt > now) || state.AstralderDailyCreditsRemaining <= 0
            || state.LastDailyClaimDate >= date) return 0;
        state.InstancePriorityUses = checked(state.InstancePriorityUses + 1);
        state.AstralderDailyCreditsRemaining--;
        state.LastDailyClaimDate = date;
        return 1;
    }

    private static DateTime? Utc(DateTime? value) => value.HasValue ? DateTime.SpecifyKind(value.Value, DateTimeKind.Utc) : null;
}
