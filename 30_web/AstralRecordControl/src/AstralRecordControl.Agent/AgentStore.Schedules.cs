using System.Globalization;
using AstralRecordControl.Contracts;
using Microsoft.Data.Sqlite;

namespace AstralRecordControl.Agent;

public sealed partial class AgentStore
{
    // Called by the v3 -> v4 migration in AgentStore's constructor.
    private void InitializeScheduleSchema(SqliteTransaction transaction)
    {
        Execute("CREATE TABLE ScheduleApplied(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,snapshotJson TEXT NOT NULL,sha256 TEXT NOT NULL);" +
            "CREATE TABLE ScheduleCursor(scheduleId TEXT NOT NULL,revision INTEGER NOT NULL,cursorUtc TEXT NOT NULL,PRIMARY KEY(scheduleId,revision));" +
            "CREATE TABLE ScheduleOccurrences(operationId TEXT PRIMARY KEY,scheduleId TEXT NOT NULL,revision INTEGER NOT NULL,scheduledAtUtc TEXT NOT NULL,manual INTEGER NOT NULL,definitionJson TEXT NOT NULL,reportJson TEXT NOT NULL);" +
            "CREATE UNIQUE INDEX ScheduleOccurrenceNaturalKey ON ScheduleOccurrences(scheduleId,revision,scheduledAtUtc) WHERE manual=0;" +
            "CREATE TABLE ScheduleChildOperations(operationId TEXT PRIMARY KEY,occurrenceId TEXT NOT NULL,stepKey TEXT NOT NULL,attempt INTEGER NOT NULL,FOREIGN KEY(occurrenceId) REFERENCES ScheduleOccurrences(operationId));", transaction);
    }

    public void MarkScheduleInventoryAcknowledged(long revision)
    {
        lock (sync)
        {
            using var transaction = database.BeginTransaction();
            using var command = Command("SELECT revision,snapshot,hash FROM State WHERE id=1;", transaction);
            using var reader = command.ExecuteReader();
            if (!reader.Read() || reader.GetInt64(0) != revision || reader.GetString(1).Length == 0 || reader.GetString(2).Length == 0) throw new AgentFault(ErrorCode.StaleRevision);
            var snapshot = reader.GetString(1); var hash = reader.GetString(2);
            reader.Close();
            Execute("INSERT INTO ScheduleApplied(id,revision,snapshotJson,sha256) VALUES(1,$revision,$snapshot,$hash) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,snapshotJson=excluded.snapshotJson,sha256=excluded.sha256;", transaction,
                ("$revision", revision), ("$snapshot", snapshot), ("$hash", hash));
            transaction.Commit();
        }
    }

    public (long InventoryRevision, ScheduleDefinition[] Definitions) GetAcknowledgedScheduleDefinitions()
    {
        lock (sync)
        {
            using var command = Command("SELECT revision,snapshotJson,sha256 FROM ScheduleApplied WHERE id=1;");
            using var reader = command.ExecuteReader();
            if (!reader.Read()) return (0, []);
            var revision = reader.GetInt64(0); var snapshot = reader.GetString(1); var sha = reader.GetString(2);
            if (ControlProtocol.Hash(snapshot) != sha) throw new AgentFault(ErrorCode.RecoveryRequired);
            var inventory = InventoryValidation.ReadEnvelope(new InventoryEnvelope(ControlProtocol.Version, hostId, revision, snapshot, sha), hostId);
            return (revision, inventory.Schedules.Select(ScheduleValidation.Read).ToArray());
        }
    }

    public DateTimeOffset? GetScheduleCursor(Guid scheduleId, long revision)
    {
        lock (sync)
        {
            var value = (string?)Scalar("SELECT cursorUtc FROM ScheduleCursor WHERE scheduleId=$id AND revision=$revision;", null,
                ("$id", scheduleId.ToString()), ("$revision", revision));
            return value is null ? null : DateTimeOffset.Parse(value, CultureInfo.InvariantCulture);
        }
    }

    public void AdvanceScheduleCursor(Guid scheduleId, long revision, DateTimeOffset cursorUtc)
    {
        lock (sync) Execute("INSERT INTO ScheduleCursor(scheduleId,revision,cursorUtc) VALUES($id,$revision,$utc) ON CONFLICT(scheduleId,revision) DO UPDATE SET cursorUtc=excluded.cursorUtc WHERE excluded.cursorUtc>ScheduleCursor.cursorUtc;", null,
            ("$id", scheduleId.ToString()), ("$revision", revision), ("$utc", ScheduleIdentity.CanonicalUtc(cursorUtc)));
    }

