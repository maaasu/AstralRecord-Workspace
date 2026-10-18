using AstralRecordControl.Contracts;
using Microsoft.Data.Sqlite;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private void MigrateV2(string path, bool backupExisting)
    {
        // SQLite's online backup includes the WAL snapshot. Never overwrite an older safety copy.
        if (backupExisting)
        {
            var backupPath = path + ".before-v2." + clock.GetUtcNow().ToString("yyyyMMddHHmmssfffffff") + "." + Guid.NewGuid().ToString("N") + ".bak";
            using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backupPath, Pooling = false }.ToString());
            backup.Open();
            database.BackupDatabase(backup);
        }
        using var transaction = database.BeginTransaction();
        Execute("ALTER TABLE Servers ADD COLUMN processState TEXT NOT NULL DEFAULT 'Stopped';", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN currentSessionId TEXT;", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN desiredState TEXT NOT NULL DEFAULT 'Stopped';", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN recoveryRequired INTEGER NOT NULL DEFAULT 0;", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN restartCount INTEGER NOT NULL DEFAULT 0;", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN restartWindowUtc TEXT;", transaction);
        Execute("ALTER TABLE Servers ADD COLUMN nextAutoStartUtc TEXT;", transaction);
        Execute("CREATE TABLE ServerSessions(sessionId TEXT PRIMARY KEY,serverId TEXT NOT NULL,startOperationId TEXT NOT NULL,runnerEpoch TEXT NOT NULL,pid INTEGER,processCreatedUtc TEXT,executableFingerprint TEXT NOT NULL,configRevision INTEGER NOT NULL,startedUtc TEXT NOT NULL,exitedUtc TEXT,exitCode INTEGER);", transaction);
        Execute("CREATE TABLE ExecutionLedger(operationId TEXT PRIMARY KEY,commandHash TEXT NOT NULL,commandJson TEXT NOT NULL,serverId TEXT NOT NULL,reservation TEXT NOT NULL,workflow TEXT NOT NULL,fence INTEGER NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,phase TEXT NOT NULL,statusVersion INTEGER NOT NULL,acceptedUtc TEXT NOT NULL,startedUtc TEXT,finishedUtc TEXT,result TEXT,errorCode TEXT,deadlineUtc TEXT NOT NULL);", transaction);
        Execute("CREATE INDEX IX_ExecutionLedger_Reservation ON ExecutionLedger(reservation,phase);", transaction);
        Execute("CREATE TABLE LogSegments(serverId TEXT NOT NULL,sessionId TEXT NOT NULL,segmentId INTEGER NOT NULL,path TEXT NOT NULL,firstSequence INTEGER NOT NULL,lastSequence INTEGER NOT NULL,bytes INTEGER NOT NULL,createdUtc TEXT NOT NULL,PRIMARY KEY(serverId,sessionId,segmentId));", transaction);
        Execute("CREATE TABLE LogLines(serverId TEXT NOT NULL,sessionId TEXT NOT NULL,sequence INTEGER NOT NULL,segmentId INTEGER NOT NULL,offset INTEGER NOT NULL,bytes INTEGER NOT NULL,stream TEXT NOT NULL,truncated INTEGER NOT NULL,createdUtc TEXT NOT NULL,PRIMARY KEY(serverId,sessionId,sequence));", transaction);
        Execute("CREATE TABLE LogCursors(serverId TEXT NOT NULL,sessionId TEXT NOT NULL,nextSequence INTEGER NOT NULL,currentSegment INTEGER NOT NULL,retentionDays INTEGER NOT NULL,PRIMARY KEY(serverId,sessionId));", transaction);
        Execute("CREATE TABLE RecoveryAudit(id TEXT PRIMARY KEY,serverId TEXT NOT NULL,sessionId TEXT,action TEXT NOT NULL,reason TEXT NOT NULL,occurredUtc TEXT NOT NULL);", transaction);
        Execute("PRAGMA user_version=2;", transaction);
        transaction.Commit();
    }

    private void ReconcileAfterRestart()
    {
        // An inherited PID cannot restore anonymous pipes. No process is adopted or killed here.
        using var transaction = database.BeginTransaction();
        Execute("UPDATE Servers SET processState='Unmanaged',recoveryRequired=1,stateRevision=stateRevision+1 WHERE processState IN ('Starting','Running','Stopping') OR currentSessionId IS NOT NULL;", transaction);
        Execute("UPDATE ExecutionLedger SET status='NeedsAttention',phase='Unknown',statusVersion=statusVersion+1,errorCode='RecoveryRequired',finishedUtc=$now WHERE status IN ('Accepted','Running');", transaction, ("$now", clock.GetUtcNow().ToString("O")));
        Execute("UPDATE Servers SET recoveryRequired=1,stateRevision=stateRevision+1 WHERE recoveryRequired=0 AND id IN (SELECT serverId FROM ExecutionLedger WHERE status='NeedsAttention');", transaction);
        Execute("UPDATE DataOperationLedger SET status='NeedsAttention',statusVersion=statusVersion+1 WHERE status IN ('Accepted','Running');", transaction);
        Execute("UPDATE Servers SET recoveryRequired=1,stateRevision=stateRevision+1 WHERE recoveryRequired=0 AND id IN (SELECT serverId FROM DataOperationLedger WHERE status='NeedsAttention');", transaction);
        transaction.Commit();
    }

    private void MigrateV3(string path, bool backupExisting)
    {
        if (backupExisting)
        {
            var backupPath = path + ".before-v3." + clock.GetUtcNow().ToString("yyyyMMddHHmmssfffffff") + "." + Guid.NewGuid().ToString("N") + ".bak";
            using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backupPath, Pooling = false }.ToString());
            backup.Open(); database.BackupDatabase(backup);
        }
        using var transaction = database.BeginTransaction();
        Execute("CREATE TABLE DataOperationLedger(operationId TEXT PRIMARY KEY,commandHash TEXT NOT NULL,commandJson TEXT NOT NULL,serverId TEXT NOT NULL,reservation TEXT NOT NULL,workflow TEXT NOT NULL,fence INTEGER NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,statusVersion INTEGER NOT NULL,readyForRestart INTEGER NOT NULL DEFAULT 0,acceptedUtc TEXT NOT NULL,finishedUtc TEXT);", transaction);
        Execute("CREATE INDEX IX_DataOperationLedger_Reservation ON DataOperationLedger(reservation,status);", transaction);
        Execute("CREATE TABLE DataResumeAudit(id TEXT PRIMARY KEY,operationId TEXT NOT NULL,reason TEXT NOT NULL,occurredUtc TEXT NOT NULL);", transaction);
        Execute("PRAGMA user_version=3;", transaction);
        transaction.Commit();
    }

    private void MigrateV4(string path, bool backupExisting)
    {
        if (backupExisting)
        {
            var backupPath = path + ".before-v4." + clock.GetUtcNow().ToString("yyyyMMddHHmmssfffffff") + "." + Guid.NewGuid().ToString("N") + ".bak";
            using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backupPath, Pooling = false }.ToString());
            backup.Open(); database.BackupDatabase(backup);
        }
        using var transaction = database.BeginTransaction();
        Execute("ALTER TABLE ServerSessions ADD COLUMN lastExitWasExpected INTEGER;", transaction);
        Execute("PRAGMA user_version=4;", transaction);
        transaction.Commit();
    }
}
