using System.ComponentModel.DataAnnotations;

namespace AstralRecordApi.Models;

public sealed record BuilderSchematicTicketRequest(Guid UserUuid);

public sealed record BuilderSchematicTicketResponse(string Token);

public sealed class BuilderSchematicUploadRequest
{
    public Guid UserUuid { get; init; }
    public string? Token { get; init; }
    [Required]
    public IFormFile File { get; init; } = null!;
}

public sealed record BuilderSchematicUploadReceipt(Guid Id, string Status);

public sealed record BuilderSchematicResponse(Guid Id, string Name, string Status, string? Error);

public sealed record BuilderSchematicDecisionRequest([Required] bool? Accepted, string? Error);

public static class BuilderSchematicStatus
{
    public const string Pending = "pending";
    public const string Registered = "registered";
    public const string Rejected = "rejected";
}
