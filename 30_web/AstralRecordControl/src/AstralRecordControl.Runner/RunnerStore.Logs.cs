using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

public sealed partial class RunnerStore
{
    private const long MaxLogSegmentBytes = 100L * 1024 * 1024;
    private const int MaxLogLineBytes = 64 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);
    private DateTimeOffset lastLogPruneUtc = DateTimeOffset.MinValue;

    public LogBatch ReadLogs(ReadLogsRequest query)
    {
        if (query.ServerId == Guid.Empty || query.Limit is < 1 or > 200 || query.AfterSequence < 0) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        lock (sync)
        {
            var sessionId = query.SessionId;
            if (sessionId is null)
            {
                var latest = (string?)Scalar("SELECT sessionId FROM ServerSessions WHERE serverId=$server ORDER BY startedUtc DESC LIMIT 1;", null, ("$server", query.ServerId.ToString()));
                if (latest is not null) sessionId = Guid.Parse(latest);
            }
            if (sessionId is null) return new LogBatch { ServerId = query.ServerId };
            if (Scalar("SELECT 1 FROM ServerSessions WHERE serverId=$server AND sessionId=$session;", null, ("$server", query.ServerId.ToString()), ("$session", sessionId.Value.ToString())) is null) throw new RunnerFault(ErrorCode.StateConflict);
            var earliest = Convert.ToInt64(Scalar("SELECT COALESCE((SELECT MIN(sequence) FROM LogLines WHERE serverId=$server AND sessionId=$session),(SELECT nextSequence FROM LogCursors WHERE serverId=$server AND sessionId=$session),0);", null, ("$server", query.ServerId.ToString()), ("$session", sessionId.Value.ToString())));
            var gap = earliest > 0 && query.AfterSequence < earliest - 1;
            var after = gap ? earliest - 1 : query.AfterSequence;
            var rows = new List<LogRow>();
            var next = after;
            var bytes = 0;
            var requestedSegment = ParseSegment(query.SegmentId);
            using var command = Command("SELECT l.sequence,l.createdUtc,l.stream,l.truncated,s.path,l.offset,l.bytes,l.segmentId FROM LogLines l JOIN LogSegments s ON s.serverId=l.serverId AND s.sessionId=l.sessionId AND s.segmentId=l.segmentId WHERE l.serverId=$server AND l.sessionId=$session AND l.sequence>$after AND ($segment IS NULL OR l.segmentId=$segment) ORDER BY l.sequence LIMIT $limit;", null,
                ("$server", query.ServerId.ToString()), ("$session", sessionId.Value.ToString()), ("$after", after), ("$segment", requestedSegment is null ? DBNull.Value : requestedSegment.Value), ("$limit", query.Limit));
            using var reader = command.ExecuteReader();
            while (reader.Read())
            {
                var length = reader.GetInt32(6);
                if (rows.Count > 0 && bytes + length > MaxLogLineBytes) break;
                var path = reader.GetString(4);
                LocalResourceBoundary.ValidateLocalPath(path);
                var buffer = new byte[length];
                using (var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    file.Position = reader.GetInt64(5);
                    file.ReadExactly(buffer);
                }
                var text = StrictUtf8.GetString(buffer);
                rows.Add(new LogRow(reader.GetInt64(0), DateTimeOffset.Parse(reader.GetString(1)), reader.GetString(2), text, reader.GetInt64(3) != 0));
                bytes += length;
                next = reader.GetInt64(0);
            }
            return new LogBatch { ServerId = query.ServerId, SessionId = sessionId, SegmentId = requestedSegment?.ToString(), EarliestAvailableCursor = earliest, NextCursor = next, Gap = gap, Rows = rows.ToArray() };
        }
    }

    private static long? ParseSegment(string? value)
    {
        if (value is null) return null;
        if (!long.TryParse(value, out var result) || result < 1 || result.ToString() != value) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        return result;
    }

    internal void AppendLog(Guid serverId, Guid sessionId, string stream, string text, bool forceTruncated = false)
    {
        // Split by UTF-8 bytes without splitting a scalar. An overlong line exposes truncated=true on every chunk.
        var chunk = new StringBuilder();
        var byteCount = 0;
        var chunks = new List<string>();
        foreach (var rune in text.EnumerateRunes())
        {
            if (byteCount + rune.Utf8SequenceLength > MaxLogLineBytes)
            {
                chunks.Add(chunk.ToString());
                chunk.Clear(); byteCount = 0;
            }
            chunk.Append(rune.ToString());
            byteCount += rune.Utf8SequenceLength;
        }
        chunks.Add(chunk.ToString());
        foreach (var part in chunks) AppendLogChunk(serverId, sessionId, stream, part, forceTruncated || chunks.Count > 1);
    }

    private void AppendLogChunk(Guid serverId, Guid sessionId, string stream, string text, bool truncated)
    {
        var bytes = StrictUtf8.GetBytes(text);
        lock (sync)
        {
            Execute("INSERT INTO LogCursors VALUES($server,$session,1,0) ON CONFLICT DO NOTHING;", null, ("$server", serverId.ToString()), ("$session", sessionId.ToString()));
            var nextSequence = Convert.ToInt64(Scalar("SELECT nextSequence FROM LogCursors WHERE serverId=$server AND sessionId=$session;", null, ("$server", serverId.ToString()), ("$session", sessionId.ToString())));
            var segmentId = Convert.ToInt64(Scalar("SELECT COALESCE(MAX(segmentId),0) FROM LogSegments WHERE serverId=$server AND sessionId=$session;", null, ("$server", serverId.ToString()), ("$session", sessionId.ToString())));
            var existingBytes = segmentId == 0 ? 0 : Convert.ToInt64(Scalar("SELECT bytes FROM LogSegments WHERE serverId=$server AND sessionId=$session AND segmentId=$segment;", null, ("$server", serverId.ToString()), ("$session", sessionId.ToString()), ("$segment", segmentId)));
            if (segmentId == 0 || existingBytes + bytes.Length > MaxLogSegmentBytes)
            {
                segmentId = Convert.ToInt64(Scalar("SELECT currentSegment+1 FROM LogCursors WHERE serverId=$server AND sessionId=$session;", null, ("$server", serverId.ToString()), ("$session", sessionId.ToString())));
                existingBytes = 0;
            }
            var directory = Path.Combine(dataRoot, "logs", serverId.ToString(), sessionId.ToString());
            Directory.CreateDirectory(directory);
            LocalResourceBoundary.ValidateLocalPath(directory);
            var path = Path.Combine(directory, segmentId.ToString("D8") + ".log");
            LocalResourceBoundary.ValidateLocalPath(path);
            using (var file = new FileStream(path, FileMode.OpenOrCreate, FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough))
            {
                file.Position = existingBytes;
                file.Write(bytes);
                file.Flush(true);
            }
            using var transaction = database.BeginTransaction();
            Execute("INSERT INTO LogSegments(serverId,sessionId,segmentId,path,firstSequence,lastSequence,bytes,createdUtc) VALUES($server,$session,$segment,$path,$seq,$seq,$bytes,$utc) ON CONFLICT(serverId,sessionId,segmentId) DO UPDATE SET lastSequence=$seq,bytes=$bytes;", transaction,
                ("$server", serverId.ToString()), ("$session", sessionId.ToString()), ("$segment", segmentId), ("$path", path), ("$seq", nextSequence), ("$bytes", existingBytes + bytes.Length), ("$utc", clock.GetUtcNow().ToString("O")));
            Execute("INSERT INTO LogLines VALUES($server,$session,$seq,$segment,$offset,$bytes,$stream,$truncated,$utc);", transaction,
                ("$server", serverId.ToString()), ("$session", sessionId.ToString()), ("$seq", nextSequence), ("$segment", segmentId), ("$offset", existingBytes), ("$bytes", bytes.Length), ("$stream", stream), ("$truncated", truncated ? 1 : 0), ("$utc", clock.GetUtcNow().ToString("O")));
            Execute("UPDATE LogCursors SET nextSequence=$next,currentSegment=$segment WHERE serverId=$server AND sessionId=$session;", transaction,
                ("$next", checked(nextSequence + 1)), ("$segment", segmentId), ("$server", serverId.ToString()), ("$session", sessionId.ToString()));
            transaction.Commit();
        }
    }

    public void PruneLogs(bool force = false)
    {
        lock (sync)
        {
            var now = clock.GetUtcNow();
            if (!force && now - lastLogPruneUtc < TimeSpan.FromMinutes(30)) return;
            lastLogPruneUtc = now;
            var candidates = new List<(string Server, string Session, long Segment, string Path)>();
            using (var command = Command("SELECT g.serverId,g.sessionId,g.segmentId,g.path,s.config,MAX(l.createdUtc) FROM LogSegments g JOIN Servers s ON s.id=g.serverId JOIN LogLines l ON l.serverId=g.serverId AND l.sessionId=g.sessionId AND l.segmentId=g.segmentId WHERE NOT(s.currentSessionId=g.sessionId AND s.processState<>'Stopped' AND g.segmentId=(SELECT MAX(x.segmentId) FROM LogSegments x WHERE x.serverId=g.serverId AND x.sessionId=g.sessionId)) GROUP BY g.serverId,g.sessionId,g.segmentId;"))
            using (var reader = command.ExecuteReader())
                while (reader.Read())
                {
                    var config = ControlJson.Deserialize<ServerConfig>(reader.GetString(4));
                    if (DateTimeOffset.Parse(reader.GetString(5)) < now.AddDays(-config.LogRetentionDays)) candidates.Add((reader.GetString(0), reader.GetString(1), reader.GetInt64(2), reader.GetString(3)));
                }
            foreach (var item in candidates)
            {
                LocalResourceBoundary.ValidateLocalPath(item.Path);
                // Delete both indexes in one commit. A leftover file can be retried on the next prune.
                File.Delete(item.Path);
                using var transaction = database.BeginTransaction();
                Execute("DELETE FROM LogLines WHERE serverId=$server AND sessionId=$session AND segmentId=$segment;", transaction, ("$server", item.Server), ("$session", item.Session), ("$segment", item.Segment));
                Execute("DELETE FROM LogSegments WHERE serverId=$server AND sessionId=$session AND segmentId=$segment;", transaction, ("$server", item.Server), ("$session", item.Session), ("$segment", item.Segment));
                transaction.Commit();
            }
        }
    }
}
