namespace AstralRecordApi.Models;

public class HealthCheckResponse
{
    public required string Status { get; init; }

    public required string Service { get; init; }

    public required DateTimeOffset Timestamp { get; init; }
}