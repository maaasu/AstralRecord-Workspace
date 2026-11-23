using System.Text.Json.Serialization;

namespace SkillTreeEditor.Server.Models;

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ToolJobPlanRequest(
    string ToolId,
    string? NetworkTarget = null,
    bool SkipTests = false,
    string? WorldCopy = null,
    string? NetworkPlugins = null,
    bool ServerAlreadyRunning = false,
    string? PluginTestMode = null,
    string? PluginTests = null,
    string? Recovery = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? ArchitectMode = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string[]? DistributionSelection = null,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? DistributionConfigurationDigest = null);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ToolJobStartRequest(Guid PlanId, string[] Confirmations);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ToolJobDirectStartRequest(Guid RequestId, ToolJobPlanRequest Settings);

[JsonUnmappedMemberHandling(JsonUnmappedMemberHandling.Disallow)]
public sealed record ToolJobInputRequest(Guid PromptId, string Value);

public sealed record ToolJobCapabilities(
    bool Enabled, string? Reason, string WorkspaceRoot, Guid? ActiveRunId, string? WorkspaceBranch);

public sealed record ToolJobMaintenanceOption(string Id, string Kind, string Label, string Source, string Destination);

public sealed record ToolJobMaintenanceOptions(
    string ConfigurationDigest,
    ToolJobMaintenanceOption[] Options,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string[]? SavedSelection);

public sealed record ToolJobPath(string Label, string Source, string Destination);
public sealed record ToolJobConfirmation(string Id, string Label);

public sealed record ToolJobPlan(
    Guid Id,
    string ToolId,
    string WorkspaceRoot,
    string? ConfigurationPath,
    string? FilebaseSource,
    bool WorkspaceMismatch,
    bool CanRun,
    string[] Warnings,
    string[] Summary,
    ToolJobPath[] Paths,
    ToolJobConfirmation[] Confirmations,
    DateTimeOffset ExpiresAtUtc);

public sealed record ToolJobPromptChoice(string Value, string Label);
public sealed record ToolJobPrompt(Guid Id, string Message, ToolJobPromptChoice[] Choices);

public sealed record ToolJobRun(
    Guid Id,
    string ToolId,
    ToolJobPlan Plan,
    string Status,
    DateTimeOffset StartedAtUtc,
    DateTimeOffset? FinishedAtUtc = null,
    int? ExitCode = null,
    ToolJobPrompt? Prompt = null,
    string? LogPath = null);

public sealed record ToolJobLogLine(long Sequence, string Stream, string Text);
public sealed record ToolJobLogs(ToolJobLogLine[] Lines, long Next);

internal sealed record ToolJobPlanDetails(
    string? ConfigurationPath,
    string? FilebaseSource,
    bool WorkspaceMismatch,
    bool CanRun,
    string[] Warnings,
    string[] Summary,
    ToolJobPath[] Paths,
    ToolJobConfirmation[] Confirmations,
    string Fingerprint);
