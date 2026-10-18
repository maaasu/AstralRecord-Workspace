using System.Text.Json;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public sealed partial class AgentStore
{
    public (ExecuteRequest Command, bool DispatchAttempted) GetDispatch(Guid operationId)
    {
        lock (sync)
        {
            using var command = Command("SELECT commandJson,commandSha256,dispatchAttempted FROM OperationInbox WHERE operationId=$id;", null, ("$id", operationId.ToString()));
            using var reader = command.ExecuteReader();
            if (!reader.Read()) throw new AgentFault(ErrorCode.OperationConflict);
            return (new ExecuteRequest(operationId, reader.GetString(0), reader.GetString(1)), reader.GetBoolean(2));
        }
    }

    public void MarkDispatchAttempted(Guid operationId)
    {
        lock (sync) Execute("UPDATE OperationInbox SET dispatchAttempted=1 WHERE operationId=$id;", null, ("$id", operationId.ToString()));
    }

    public ExecutionSnapshot? GetOperation(Guid operationId)
    {
        lock (sync)
        {
            using var command = Command("SELECT commandSha256,status,statusVersion,resultJson,errorCode,updatedAtUtc FROM OperationInbox WHERE operationId=$id;", null, ("$id", operationId.ToString()));
            using var reader = command.ExecuteReader();
            if (!reader.Read()) return null;
            return new ExecutionSnapshot
            {
                OperationId = operationId,
                CommandSha256 = reader.GetString(0),
                Status = Enum.Parse<OperationStatus>(reader.GetString(1)),
                StatusVersion = reader.GetInt64(2),
                Phase = reader.IsDBNull(3) ? ExecutionPhase.Accepted : ExecutionPhase.Unknown,
                Result = reader.IsDBNull(3) ? ControlJson.Element(new { }) : ControlJson.Deserialize<JsonElement>(reader.GetString(3)),
                ErrorCode = reader.IsDBNull(4) ? null : reader.GetString(4),
                UpdatedAtUtc = DateTimeOffset.Parse(reader.GetString(5), System.Globalization.CultureInfo.InvariantCulture)
            };
        }
    }

    public ExecutionSnapshot AcceptOperation(ExecuteRequest request)
    {
        OperationValidation.Read(request, hostId);
        lock (sync)
        {
            var previous = GetOperation(request.OperationId);
            if (previous != null)
            {
                if (previous.CommandSha256 != request.CommandSha256) throw new AgentFault(ErrorCode.OperationConflict);
                return previous;
            }
            using var transaction = database.BeginTransaction();
            var now = DateTimeOffset.UtcNow;
            Execute("INSERT INTO OperationInbox(operationId,commandJson,commandSha256,status,statusVersion,updatedAtUtc) VALUES($id,$json,$hash,$status,1,$now);", transaction,
                ("$id", request.OperationId.ToString()), ("$json", request.CommandJson), ("$hash", request.CommandSha256), ("$status", OperationStatus.Accepted.ToString()), ("$now", now.ToString("O")));
            AppendOperationEvent(transaction, request.OperationId, 1, OperationStatus.Accepted, null, null, now);
            transaction.Commit();
            return GetOperation(request.OperationId)!;
        }
    }

    public ExecutionSnapshot SaveOperation(ExecutionSnapshot execution)
    {
        lock (sync)
        {
            var previous = GetOperation(execution.OperationId) ?? throw new AgentFault(ErrorCode.RecoveryRequired);
            if (previous.CommandSha256 != execution.CommandSha256) throw new AgentFault(ErrorCode.OperationConflict);
            if (execution.StatusVersion <= previous.StatusVersion) return previous;
            if (OperationValidation.IsTerminal(previous.Status) && previous.Status != execution.Status) throw new AgentFault(ErrorCode.RecoveryRequired);
            using var transaction = database.BeginTransaction();
            Execute("UPDATE OperationInbox SET status=$status,statusVersion=$version,resultJson=$result,errorCode=$error,updatedAtUtc=$now WHERE operationId=$id;", transaction,
                ("$status", execution.Status.ToString()), ("$version", execution.StatusVersion), ("$result", execution.Result.GetRawText()), ("$error", (object?)execution.ErrorCode ?? DBNull.Value), ("$now", execution.UpdatedAtUtc.ToString("O")), ("$id", execution.OperationId.ToString()));
            AppendOperationEvent(transaction, execution.OperationId, execution.StatusVersion, execution.Status, execution.Result, execution.ErrorCode, execution.UpdatedAtUtc);
            transaction.Commit();
            return execution;
        }
    }

    public (Guid OperationId, string CommandSha256, OperationStatus Status)[] ReadNonTerminalOperations()
    {
        lock (sync)
        {
            using var command = Command("SELECT operationId,commandSha256,status FROM OperationInbox;");
            using var reader = command.ExecuteReader();
            var result = new List<(Guid, string, OperationStatus)>();
            while (reader.Read())
            {
                var status = Enum.Parse<OperationStatus>(reader.GetString(2));
                if (!OperationValidation.IsTerminal(status)) result.Add((Guid.Parse(reader.GetString(0)), reader.GetString(1), status));
            }
            return result.ToArray();
        }
    }

    private void AppendOperationEvent(Microsoft.Data.Sqlite.SqliteTransaction transaction, Guid id, long version, OperationStatus status, JsonElement? result, string? error, DateTimeOffset occurredAt)
    {
        var sequence = checked(Convert.ToInt64(Scalar("SELECT maxSeq FROM State WHERE id=1;", transaction)) + 1);
        var item = new AgentEvent(sequence, id, version, "OperationChanged", occurredAt, ControlJson.Element(new { status, result, errorCode = error }));
        Execute("INSERT INTO EventOutbox VALUES($seq,$json);", transaction, ("$seq", sequence), ("$json", ControlJson.Serialize(item)));
        Execute("UPDATE State SET maxSeq=$seq WHERE id=1;", transaction, ("$seq", sequence));
    }
}
