namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private DateTimeOffset nextAuditRetentionAtUtc = DateTimeOffset.MinValue;

    public void PruneAuditHistory()
    {
        lock (sync)
        {
            if (clock.GetUtcNow() < nextAuditRetentionAtUtc) return;
            var cutoff = clock.GetUtcNow().AddDays(-90).ToString("O");
            using var transaction = database.BeginTransaction();
            Execute("DELETE FROM RecoveryAudit WHERE julianday(occurredUtc)<julianday($cutoff) AND serverId IN (SELECT id FROM Servers WHERE recoveryRequired=0) AND NOT EXISTS(SELECT 1 FROM Holds WHERE resource='server:'||RecoveryAudit.serverId);", transaction, ("$cutoff", cutoff));
            Execute("DELETE FROM DataResumeAudit WHERE julianday(occurredUtc)<julianday($cutoff) AND operationId IN (SELECT operationId FROM DataOperationLedger WHERE status IN ('Succeeded','Failed','Rejected','Expired','Cancelled') AND NOT EXISTS(SELECT 1 FROM Holds WHERE reservation=DataOperationLedger.reservation) AND serverId IN (SELECT id FROM Servers WHERE recoveryRequired=0));", transaction, ("$cutoff", cutoff));
            transaction.Commit();
            nextAuditRetentionAtUtc = clock.GetUtcNow().AddHours(1);
        }
    }
}
