using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace AstralRecordControl.Contracts;

public enum ScheduleTriggerKind { Once, Interval, Cron }
public enum ScheduleActionType { Operation, LocalChain, WebWorkflow }
public enum ScheduleOccurrenceStatus { Pending, Running, Succeeded, Failed, Skipped, NeedsAttention }

public sealed record ScheduleTrigger
{
    public ScheduleTriggerKind Kind { get; init; }
    public DateTimeOffset? AtUtc { get; init; }
    public int? Seconds { get; init; }
    public DateTimeOffset? AnchorUtc { get; init; }
    public string? Expression { get; init; }
    public string? TimeZoneId { get; init; }
}

public sealed record ScheduleStep
{
    public string StepKey { get; init; } = "";
    public OperationKind Kind { get; init; }
    public JsonElement Payload { get; init; } = ControlJson.Element(new { });
    public int DelaySeconds { get; init; }
}

public sealed record ScheduleTemplate
{
    public ScheduleActionType ActionType { get; init; }
    public ScheduleStep[] Steps { get; init; } = [];
}

public sealed record ScheduleDefinition
{
    public Guid ScheduleId { get; init; }
    public Guid HostId { get; init; }
    public Guid? ServerId { get; init; }
    public long Revision { get; init; }
    public bool Enabled { get; init; }
    public ScheduleTrigger Trigger { get; init; } = new();
    public ScheduleTemplate Template { get; init; } = new();
    public string MisfirePolicy { get; init; } = "Skip";
}

public sealed record ScheduleStepReport(string StepKey, int Attempt, Guid OperationId, OperationStatus Status, string? ErrorCode);

public sealed record ScheduleOccurrenceReport
{
    public Guid ScheduleId { get; init; }
    public Guid HostId { get; init; }
    public Guid ServerId { get; init; }
    public long Revision { get; init; }
    public DateTimeOffset ScheduledAtUtc { get; init; }
    public bool Manual { get; init; }
    public Guid OperationId { get; init; }
    public Guid WorkflowId { get; init; }
    public Guid ReservationId { get; init; }
    public long AppliedInventoryRevision { get; init; }
    public string DefinitionSha256 { get; init; } = "";
    public long? Fence { get; init; }
    public string[] ReservedResourceKeys { get; init; } = [];
    public ScheduleOccurrenceStatus Status { get; init; }
    public string? ErrorCode { get; init; }
    public ScheduleStepReport[] Steps { get; init; } = [];
    public DateTimeOffset? NextStepNotBeforeUtc { get; init; }
    public DateTimeOffset UpdatedAtUtc { get; init; }
}

public sealed record RunScheduleNowRequest(Guid ConnectionEpoch, Guid ScheduleId, long Revision, Guid OperationId);
public sealed record RunScheduleNowResponse(ScheduleOccurrenceReport? Report, ErrorCode? ErrorCode);

public static class ScheduleValidation
{
    public static bool IsLocalKind(OperationKind kind) => kind is OperationKind.Start or OperationKind.Stop or OperationKind.Restart or OperationKind.ConsoleCommand or OperationKind.CreateBackup or OperationKind.PruneBackups;

    public static ScheduleDefinition Read(JsonElement json)
    {
        var schedule = ControlJson.Deserialize<ScheduleDefinition>(json.GetRawText());
        Validate(schedule);
        return schedule;
    }

