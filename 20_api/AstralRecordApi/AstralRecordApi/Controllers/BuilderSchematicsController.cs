using System.Globalization;
using System.Text;
using AstralRecordApi.Authentication;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Accepts owner-bound schematic uploads and exposes them to the development server for validation.</summary>
[ApiController]
[Authorize]
[Route("api/builder-schematics")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class BuilderSchematicsController(IBuilderSchematicRepository repository) : ControllerBase
{
    private const int MaxFileBytes = 1024 * 1024;
    private const int MaxRequestBytes = MaxFileBytes + 16 * 1024;
    private const int MaxNameLength = 120;

    /// <summary>Issues one short-lived upload ticket for a Minecraft user.</summary>
    [HttpPost("tickets")]
    [BuilderSchematicAccess(devOnly: true)]
    [RequestSizeLimit(1024)]
    public ActionResult<BuilderSchematicTicketResponse> IssueTicket(BuilderSchematicTicketRequest request)
    {
        if (request.UserUuid == Guid.Empty)
            return BadRequest();

        var token = repository.IssueTicket(request.UserUuid);
        return token is null
            ? StatusCode(StatusCodes.Status429TooManyRequests)
            : StatusCode(StatusCodes.Status201Created, new BuilderSchematicTicketResponse(token));
    }

    /// <summary>Enqueues a compressed schematic using a single-use ticket issued to its owner.</summary>
    [HttpPost("uploads")]
    [BuilderSchematicAccess]
    [Consumes("multipart/form-data")]
    [RequestSizeLimit(MaxRequestBytes)]
    [RequestFormLimits(MultipartBodyLengthLimit = MaxRequestBytes, ValueCountLimit = 3)]
    public async Task<ActionResult<BuilderSchematicUploadReceipt>> Upload(
        [FromForm] BuilderSchematicUploadRequest request, CancellationToken cancellationToken)
    {
        if (request.UserUuid == Guid.Empty)
            return BadRequest();

        if (request.Token is not { Length: 43 } token)
            return StatusCode(StatusCodes.Status403Forbidden);

        var file = request.File;
        if (file.Length > MaxFileBytes)
            return StatusCode(StatusCodes.Status413PayloadTooLarge);

        if (file.Length < 3 || !IsSafeName(file.FileName))
            return BadRequest();

        var content = new byte[(int)file.Length];
        await using (var source = file.OpenReadStream())
            await source.ReadExactlyAsync(content, cancellationToken);

        if (content[0] != 0x1f || content[1] != 0x8b || content[2] != 8)
            return BadRequest();

        var (result, id) = repository.Enqueue(request.UserUuid, token, file.FileName, content);
        return result switch
        {
            BuilderSchematicEnqueueResult.Accepted => Accepted(
                new BuilderSchematicUploadReceipt(id, BuilderSchematicStatus.Pending)),
            BuilderSchematicEnqueueResult.InvalidTicket => StatusCode(StatusCodes.Status403Forbidden),
            BuilderSchematicEnqueueResult.QuotaExceeded => StatusCode(StatusCodes.Status429TooManyRequests),
            _ => throw new InvalidOperationException("Unexpected schematic enqueue result.")
        };
    }

    /// <summary>Returns the current status of an upload for its owner.</summary>
    [HttpGet("uploads/{id:guid}")]
    [BuilderSchematicAccess]
    public ActionResult<BuilderSchematicResponse> GetUpload(Guid id, [FromQuery] Guid userUuid)
    {
        if (userUuid == Guid.Empty)
            return BadRequest();

        var upload = repository.GetUpload(id, userUuid);
        return upload is null ? NotFound() : Ok(upload);
    }

    /// <summary>Returns the queue of uploads awaiting development-server validation.</summary>
    [HttpGet("pending")]
    [BuilderSchematicAccess(devOnly: true)]
    public ActionResult<IReadOnlyList<BuilderSchematicResponse>> GetPending() => Ok(repository.GetPending());

    /// <summary>Returns registered schematics belonging to one Minecraft user.</summary>
    [HttpGet("registered")]
    [BuilderSchematicAccess(devOnly: true)]
    public ActionResult<IReadOnlyList<BuilderSchematicResponse>> GetRegistered([FromQuery] Guid userUuid)
    {
        if (userUuid == Guid.Empty)
            return BadRequest();

        return Ok(repository.GetRegistered(userUuid));
    }

    /// <summary>Fetches a pending upload or a registered schematic scoped to its owner.</summary>
    [HttpGet("files/{id:guid}")]
    [BuilderSchematicAccess(devOnly: true)]
    public IActionResult GetFile(Guid id, [FromQuery] Guid? userUuid)
    {
        if (userUuid == Guid.Empty || (userUuid is null && Request.Query.ContainsKey("userUuid")))
            return BadRequest();

        var content = repository.GetFile(id, userUuid);
        return content is null ? NotFound() : File(content, "application/octet-stream");
    }

    /// <summary>Records the development server's validation result for a pending upload.</summary>
    [HttpPost("decisions/{id:guid}")]
    [BuilderSchematicAccess(devOnly: true)]
    [RequestSizeLimit(1024)]
    public IActionResult Decide(Guid id, BuilderSchematicDecisionRequest request)
    {
        if (request.Accepted is not { } accepted)
            return BadRequest();

        var error = accepted ? null : request.Error switch
        {
            "unsupported_format" or "too_large" or "unsafe_content" or "incompatible_schematic" => request.Error,
            _ => "invalid_schematic"
        };
        return repository.Decide(id, accepted, error) switch
        {
            BuilderSchematicDecisionResult.Applied => NoContent(),
            BuilderSchematicDecisionResult.Missing => NotFound(),
            BuilderSchematicDecisionResult.Conflict => Conflict(),
            _ => throw new InvalidOperationException("Unexpected schematic decision result.")
        };
    }

    private static bool IsSafeName(string name)
    {
        const string extension = ".schem";
        if (name.Length <= extension.Length || name.Length > MaxNameLength
            || !name.EndsWith(extension, StringComparison.OrdinalIgnoreCase))
            return false;

        var stem = name.AsSpan(0, name.Length - extension.Length);
        if (stem.Trim().Length != stem.Length || stem[0] == '.' || stem[^1] == '.'
            || name.IndexOfAny(['/', '\\', ':', '<', '>', '"', '|', '?', '*']) >= 0)
            return false;

        foreach (var rune in name.EnumerateRunes())
        {
            if (Rune.GetUnicodeCategory(rune) is UnicodeCategory.Control or UnicodeCategory.Format
                or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator)
                return false;
        }
        return true;
    }
}
