namespace AstralRecordApi.Models;

/// <summary>寄付受付とゲーム内配布で共有する上限・識別子です。</summary>
public static class DonationRules
{
    public const string TermsVersion = "2026-09-26";
    public const string PaidAstraldItemId = "99a00021";
    public const int MinimumAmount = 500;
    public const int MaximumAmount = 1_000_000;
    public const int MaximumEntries = 10;
    public const string Pending = "Pending";
    public const string Reviewing = "Reviewing";
    public const string Approved = "Approved";
    public const string Rejected = "Rejected";
    public const string Cancelled = "Cancelled";
}

public sealed record DonationEntry(string Method, string Value, int DeclaredAmount);
public sealed record DonationCreateRequest(Guid OperationId, int DeclaredAmount, string TermsVersion, IReadOnlyList<DonationEntry> Entries);
public sealed record DonationApproveRequest(int? ApprovedAmount);
public sealed record DonationRejectRequest(string Reason);
public sealed record DonationResponse(Guid Id, Guid UserUuid, string Mcid, int DeclaredAmount,
    int? ApprovedAmount, string Status, string? Reason, DateTime CreatedAtUtc, DateTime? DecidedAtUtc,
    Guid? ReviewerUuid, IReadOnlyList<DonationEntry> Entries, string DiscordUserId, string DiscordName);
public sealed record DonationListResponse(long TotalApprovedAmount, IReadOnlyList<DonationResponse> Requests,
    DonationDiscordLinkResponse? DiscordLink, int TotalCount);
public sealed record DonationNotificationResponse(Guid Id, string Kind, int Amount, string Message);
public sealed class DonationConflictException(string message) : Exception(message);