    public static void Validate(ScheduleDefinition schedule)
    {
        ArgumentNullException.ThrowIfNull(schedule);
        if (schedule.ScheduleId == Guid.Empty || schedule.HostId == Guid.Empty || schedule.ServerId == Guid.Empty || schedule.Revision < 1 || schedule.MisfirePolicy != "Skip") throw new ArgumentException("Invalid schedule identity.");
        if (schedule.Trigger is null || schedule.Template is null || !Enum.IsDefined(schedule.Trigger.Kind) || !Enum.IsDefined(schedule.Template.ActionType)) throw new ArgumentException("Invalid schedule type.");
        var trigger = schedule.Trigger;
        switch (trigger.Kind)
        {
            case ScheduleTriggerKind.Once:
                if (!IsUtc(trigger.AtUtc) || trigger.Seconds is not null || trigger.AnchorUtc is not null || trigger.Expression is not null || trigger.TimeZoneId is not null) throw new ArgumentException("Invalid Once trigger.");
                break;
            case ScheduleTriggerKind.Interval:
                if (trigger.Seconds is < 60 or > 31536000 || !IsUtc(trigger.AnchorUtc) || trigger.AtUtc is not null || trigger.Expression is not null || trigger.TimeZoneId is not null) throw new ArgumentException("Invalid interval trigger.");
                break;
            case ScheduleTriggerKind.Cron:
                if (trigger.AtUtc is not null || trigger.Seconds is not null || trigger.AnchorUtc is not null || string.IsNullOrWhiteSpace(trigger.TimeZoneId) || trigger.TimeZoneId.Length > 128 || string.IsNullOrWhiteSpace(trigger.Expression) || trigger.Expression.Length > 128) throw new ArgumentException("Invalid cron trigger.");
                _ = TimeZoneInfo.FindSystemTimeZoneById(trigger.TimeZoneId);
                _ = CronSchedule.Parse(trigger.Expression);
                break;
        }
        var steps = schedule.Template.Steps;
        if (steps is null || steps.Length is < 1 or > 16 || schedule.Template.ActionType == ScheduleActionType.Operation && steps.Length != 1) throw new ArgumentException("Invalid schedule steps.");
        if (schedule.Template.ActionType == ScheduleActionType.WebWorkflow) throw new ArgumentException("Web workflows require online orchestration.");
        if (schedule.ServerId is null || steps.Any(s => s is null || !IsLocalKind(s.Kind) || s.DelaySeconds is < 0 or > 86400 || s.StepKey.Length is < 1 or > 128 || s.StepKey.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_')) || s.Payload.ValueKind != JsonValueKind.Object) || steps.Select(s => s.StepKey).Distinct(StringComparer.Ordinal).Count() != steps.Length) throw new ArgumentException("Invalid local schedule steps.");
        foreach (var step in steps)
        {
            if (step.Kind == OperationKind.CreateBackup)
            {
                var payload = FileOperationValidation.Read<CreateBackupPayload>(step.Payload);
                if (payload.BackupId != Guid.Empty || payload.BackupProfileId == Guid.Empty || payload.ProfileRevision < 1) throw new ArgumentException("Scheduled backup ID must be generated for each occurrence.");
            }
            else if (step.Kind is OperationKind.PruneBackups) FileOperationValidation.ValidatePayload(step.Kind, step.Payload);
            else
            {
                var command = new OperationCommand { OperationId = Guid.NewGuid(), HostId = schedule.HostId, ServerId = schedule.ServerId,
                    WorkflowId = Guid.NewGuid(), ReservationId = Guid.NewGuid(), ReservationFence = 1, Kind = step.Kind,
                    ConfigRevision = 1, ExpectedStateRevision = 0, StartBeforeUtc = DateTimeOffset.UtcNow.AddMinutes(1), Payload = step.Payload };
                OperationValidation.Validate(command, schedule.HostId);
            }
        }
    }

    private static bool IsUtc(DateTimeOffset? value) => value is { Offset: var offset } && offset == TimeSpan.Zero;

    public static bool IsOccurrence(ScheduleDefinition schedule, DateTimeOffset scheduledAtUtc)
    {
        if (scheduledAtUtc.Offset != TimeSpan.Zero) return false;
        var trigger = schedule.Trigger;
        return trigger.Kind switch
        {
            ScheduleTriggerKind.Once => trigger.AtUtc == scheduledAtUtc,
            ScheduleTriggerKind.Interval => trigger.AnchorUtc is DateTimeOffset anchor && trigger.Seconds is int seconds && seconds >= 60
                && scheduledAtUtc >= anchor && (scheduledAtUtc - anchor).Ticks % TimeSpan.FromSeconds(seconds).Ticks == 0,
            ScheduleTriggerKind.Cron => scheduledAtUtc.Second == 0 && scheduledAtUtc.Millisecond == 0 && scheduledAtUtc.Ticks % TimeSpan.TicksPerMinute == 0
                && trigger.Expression is not null && trigger.TimeZoneId is not null
                && MatchesCron(trigger.Expression, trigger.TimeZoneId, scheduledAtUtc),
            _ => false
        };
    }

    private static bool MatchesCron(string expression, string timeZoneId, DateTimeOffset utc)
    {
        var zone = TimeZoneInfo.FindSystemTimeZoneById(timeZoneId);
        return CronSchedule.Parse(expression).Matches(TimeZoneInfo.ConvertTime(utc, zone).DateTime) && CronSchedule.IsFirstUtcOccurrence(utc, zone);
    }
}

public static class ScheduleIdentity
{
    public static Guid Occurrence(Guid scheduleId, long revision, DateTimeOffset scheduledAtUtc)
        => Uuid5(scheduleId, revision.ToString(CultureInfo.InvariantCulture) + ":" + CanonicalUtc(scheduledAtUtc));

    public static Guid Child(Guid workflowId, string stepKey, int attempt)
        => Uuid5(workflowId, stepKey + ":" + attempt.ToString(CultureInfo.InvariantCulture));

    public static string CanonicalUtc(DateTimeOffset value)
    {
        if (value.Offset != TimeSpan.Zero) throw new ArgumentException("UTC is required.");
        return value.UtcDateTime.ToString("O", CultureInfo.InvariantCulture);
    }

    public static Guid Uuid5(Guid namespaceId, string name)
    {
        if (namespaceId == Guid.Empty || name is null) throw new ArgumentException("Invalid UUID v5 input.");
        Span<byte> ns = stackalloc byte[16];
        namespaceId.TryWriteBytes(ns, bigEndian: true, out _);
        var bytes = new byte[16 + Encoding.UTF8.GetByteCount(name)];
        ns.CopyTo(bytes);
        Encoding.UTF8.GetBytes(name, bytes.AsSpan(16));
        var hash = SHA1.HashData(bytes);
        hash[6] = (byte)((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte)((hash[8] & 0x3f) | 0x80);
        return new Guid(hash.AsSpan(0, 16), bigEndian: true);
    }
}

public sealed class CronSchedule
{
    private readonly bool[] minute, hour, day, month, weekday;
    private readonly bool anyDay, anyWeekday;
    private CronSchedule(bool[] minute, bool[] hour, bool[] day, bool[] month, bool[] weekday, bool anyDay, bool anyWeekday)
    { this.minute = minute; this.hour = hour; this.day = day; this.month = month; this.weekday = weekday; this.anyDay = anyDay; this.anyWeekday = anyWeekday; }

    public static CronSchedule Parse(string expression)
    {
        var fields = expression.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        if (fields.Length != 5) throw new ArgumentException("Cron requires five fields.");
        return new CronSchedule(Field(fields[0], 0, 59), Field(fields[1], 0, 23), Field(fields[2], 1, 31), Field(fields[3], 1, 12), Field(fields[4], 0, 7), fields[2] == "*", fields[4] == "*");
    }

    public bool Matches(DateTime local)
    {
        var dom = day[local.Day];
        var dow = weekday[(int)local.DayOfWeek];
        var dayMatch = anyDay ? dow : anyWeekday ? dom : dom || dow;
        return minute[local.Minute] && hour[local.Hour] && month[local.Month] && dayMatch;
    }

    public static bool IsFirstUtcOccurrence(DateTimeOffset utc, TimeZoneInfo zone)
    {
        var local = TimeZoneInfo.ConvertTime(utc, zone).DateTime;
        if (!zone.IsAmbiguousTime(local)) return true;
        return zone.GetAmbiguousTimeOffsets(local).Max() == TimeZoneInfo.ConvertTime(utc, zone).Offset;
    }

    private static bool[] Field(string text, int min, int max)
    {
        var result = new bool[max + 1];
        foreach (var part in text.Split(','))
        {
            var slash = part.Split('/');
            if (slash.Length > 2 || slash.Length == 2 && (!int.TryParse(slash[1], NumberStyles.None, CultureInfo.InvariantCulture, out var stepValue) || stepValue < 1)) throw new ArgumentException("Invalid cron step.");
            var step = slash.Length == 1 ? 1 : int.Parse(slash[1], CultureInfo.InvariantCulture);
            var range = slash[0].Split('-');
            int start, end;
            if (slash[0] == "*") { start = min; end = max; }
            else if (range.Length == 1 && int.TryParse(range[0], NumberStyles.None, CultureInfo.InvariantCulture, out start)) end = slash.Length == 2 ? max : start;
            else if (range.Length == 2 && int.TryParse(range[0], NumberStyles.None, CultureInfo.InvariantCulture, out start) && int.TryParse(range[1], NumberStyles.None, CultureInfo.InvariantCulture, out end)) { }
            else throw new ArgumentException("Invalid cron field.");
            if (start < min || end > max || start > end) throw new ArgumentException("Cron field out of range.");
            for (var value = start; value <= end; value += step) result[value == 7 && max == 7 ? 0 : value] = true;
        }
        return result;
    }
}
