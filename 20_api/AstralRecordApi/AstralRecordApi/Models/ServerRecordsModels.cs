namespace AstralRecordApi.Models;

public sealed record ServerRecordsResponse(DateTimeOffset GeneratedAt, IReadOnlyList<ServerRankingResponse> Rankings);
public sealed record ServerRankingResponse(string Type, string Unit, bool Available, IReadOnlyList<ServerRankingEntryResponse> Entries);
public sealed record ServerRankingEntryResponse(int Rank, Guid UserUuid, string PlayerName, long Value);