    public bool ClaimScheduleOccurrence(ScheduleDefinition definition, ScheduleOccurrenceReport report)
    {
        ScheduleValidation.Validate(definition);
        if (definition.ScheduleId != report.ScheduleId || definition.Revision != report.Revision || definition.HostId != report.HostId || definition.ServerId != report.ServerId || report.OperationId == Guid.Empty || report.WorkflowId == Guid.Empty || report.ReservationId == Guid.Empty || report.ScheduledAtUtc.Offset != TimeSpan.Zero) throw new AgentFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            if (GetScheduleReport(report.OperationId) is not null) return false;
            using var transaction = database.BeginTransaction();
            Execute("INSERT INTO ScheduleOccurrences(operationId,scheduleId,revision,scheduledAtUtc,manual,definitionJson,reportJson) VALUES($id,$schedule,$revision,$scheduled,$manual,$definition,$report);", transaction,
                ("$id", report.OperationId.ToString()), ("$schedule", report.ScheduleId.ToString()), ("$revision", report.Revision), ("$scheduled", ScheduleIdentity.CanonicalUtc(report.ScheduledAtUtc)), ("$manual", report.Manual ? 1 : 0),
                ("$definition", ControlJson.Serialize(definition)), ("$report", ControlJson.Serialize(report)));
            AppendScheduleEvent(transaction, report);
            transaction.Commit();
            return true;
        }
    }

    public ScheduleOccurrenceReport? GetScheduleReport(Guid occurrenceId)
    {
        lock (sync)
        {
            var json = (string?)Scalar("SELECT reportJson FROM ScheduleOccurrences WHERE operationId=$id;", null, ("$id", occurrenceId.ToString()));
            return json is null ? null : ControlJson.Deserialize<ScheduleOccurrenceReport>(json);
        }
    }

    public (ScheduleDefinition Definition, ScheduleOccurrenceReport Report)[] ReadScheduleReports(bool nonterminalOnly = false)
    {
        lock (sync)
        {
            using var command = Command("SELECT definitionJson,reportJson FROM ScheduleOccurrences ORDER BY scheduledAtUtc,operationId;");
            using var reader = command.ExecuteReader();
            var rows = new List<(ScheduleDefinition, ScheduleOccurrenceReport)>();
            while (reader.Read())
            {
                var report = ControlJson.Deserialize<ScheduleOccurrenceReport>(reader.GetString(1));
                if (nonterminalOnly && report.Status is ScheduleOccurrenceStatus.Succeeded or ScheduleOccurrenceStatus.Failed or ScheduleOccurrenceStatus.Skipped) continue;
                rows.Add((ControlJson.Deserialize<ScheduleDefinition>(reader.GetString(0)), report));
            }
            return rows.ToArray();
        }
    }

    public bool OwnsScheduledOperation(Guid operationId)
    {
        lock (sync) return Scalar("SELECT 1 FROM ScheduleChildOperations WHERE operationId=$id;", null, ("$id", operationId.ToString())) is not null;
    }

    public void RegisterScheduleChild(Guid occurrenceId, ScheduleStepReport step)
    {
        lock (sync) Execute("INSERT INTO ScheduleChildOperations(operationId,occurrenceId,stepKey,attempt) VALUES($id,$occurrence,$key,$attempt) ON CONFLICT(operationId) DO NOTHING;", null,
            ("$id", step.OperationId.ToString()), ("$occurrence", occurrenceId.ToString()), ("$key", step.StepKey), ("$attempt", step.Attempt));
    }

    public ScheduleOccurrenceReport SaveScheduleReport(ScheduleOccurrenceReport report)
    {
        lock (sync)
        {
            if (GetScheduleReport(report.OperationId) is null) throw new AgentFault(ErrorCode.OperationConflict);
            using var transaction = database.BeginTransaction();
            Execute("UPDATE ScheduleOccurrences SET reportJson=$json WHERE operationId=$id;", transaction,
                ("$json", ControlJson.Serialize(report)), ("$id", report.OperationId.ToString()));
            AppendScheduleEvent(transaction, report);
            transaction.Commit();
            return report;
        }
    }

    private void AppendScheduleEvent(SqliteTransaction transaction, ScheduleOccurrenceReport report)
    {
        var sequence = checked(Convert.ToInt64(Scalar("SELECT maxSeq FROM State WHERE id=1;", transaction)) + 1);
        var item = new AgentEvent(sequence, null, null, "ScheduleOccurrenceChanged", report.UpdatedAtUtc, ControlJson.Element(report));
        Execute("INSERT INTO EventOutbox VALUES($seq,$json);", transaction, ("$seq", sequence), ("$json", ControlJson.Serialize(item)));
        Execute("UPDATE State SET maxSeq=$seq WHERE id=1;", transaction, ("$seq", sequence));
    }
}
