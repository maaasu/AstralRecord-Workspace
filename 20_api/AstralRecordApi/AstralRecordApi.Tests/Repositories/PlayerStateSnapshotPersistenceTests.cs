using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Theory]
    [InlineData(1)]
    [InlineData(500)]
    public async Task SaveAsync_DeltaDoesNotLoadUnrelatedInventoryEntries(int unrelatedCount)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.InventoryEntries.AddRange(Enumerable.Range(0, unrelatedCount).Select(index => new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(), InventoryId = fixture.FirstInventoryId,
            ItemCategory = "MATERIAL", ItemId = "material-" + index, Quantity = 1,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        }));
        await fixture.DbContext.SaveChangesAsync();
        var saved = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(fixture.CreateMoveRequest(Guid.NewGuid()));
        Assert.True(saved.Succeeded, saved.Detail);
        Assert.Single(fixture.DbContext.ChangeTracker.Entries<InventoryEntryEntity>());
        Assert.Equal(unrelatedCount + 1, await fixture.DbContext.InventoryEntries.CountAsync(row => !row.IsDeleted));
    }

    [Fact]
    public async Task SaveAsync_QuestDiffPreservesUnchangedChildIdsAndTimestamps()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var activeId = Guid.NewGuid();
        var changedId = Guid.NewGuid();
        var unchangedId = Guid.NewGuid();
        var state = new AccountQuestStateEntity
        {
            AccountQuestStateId = Guid.NewGuid(), AccountId = fixture.AccountId, Version = 1,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        };
        var active = new AccountQuestActiveEntity
        {
            AccountQuestActiveId = activeId, AccountQuestStateId = state.AccountQuestStateId, QuestId = "quest",
            AcceptedAt = fixture.BaseTime, CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        };
        active.ObjectiveProgress.Add(new AccountQuestObjectiveProgressEntity
        {
            AccountQuestObjectiveProgressId = changedId, AccountQuestActiveId = activeId, ObjectiveId = "changed", Progress = 1,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        active.ObjectiveProgress.Add(new AccountQuestObjectiveProgressEntity
        {
            AccountQuestObjectiveProgressId = unchangedId, AccountQuestActiveId = activeId, ObjectiveId = "unchanged", Progress = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        state.ActiveQuests.Add(active);
        fixture.DbContext.AccountQuestStates.Add(state);
        await fixture.DbContext.SaveChangesAsync();
        // DTO millisecond precision is the authoritative acceptedAt shape.
        var acceptedAt = DateTimeOffset.FromUnixTimeMilliseconds(new DateTimeOffset(fixture.BaseTime).ToUnixTimeMilliseconds()).UtcDateTime;
        active.AcceptedAt = acceptedAt;
        await fixture.DbContext.SaveChangesAsync();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            QuestState = JsonSerializer.SerializeToElement(new PlayerStateQuestStateSection
            {
                AccountId = fixture.AccountId, ExpectedVersion = 1, ClientRevision = 2,
                ActiveQuests = [new AccountQuestActiveRequest
                {
                    QuestId = "quest", AcceptedAtEpochMillis = new DateTimeOffset(acceptedAt).ToUnixTimeMilliseconds(),
                    ObjectiveProgress = [new AccountQuestObjectiveProgressRequest { ObjectiveId = "changed", Progress = 3 },
                        new AccountQuestObjectiveProgressRequest { ObjectiveId = "unchanged", Progress = 2 }],
                }],
            }),
        };
        var saved = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        Assert.True(saved.Succeeded, saved.Detail);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(activeId, (await fixture.DbContext.AccountQuestActives.SingleAsync()).AccountQuestActiveId);
        Assert.Equal(fixture.BaseTime, (await fixture.DbContext.AccountQuestActives.SingleAsync()).UpdatedAt);
        var rows = await fixture.DbContext.AccountQuestObjectiveProgresses.ToDictionaryAsync(row => row.ObjectiveId);
        Assert.Equal(changedId, rows["changed"].AccountQuestObjectiveProgressId);
        Assert.Equal(3, rows["changed"].Progress);
        Assert.Equal(unchangedId, rows["unchanged"].AccountQuestObjectiveProgressId);
        Assert.Equal(fixture.BaseTime, rows["unchanged"].UpdatedAt);
    }
}
