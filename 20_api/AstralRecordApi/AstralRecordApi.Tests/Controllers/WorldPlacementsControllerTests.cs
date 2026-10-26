using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using System.Text.Json;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class WorldPlacementsControllerTests
{
    private static readonly WorldPlacement Valid = new("spawner:1", "spawner", "world:dev", "world_dev", "mob:1",
        1, 64, 2, 0, 0, true, null);

    [Fact]
    public void ValidationRejectsDuplicateIdsAndNonFiniteCoordinates()
    {
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.Validate([Valid, Valid]));
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.Validate([Valid with { X = double.NaN }]));
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.Validate([Valid with { X = 1.5 }]));
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.Validate([Valid with { PlacementId = "日本語" }]));
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.Validate([Valid,
            Valid with { PlacementId = "spawner:2", TemplateId = "mob:2" }]));
        WorldPlacementValidation.Validate([Valid, Valid with
        {
            PlacementId = "spawner:disabled", Enabled = false
        }]);
        WorldPlacementValidation.Validate([Valid with { Kind = "npc", X = 1.5 },
            Valid with { PlacementId = "npc:2", Kind = "npc", X = 2.5 }]);
        WorldPlacementValidation.Validate([Valid with
        {
            WorldId = "plugins/AstralRecord/worlds/base/starlit_nox",
            WorldName = "plugins/AstralRecord/worlds/base/starlit_nox"
        }]);
    }

    [Fact]
    public void WaystoneInstantKeepsNanoseconds()
    {
        const string instant = "2026-08-01T16:33:08.928898200Z";
        var waystone = Valid with
        {
            PlacementId = "waystone:1", Kind = "waystone", TemplateId = null,
            Waystone = new("ws-1", "Test", false, 0, null, instant, "admin")
        };
        WorldPlacementValidation.Validate([waystone]);
        var copied = JsonSerializer.Deserialize<WorldPlacement>(JsonSerializer.Serialize(waystone));
        Assert.Equal(instant, copied?.Waystone?.CreatedAt);
        Assert.Throws<ArgumentException>(() => WorldPlacementValidation.ValidateStableWaystoneIds(
            [waystone], [waystone with { Waystone = waystone.Waystone! with { Id = "ws-2" } }]));
        WorldPlacementValidation.ValidateStableWaystoneIds([waystone], [waystone with
        {
            Waystone = waystone.Waystone! with { Name = "Renamed" }
        }]);
    }

    [Fact]
    public async Task WriteRequiresDedicatedKeyBeforeRepositoryAccess()
    {
        var fake = new FakeRepository();
        var controller = Controller(fake);
        var response = await controller.Replace(new("0", [Valid]), CancellationToken.None);
        Assert.IsType<UnauthorizedResult>(response);
        Assert.False(fake.Called);
    }

    [Fact]
    public async Task StaleRevisionReturnsConflictAndPreservesSnapshot()
    {
        var fake = new FakeRepository { Conflict = true };
        var controller = Controller(fake);
        controller.HttpContext.Request.Headers["X-World-Placement-Key"] = "writer";
        var response = await controller.Replace(new("1", [Valid]), CancellationToken.None);
        Assert.IsType<ConflictResult>(response);
        Assert.Empty(fake.Current.Placements);
    }

    [Fact]
    public async Task SyncUsesSeparateKeyAndExpectedRevisions()
    {
        var fake = new FakeRepository();
        var controller = Controller(fake);
        controller.HttpContext.Request.Headers["X-World-Placement-Key"] = "writer";
        Assert.IsType<UnauthorizedResult>(await controller.Sync(new("0", "0"), CancellationToken.None));
        Assert.False(fake.Called);
        controller.HttpContext.Request.Headers["X-World-Placement-Key"] = "publisher";
        Assert.IsType<OkObjectResult>(await controller.Sync(new("0", "0"), CancellationToken.None));
    }

    [Fact]
    public async Task WriteRejectsInvalidSnapshotBeforeRepositoryAccess()
    {
        var fake = new FakeRepository();
        var controller = Controller(fake);
        controller.HttpContext.Request.Headers["X-World-Placement-Key"] = "writer";
        Assert.IsType<BadRequestObjectResult>(await controller.Replace(new("0", [Valid, Valid]), CancellationToken.None));
        Assert.False(fake.Called);
    }

    [Fact]
    public async Task UpsertReturnsBadRequestForCombinedSnapshotValidationFailure()
    {
        var fake = new FakeRepository { InvalidMergedSnapshot = true };
        var controller = Controller(fake);
        controller.HttpContext.Request.Headers["X-World-Placement-Key"] = "writer";
        var result = await controller.Upsert("dev", Valid.PlacementId,
            new("0", Valid), CancellationToken.None);
        Assert.IsType<BadRequestObjectResult>(result);
    }

    private static WorldPlacementsController Controller(FakeRepository repository)
    {
        var settings = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["WorldPlacements:WriteKey"] = "writer", ["WorldPlacements:SyncKey"] = "publisher",
            ["ConnectionStrings:WorldPlacementDev"] = "Server=example;Database=WorldPlacementDevDB",
            ["ConnectionStrings:WorldPlacement"] = "Server=example;Database=WorldPlacementDB"
        }).Build();
        return new WorldPlacementsController(repository, settings)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() }
        };
    }

    private sealed class FakeRepository : IWorldPlacementRepository
    {
        public bool Called { get; private set; }
        public bool Conflict { get; init; }
        public bool InvalidMergedSnapshot { get; init; }
        public WorldPlacementSnapshot Current { get; private set; } = new(1, "0", []);
        public Task<WorldPlacementSnapshot> GetAsync(string environment, CancellationToken cancellationToken) => Task.FromResult(Current);
        public Task<WorldPlacementSnapshot?> ReplaceDevAsync(WorldPlacementReplaceRequest request, CancellationToken cancellationToken)
        {
            Called = true;
            if (Conflict) return Task.FromResult<WorldPlacementSnapshot?>(null);
            Current = new(1, "1", request.Placements);
            return Task.FromResult<WorldPlacementSnapshot?>(Current);
        }
        public Task<WorldPlacementSnapshot?> UpsertDevAsync(WorldPlacementUpsertRequest request, CancellationToken cancellationToken)
            => InvalidMergedSnapshot ? throw new ArgumentException("Duplicate placement coordinate")
                : Task.FromResult<WorldPlacementSnapshot?>(Current);
        public Task<WorldPlacementSnapshot?> DeleteDevAsync(string placementId, string expectedRevision, CancellationToken cancellationToken)
            => Task.FromResult<WorldPlacementSnapshot?>(Current);
        public Task<WorldPlacementSnapshot?> SyncAsync(WorldPlacementSyncRequest request, CancellationToken cancellationToken)
        {
            Called = true;
            return Task.FromResult<WorldPlacementSnapshot?>(Current);
        }
    }
}
