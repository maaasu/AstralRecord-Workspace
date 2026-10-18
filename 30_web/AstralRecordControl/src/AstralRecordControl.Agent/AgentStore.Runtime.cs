using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public sealed partial class AgentStore
{
    public void ObserveRuntime(RunnerSnapshot snapshot, DateTimeOffset observedAtUtc)
    {
        if (observedAtUtc.Offset != TimeSpan.Zero || snapshot.Servers.Length > 32) throw new AgentFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            var inventory = GetAppliedInventory();
            using var transaction = database.BeginTransaction();
            foreach (var current in snapshot.Servers)
            {
                if (!inventory.Servers.Any(s => s.ServerId == current.ServerId)) throw new AgentFault(ErrorCode.StateConflict);
                var json = (string?)Scalar("SELECT snapshotJson FROM RuntimeObservations WHERE serverId=$id;", transaction, ("$id", current.ServerId.ToString()));
                var previous = json == null ? null : ControlJson.Deserialize<ServerRuntimeSnapshot>(json);
                var newExit = current.LastExitedAtUtc.HasValue && (previous?.LastExitedAtUtc == null || current.LastExitedAtUtc > previous.LastExitedAtUtc);
                if (previous != null && previous.ProcessState == current.ProcessState && previous.CurrentSessionId == current.CurrentSessionId && previous.Readiness == current.Readiness && !newExit) continue;
                Execute("INSERT INTO RuntimeObservations(serverId,snapshotJson) VALUES($id,$json) ON CONFLICT(serverId) DO UPDATE SET snapshotJson=excluded.snapshotJson;", transaction, ("$id", current.ServerId.ToString()), ("$json", ControlJson.Serialize(current)));
                // The state and durable event advance atomically, including while Web is offline.
                if (previous == null && current.ProcessState == ProcessState.Stopped) continue;
                var sequence = checked(Convert.ToInt64(Scalar("SELECT maxSeq FROM State WHERE id=1;", transaction)) + 1);
                var item = new AgentEvent(sequence, null, null, "RuntimeChanged", observedAtUtc, ControlJson.Element(new RuntimeChangedBody(previous, current,
                    UnexpectedExit: newExit && current.LastExitWasExpected == false,
                    RecoveryRequired: current.ProcessState == ProcessState.Unmanaged)));
                Execute("INSERT INTO EventOutbox VALUES($seq,$json);", transaction, ("$seq", sequence), ("$json", ControlJson.Serialize(item)));
                Execute("UPDATE State SET maxSeq=$seq WHERE id=1;", transaction, ("$seq", sequence));
            }
            transaction.Commit();
        }
    }
}
