using System.Data;
using System.Data.Common;
using System.Data.SqlTypes;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;
using Xunit.Abstractions;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>
/// Production system_health XELで観測したplayer-stateのSerializable deadlockを再現する診断テストです。
/// 通常実行ではSQL Serverへ接続しません。
/// </summary>
[Trait("Category", "SqlServerIntegration")]
public sealed class PlayerStateSnapshotDeadlockSqlServerTests(ITestOutputHelper output)
{
    private const string OptInVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Fact]
    public async Task DifferentAccountsReplacingQuestChildren_ReproducesDeadlock()
    {
        if (!Enabled()) return;

        await using var database = await TemporaryDatabase.CreateAsync();
        var snapshotIds = await SeedSeparatedSnapshotLedgerGapsAsync(database);
        var first = await SeedAccountAsync(database, "quest-a", includeQuest: true);
        var second = await SeedAccountAsync(database, "quest-b", includeQuest: true);
        using var readGate = new CommandCompletionGate(2, IsQuestGraphRead);

        var attempts = new[]
        {
            Task.Run(() => SaveCapturingAsync(database, CreateQuestReplacement(first, snapshotIds.First), readGate)),
            Task.Run(() => SaveCapturingAsync(database, CreateQuestReplacement(second, snapshotIds.Second), readGate)),
        };
        await readGate.WaitUntilAllReachedAsync();
        await database.AssertSessionsNotWaitingAsync(readGate.SessionIds);
        readGate.Release();
        var results = await Task.WhenAll(attempts).WaitAsync(TimeSpan.FromSeconds(30));
        var outcomes = results
            .Select(result => new OperationOutcome(result.Result?.Succeeded == true, result.Exception))
            .ToArray();
        AssertExpectedDeadlockPair(outcomes);

        output.WriteLine("Quest replacement: 1/2 operations ended in SQL 1205; the survivor succeeded.");
        foreach (var result in results.Where(result => result.Exception is not null))
            output.WriteLine(result.Exception!.ToString());
    }

    [Fact]
    public async Task TradeAccountReadAndSnapshotInventoryThenAccountUpdate_ReproducesDeadlock()
    {
        if (!Enabled()) return;

        await using var database = await TemporaryDatabase.CreateAsync();
        var snapshotIds = await SeedSeparatedSnapshotLedgerGapsAsync(database);
        var playerA = await SeedAccountAsync(database, "trade-a", includeQuest: false, includeInventories: true);
        var playerB = await SeedAccountAsync(database, "trade-b", includeQuest: false, includeInventories: true);
        using var tradeAccountRead = new CommandPause(IsTradeAccountRead);
        using var snapshotInventoryRead = new CommandPause(IsSnapshotInventoryRead);

        var trade = Task.Run(() => TradeCapturingAsync(database, new TradeCommitRequest
        {
            OperationId = Guid.NewGuid(),
            PlayerAAccountId = playerA.AccountId,
            PlayerBAccountId = playerB.AccountId,
            PlayerAItems = [],
            PlayerBItems = [],
            PlayerAGold = 0,
            PlayerBGold = 0,
            UpdatedBy = playerA.AccountId,
        }, tradeAccountRead));
        // tradeはaccountのSerializable共有lockを保持したまま停止する。
        await tradeAccountRead.WaitUntilReachedAsync();

        var snapshot = Task.Run(() => SaveCapturingAsync(database,
            CreateInventoryAndProgressSnapshot(playerA, snapshotIds.First), snapshotInventoryRead));
        // accountのSとUは互換なので、snapshotはaccount U取得後にinventory Uまで到達できる。
        await snapshotInventoryRead.WaitUntilReachedAsync();
        var tradeSession = tradeAccountRead.SessionId;
        var snapshotSession = snapshotInventoryRead.SessionId;
        snapshotInventoryRead.Release();
        // snapshotがaccount X変換をtradeのSに待っていることをDMVで確認してからtradeを進める。
        await database.WaitForLockAsync(snapshotSession, tradeSession);
        // tradeの次のinventory Uがsnapshotのinventory Uを待ち、XELと同じ循環が成立する。
        tradeAccountRead.Release();

        await Task.WhenAll((Task)trade, snapshot).WaitAsync(TimeSpan.FromSeconds(30));
        var tradeResult = await trade;
        var snapshotResult = await snapshot;
        var outcomes = new[]
        {
            new OperationOutcome(tradeResult.Result?.Succeeded == true, tradeResult.Exception),
            new OperationOutcome(snapshotResult.Result?.Succeeded == true, snapshotResult.Exception),
        };
        AssertExpectedDeadlockPair(outcomes);

        output.WriteLine("Trade account read / snapshot inventory-account update: 1/2 operations ended in SQL 1205; the survivor succeeded.");
        foreach (var outcome in outcomes.Where(outcome => outcome.Exception is not null))
            output.WriteLine(outcome.Exception!.ToString());
    }

    private static async Task<SaveAttempt> SaveCapturingAsync(
        TemporaryDatabase database,
        PlayerStateSnapshotSaveRequest request,
        params IInterceptor[] interceptors)
    {
        await using var context = database.Context(interceptors);
        try
        {
            var result = await new PlayerStateSnapshotRepository(context).SaveAsync(request);
            return new SaveAttempt(result, null);
        }
        catch (Exception exception)
        {
            return new SaveAttempt(null, exception);
        }
    }

    private static async Task<TradeAttempt> TradeCapturingAsync(
        TemporaryDatabase database,
        TradeCommitRequest request,
        params IInterceptor[] interceptors)
    {
        await using var context = database.Context(interceptors);
        try
        {
            var result = await new TradeRepository(context).CommitAsync(request);
            return new TradeAttempt(result, null);
        }
        catch (Exception exception)
        {
            return new TradeAttempt(null, exception);
        }
    }

    private static void AssertExpectedDeadlockPair(IReadOnlyCollection<OperationOutcome> outcomes)
    {
        Assert.Equal(2, outcomes.Count);
        var unexpected = outcomes.Where(outcome =>
            outcome.Exception is null ? !outcome.Succeeded : !IsOnlySqlDeadlock(outcome.Exception)).ToArray();
        Assert.True(unexpected.Length == 0,
            "Expected only one successful operation and one SQL 1205 victim, but received:" + Environment.NewLine
            + string.Join(Environment.NewLine, unexpected.Select(DescribeOutcome)));
        Assert.Equal(1, outcomes.Count(outcome => outcome.Exception is null && outcome.Succeeded));
        Assert.Equal(1, outcomes.Count(outcome => outcome.Exception is not null && IsOnlySqlDeadlock(outcome.Exception)));
    }

    private static bool IsOnlySqlDeadlock(Exception exception)
    {
        var chain = EnumerateExceptions(exception).ToArray();
        return chain.Length > 0
            && chain[^1] is SqlException { Number: 1205 }
            && chain.Take(chain.Length - 1)
                .All(candidate => candidate is InvalidOperationException or DbUpdateException);
    }

    private static string DescribeOutcome(OperationOutcome outcome)
        => outcome.Exception is null
            ? $"Operation returned without an exception but Succeeded={outcome.Succeeded}."
            : outcome.Exception.ToString();

    private static IEnumerable<Exception> EnumerateExceptions(Exception? exception)
    {
        while (exception is not null)
        {
            yield return exception;
            exception = exception.InnerException;
        }
    }

    private static bool IsQuestGraphRead(string commandText)
        => commandText.StartsWith("SELECT", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("[dbo].[account_quest_state]", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("[dbo].[account_quest_completion]", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("[dbo].[account_quest_objective_progress]", StringComparison.OrdinalIgnoreCase);

    private static bool IsTradeAccountRead(string commandText)
        => commandText.StartsWith("SELECT", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("FROM [dbo].[account] AS", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("[uuid] IN", StringComparison.OrdinalIgnoreCase);

    private static bool IsSnapshotInventoryRead(string commandText)
        => commandText.StartsWith("SELECT", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("FROM [dbo].[inventory] WITH (UPDLOCK, HOLDLOCK)", StringComparison.OrdinalIgnoreCase)
            && commandText.Contains("ORDER BY [inventory_id]", StringComparison.OrdinalIgnoreCase);

    private static PlayerStateSnapshotSaveRequest CreateQuestReplacement(SeededAccount seeded, Guid snapshotId)
        => new()
        {
            SnapshotId = snapshotId,
            AccountId = seeded.AccountId,
            UpdatedBy = seeded.AccountId,
            AccountProgress = Section(new PlayerStateAccountProgressSection
            {
                AccountId = seeded.AccountId,
                ClientRevision = 2,
                ExpectedProgressVersion = 1,
                Level = 2,
                TotalExperience = 100,
                ClassId = "adventurer",
                ClassLevel = 2,
                ClassExperience = 100,
                ClassProgresses =
                [
                    new AccountClassProgressUpdateRequest
                    {
                        ClassId = "adventurer", Level = 2, Experience = 100,
                    },
                ],
            }),
            QuestState = Section(new PlayerStateQuestStateSection
            {
                AccountId = seeded.AccountId,
                ClientRevision = 2,
                ExpectedVersion = 1,
                ActiveQuests =
                [
                    new AccountQuestActiveRequest
                    {
                        QuestId = seeded.Label + "-next-active",
                        AcceptedAtEpochMillis = 1_789_128_000_000,
                        ObjectiveProgress =
                        [
                            new AccountQuestObjectiveProgressRequest
                                { ObjectiveId = seeded.Label + "-next-objective-a", Progress = 2 },
                            new AccountQuestObjectiveProgressRequest
                                { ObjectiveId = seeded.Label + "-next-objective-b", Progress = 3 },
                        ],
                    },
                ],
                Completions =
                [
                    new AccountQuestCompletionRequest
                        { QuestId = seeded.Label + "-next-completion", CompletedAtEpochMillis = 1_789_128_001_000 },
                ],
                Cooldowns = [],
            }),
        };

    private static PlayerStateSnapshotSaveRequest CreateInventoryAndProgressSnapshot(
        SeededAccount seeded,
        Guid snapshotId)
        => new()
        {
            SnapshotId = snapshotId,
            AccountId = seeded.AccountId,
            UpdatedBy = seeded.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = seeded.BagInventoryId,
                    ExpectedEntries = [],
                    Entries = [],
                },
            ],
            AccountProgress = Section(new PlayerStateAccountProgressSection
            {
                AccountId = seeded.AccountId,
                ClientRevision = 2,
                ExpectedProgressVersion = 1,
                Level = 2,
                TotalExperience = 100,
                ClassId = "adventurer",
                ClassLevel = 2,
                ClassExperience = 100,
                ClassProgresses =
                [
                    new AccountClassProgressUpdateRequest
                    {
                        ClassId = "adventurer", Level = 2, Experience = 100,
                    },
                ],
            }),
        };

    private static JsonElement Section<T>(T value)
        => JsonSerializer.SerializeToElement(value, new JsonSerializerOptions(JsonSerializerDefaults.Web));

    private static async Task<SeededAccount> SeedAccountAsync(
        TemporaryDatabase database,
        string label,
        bool includeQuest,
        bool includeInventories = false)
    {
        await using var context = database.Context();
        var now = new DateTime(2026, 9, 11, 17, 30, 0, DateTimeKind.Utc);
        var accountId = Guid.NewGuid();
        context.Accounts.Add(new AccountEntity
        {
            Uuid = accountId,
            UserId = Guid.NewGuid(),
            AccountName = label,
            IsActive = true,
            Level = 1,
            TotalExperience = 0,
            ClassId = "adventurer",
            ClassLevel = 1,
            ClassExperience = 0,
            ProgressVersion = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        context.AccountClassProgresses.Add(new AccountClassProgressEntity
        {
            AccountId = accountId,
            ClassId = "adventurer",
            Level = 1,
            Experience = 0,
            UpdatedAt = now,
            UpdatedBy = accountId,
        });
        var bagInventoryId = Guid.Empty;
        if (includeInventories)
        {
            bagInventoryId = Guid.NewGuid();
            context.Inventories.AddRange(
                new InventoryEntity
                {
                    InventoryId = bagInventoryId,
                    AccountId = accountId,
                    InventoryType = "BAG",
                    InventoryProfile = "GAME",
                    SlotCapacity = 36,
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                },
                new InventoryEntity
                {
                    InventoryId = Guid.NewGuid(),
                    AccountId = accountId,
                    InventoryType = "CURRENCY",
                    InventoryProfile = "GAME",
                    SlotCapacity = 0,
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
        }

        if (includeQuest)
        {
            var stateId = Guid.NewGuid();
            var activeId = Guid.NewGuid();
            var state = new AccountQuestStateEntity
            {
                AccountQuestStateId = stateId,
                AccountId = accountId,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            };
            var active = new AccountQuestActiveEntity
            {
                AccountQuestActiveId = activeId,
                AccountQuestStateId = stateId,
                QuestId = label + "-active",
                AcceptedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            };
            for (var index = 0; index < 4; index++)
            {
                active.ObjectiveProgress.Add(new AccountQuestObjectiveProgressEntity
                {
                    AccountQuestObjectiveProgressId = Guid.NewGuid(),
                    AccountQuestActiveId = activeId,
                    ObjectiveId = $"{label}-objective-{index}",
                    Progress = index + 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
                state.Completions.Add(new AccountQuestCompletionEntity
                {
                    AccountQuestCompletionId = Guid.NewGuid(),
                    AccountQuestStateId = stateId,
                    QuestId = $"{label}-completion-{index}",
                    CompletedAt = now,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
            }
            state.ActiveQuests.Add(active);
            context.AccountQuestStates.Add(state);
        }

        await context.SaveChangesAsync();
        return new SeededAccount(accountId, label, bagInventoryId);
    }

    /// <summary>SQL Serverのuniqueidentifier順で、2要求を互いに異なるPK gapへ決定的に配置する。</summary>
    private static async Task<SnapshotIds> SeedSeparatedSnapshotLedgerGapsAsync(TemporaryDatabase database)
    {
        await using var context = database.Context();
        var now = new DateTime(2026, 9, 11, 17, 0, 0, DateTimeKind.Utc);
        var ordered = new[]
            {
                Guid.Parse("10000000-0000-0000-0000-000000000001"),
                Guid.Parse("20000000-0000-0000-0000-000000000002"),
                Guid.Parse("30000000-0000-0000-0000-000000000003"),
                Guid.Parse("40000000-0000-0000-0000-000000000004"),
                Guid.Parse("50000000-0000-0000-0000-000000000005"),
                Guid.Parse("60000000-0000-0000-0000-000000000006"),
            }
            .OrderBy(value => new SqlGuid(value))
            .ToArray();
        foreach (var boundary in new[] { ordered[0], ordered[2], ordered[3], ordered[5] })
        {
            context.PlayerStateSnapshots.Add(new PlayerStateSnapshotEntity
            {
                SnapshotId = boundary,
                AccountId = Guid.NewGuid(),
                RequestHash = new string('0', 64),
                AckPayloadJson = "{}",
                CreatedAt = now,
                CompletedAt = now,
                CreatedBy = Guid.NewGuid(),
            });
        }
        await context.SaveChangesAsync();
        return new SnapshotIds(ordered[1], ordered[4]);
    }

    private static bool Enabled() => string.Equals(
        Environment.GetEnvironmentVariable(OptInVariable),
        "1",
        StringComparison.Ordinal);

    private sealed record SeededAccount(Guid AccountId, string Label, Guid BagInventoryId);

    private sealed record SnapshotIds(Guid First, Guid Second);

    private sealed record SaveAttempt(PlayerStateSnapshotSaveResult? Result, Exception? Exception);

    private sealed record TradeAttempt(TradeOperationResult<TradeCommitResponse>? Result, Exception? Exception);

    private sealed record OperationOutcome(bool Succeeded, Exception? Exception);

    private sealed class CommandPause(Func<string, bool> predicate) : DbCommandInterceptor, IDisposable
    {
        private readonly TaskCompletionSource reached = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly ManualResetEventSlim release = new(false);

        internal int SessionId { get; private set; }

        internal Task WaitUntilReachedAsync() => reached.Task.WaitAsync(TimeSpan.FromSeconds(10));

        internal void Release() => release.Set();

        public override ValueTask<DbDataReader> ReaderExecutedAsync(
            DbCommand command,
            CommandExecutedEventData eventData,
            DbDataReader result,
            CancellationToken cancellationToken = default)
        {
            if (!predicate(command.CommandText))
                return new ValueTask<DbDataReader>(result);

            SessionId = ((SqlConnection)command.Connection!).ServerProcessId;
            reached.TrySetResult();
            if (!release.Wait(TimeSpan.FromSeconds(15), cancellationToken))
                throw new TimeoutException("The diagnostic SQL command was not released before the deadline.");
            return new ValueTask<DbDataReader>(result);
        }

        public void Dispose()
        {
            release.Set();
            release.Dispose();
        }
    }

    /// <summary>対象SELECT完了後のsessionを全参加者が揃うまで停止し、呼出側の検査後に一斉解放する。</summary>
    private sealed class CommandCompletionGate(int participants, Func<string, bool> predicate) : DbCommandInterceptor, IDisposable
    {
        private readonly CountdownEvent arrivals = new(participants);
        private readonly ManualResetEventSlim release = new(false);
        private readonly TaskCompletionSource allReached = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly HashSet<int> sessionIds = [];
        private readonly Lock sync = new();

        internal IReadOnlyCollection<int> SessionIds
        {
            get
            {
                lock (sync)
                    return sessionIds.ToArray();
            }
        }

        internal Task WaitUntilAllReachedAsync() => allReached.Task.WaitAsync(TimeSpan.FromSeconds(10));

        internal void Release() => release.Set();

        public override ValueTask<InterceptionResult> DataReaderClosingAsync(
            DbCommand command,
            DataReaderClosingEventData eventData,
            InterceptionResult result)
        {
            if (predicate(command.CommandText))
            {
                var sessionId = ((SqlConnection)command.Connection!).ServerProcessId;
                lock (sync)
                {
                    if (sessionIds.Add(sessionId))
                        arrivals.Signal();
                    if (arrivals.IsSet)
                        allReached.TrySetResult();
                }
                if (!release.Wait(TimeSpan.FromSeconds(15)))
                    throw new TimeoutException("The diagnostic quest reads were not released before the deadline.");
            }
            return new ValueTask<InterceptionResult>(result);
        }

        public void Dispose()
        {
            release.Set();
            release.Dispose();
            arrivals.Dispose();
        }
    }

    /// <summary>localhostのrandom UUID付き一時DBだけを作成・破棄する。</summary>
    private sealed class TemporaryDatabase(string name) : IAsyncDisposable
    {
        private const string Prefix = "AstralRecordSnapshotDeadlock_";

        internal AstralRecordDbContext Context(params IInterceptor[] interceptors)
        {
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlServer(ConnectionString(name), sql => sql.CommandTimeout(15));
            if (interceptors.Length > 0)
                options.AddInterceptors(interceptors);
            return new AstralRecordDbContext(options.Options);
        }

        internal static async Task<TemporaryDatabase> CreateAsync()
        {
            var database = new TemporaryDatabase(Prefix + Guid.NewGuid().ToString("N"));
            await ExecuteAsync("master", $"CREATE DATABASE [{database.Name}]");
            try
            {
                await using var context = database.Context();
                await context.Database.EnsureCreatedAsync();
                return database;
            }
            catch
            {
                await database.DisposeAsync();
                throw;
            }
        }

        internal async Task WaitForLockAsync(int waitingSession, int blockingSession)
        {
            await using var connection = new SqlConnection(ConnectionString(name));
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT CAST(blocking_session_id AS int)
                FROM sys.dm_exec_requests
                WHERE session_id = @session AND wait_type LIKE 'LCK_M_%'
                """, connection) { CommandTimeout = 5 };
            command.Parameters.AddWithValue("@session", waitingSession);
            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (DateTime.UtcNow < deadline)
            {
                var blocker = await command.ExecuteScalarAsync();
                if (blocker is int session && session == blockingSession)
                    return;
                await Task.Delay(20);
            }
            throw new TimeoutException(
                $"SQL Server did not report session {waitingSession} waiting on session {blockingSession}.");
        }

        internal async Task AssertSessionsNotWaitingAsync(IReadOnlyCollection<int> sessionIds)
        {
            Assert.Equal(2, sessionIds.Count);
            await using var connection = new SqlConnection(ConnectionString(name));
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT COUNT(*)
                FROM sys.dm_exec_requests
                WHERE session_id IN (@first, @second) AND blocking_session_id <> 0
                """, connection) { CommandTimeout = 5 };
            command.Parameters.AddWithValue("@first", sessionIds.First());
            command.Parameters.AddWithValue("@second", sessionIds.Last());
            Assert.Equal(0, Convert.ToInt32(await command.ExecuteScalarAsync()));
        }

        public async ValueTask DisposeAsync()
        {
            if (!name.StartsWith(Prefix, StringComparison.Ordinal)
                || !Guid.TryParseExact(name[Prefix.Length..], "N", out _))
            {
                throw new InvalidOperationException("Only this test's generated temporary database may be dropped.");
            }
            await ExecuteAsync("master",
                $"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{name}]");
        }

        private string Name => name;

        private static string ConnectionString(string database) => new SqlConnectionStringBuilder
        {
            DataSource = @"localhost\SQLEXPRESS",
            InitialCatalog = database,
            IntegratedSecurity = true,
            TrustServerCertificate = true,
            ConnectTimeout = 10,
            Pooling = false,
        }.ConnectionString;

        private static async Task ExecuteAsync(string database, string sql)
        {
            await using var connection = new SqlConnection(ConnectionString(database));
            await connection.OpenAsync();
            await using var command = new SqlCommand(sql, connection) { CommandTimeout = 30 };
            await command.ExecuteNonQueryAsync();
        }
    }
}
