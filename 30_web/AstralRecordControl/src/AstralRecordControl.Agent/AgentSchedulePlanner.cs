using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public static class AgentSchedulePlanner
{
    public static DateTimeOffset InitialCursor(ScheduleDefinition definition, DateTimeOffset now)
    {
        var recent = now.AddSeconds(-61);
        return definition.Trigger.Kind == ScheduleTriggerKind.Once && definition.Trigger.AtUtc is DateTimeOffset once && once < recent
            ? once.AddTicks(-1) : recent;
    }

    public static DateTimeOffset[] DueBetween(ScheduleDefinition definition, DateTimeOffset afterExclusive, DateTimeOffset throughInclusive)
    {
        if (afterExclusive.Offset != TimeSpan.Zero || throughInclusive.Offset != TimeSpan.Zero || throughInclusive <= afterExclusive) return [];
        var trigger = definition.Trigger;
        if (trigger.Kind == ScheduleTriggerKind.Once)
            return trigger.AtUtc is DateTimeOffset at && at > afterExclusive && at <= throughInclusive ? [at] : [];
        if (trigger.Kind == ScheduleTriggerKind.Interval)
        {
            var anchor = trigger.AnchorUtc!.Value;
            var interval = TimeSpan.FromSeconds(trigger.Seconds!.Value).Ticks;
            var firstIndex = afterExclusive < anchor ? 0 : (afterExclusive - anchor).Ticks / interval + 1;
            // Bound expired history from the latest side, so an outage never hides a current due time.
            var lastIndex = throughInclusive < anchor ? -1 : (throughInclusive - anchor).Ticks / interval;
            firstIndex = Math.Max(firstIndex, lastIndex - 999);
            var result = new List<DateTimeOffset>();
            for (var index = firstIndex; index < firstIndex + 1000; index++)
            {
                DateTimeOffset due;
                try { due = anchor.AddTicks(checked(index * interval)); }
                catch (ArgumentOutOfRangeException) { break; }
                catch (OverflowException) { break; }
                if (due > throughInclusive) break;
                result.Add(due);
            }
            return result.ToArray();
        }
        var from = afterExclusive.AddTicks(TimeSpan.TicksPerMinute - afterExclusive.Ticks % TimeSpan.TicksPerMinute);
        var earliest = throughInclusive.AddHours(-24);
        if (from < earliest) from = earliest.AddTicks(TimeSpan.TicksPerMinute - earliest.Ticks % TimeSpan.TicksPerMinute);
        var dates = new List<DateTimeOffset>();
        for (var cursor = from; cursor <= throughInclusive; cursor = cursor.AddMinutes(1))
        {
            if (ScheduleValidation.IsOccurrence(definition, cursor)) dates.Add(cursor);
            if (cursor > DateTimeOffset.MaxValue.AddMinutes(-1)) break;
        }
        return dates.ToArray();
    }
}
