using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public sealed partial class ControlDbContext(DbContextOptions<ControlDbContext> options) : IdentityUserContext<ControlUser, Guid>(options)
{
    protected override void OnModelCreating(ModelBuilder builder)
    {
        base.OnModelCreating(builder);
        ConfigureOwner(builder);
        ConfigureInventory(builder);
        ConfigureOperations(builder);
        ConfigureCli(builder);
        ConfigureTransfers(builder);
        ConfigureSchedules(builder);
        ConfigureMetrics(builder);
        ConfigureNotifications(builder);
    }
    partial void ConfigureOwner(ModelBuilder builder);
    partial void ConfigureInventory(ModelBuilder builder);
    partial void ConfigureOperations(ModelBuilder builder);
    partial void ConfigureCli(ModelBuilder builder);
    partial void ConfigureTransfers(ModelBuilder builder);
    partial void ConfigureSchedules(ModelBuilder builder);
    partial void ConfigureMetrics(ModelBuilder builder);
}
