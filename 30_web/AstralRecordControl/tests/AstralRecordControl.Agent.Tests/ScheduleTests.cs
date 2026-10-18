using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class ScheduleTests
{
    private readonly Guid hostId = Guid.NewGuid();
    private readonly Guid serverId = Guid.NewGuid();

    [Fact]
    public void UuidV5UsesCanonicalUtcAndRevision()
    {
        var schedule = Guid.Parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        Assert.Equal(Guid.Parse("2ed6657d-e927-568b-95e1-2665a8aea6a2"), ScheduleIdentity.Uuid5(schedule, "www.example.com"));
        var at = new DateTimeOffset(2026, 10, 18, 10, 0, 0, TimeSpan.Zero);
        Assert.Equal(ScheduleIdentity.Occurrence(schedule, 3, at), ScheduleIdentity.Occurrence(schedule, 3, at));
        Assert.NotEqual(ScheduleIdentity.Occurrence(schedule, 3, at), ScheduleIdentity.Occurrence(schedule, 4, at));
        Assert.Equal("2026-10-18T10:00:00.0000000Z", ScheduleIdentity.CanonicalUtc(at));
    }

    [Fact]
    public void IntervalRespectsAnchorAndRejectsTooShortPeriod()
    {
        var anchor = new DateTimeOffset(2026, 10, 18, 0, 0, 17, TimeSpan.Zero);
        var definition = Definition(new ScheduleTrigger { Kind = ScheduleTriggerKind.Interval, AnchorUtc = anchor, Seconds = 90 });
        ScheduleValidation.Validate(definition);
        Assert.Equal([anchor, anchor.AddSeconds(90), anchor.AddSeconds(180)],
            AgentSchedulePlanner.DueBetween(definition, anchor.AddTicks(-1), anchor.AddSeconds(180)));
        Assert.True(ScheduleValidation.IsOccurrence(definition, anchor.AddSeconds(90)));
        Assert.False(ScheduleValidation.IsOccurrence(definition, anchor.AddSeconds(60)));
        Assert.Throws<ArgumentException>(() => ScheduleValidation.Validate(definition with { Trigger = definition.Trigger with { Seconds = 59 } }));
    }

    [Fact]
    public void CronUsesEarlierUtcForRepeatedLocalMinuteAndSkipsMissingMinute()
    {
        var repeated = Definition(new ScheduleTrigger { Kind = ScheduleTriggerKind.Cron, Expression = "30 1 * * *", TimeZoneId = "America/New_York" });
        ScheduleValidation.Validate(repeated);
        var early = new DateTimeOffset(2026, 11, 1, 5, 30, 0, TimeSpan.Zero);
        var late = early.AddHours(1);
        Assert.True(ScheduleValidation.IsOccurrence(repeated, early));
        Assert.False(ScheduleValidation.IsOccurrence(repeated, late));
        Assert.Equal([early], AgentSchedulePlanner.DueBetween(repeated, early.AddMinutes(-1), late));
        var missing = repeated with { Trigger = repeated.Trigger with { Expression = "30 2 * * *" } };
        Assert.Empty(AgentSchedulePlanner.DueBetween(missing,
            new DateTimeOffset(2026, 3, 8, 6, 0, 0, TimeSpan.Zero), new DateTimeOffset(2026, 3, 8, 8, 0, 0, TimeSpan.Zero)));
    }

    [Fact]
    public void LongOutageKeepsMostRecentDueInsteadOfDroppingItAtTheBatchLimit()
    {
        var anchor = new DateTimeOffset(2026, 10, 18, 0, 0, 0, TimeSpan.Zero);
        var definition = Definition(new() { Kind = ScheduleTriggerKind.Interval, AnchorUtc = anchor, Seconds = 60 });
        var through = anchor.AddDays(2).AddSeconds(10);
        var due = AgentSchedulePlanner.DueBetween(definition, anchor.AddTicks(-1), through);
        Assert.Equal(1000, due.Length);
        Assert.Equal(anchor.AddDays(2), due[^1]);
        Assert.True(through - due[^1] < TimeSpan.FromSeconds(60));
    }

    [Fact]
    public void OnceInThePastIsEnumeratedForSkipAndLocalKindsAreConstrained()
    {
        var at = new DateTimeOffset(2026, 10, 18, 0, 0, 0, TimeSpan.Zero);
        var now = at.AddHours(5);
        var definition = Definition(new ScheduleTrigger { Kind = ScheduleTriggerKind.Once, AtUtc = at });
        Assert.Equal([at], AgentSchedulePlanner.DueBetween(definition, AgentSchedulePlanner.InitialCursor(definition, now), now));
        Assert.False(ScheduleValidation.IsLocalKind(OperationKind.Kill));
        Assert.False(ScheduleValidation.IsLocalKind(OperationKind.RestoreBackup));
    }

    private ScheduleDefinition Definition(ScheduleTrigger trigger) => new()
    {
        ScheduleId = Guid.NewGuid(), HostId = hostId, ServerId = serverId, Revision = 1,
        Trigger = trigger, Template = new ScheduleTemplate { ActionType = ScheduleActionType.Operation,
            Steps = [new ScheduleStep { StepKey = "start", Kind = OperationKind.Start }] }
    };
}
