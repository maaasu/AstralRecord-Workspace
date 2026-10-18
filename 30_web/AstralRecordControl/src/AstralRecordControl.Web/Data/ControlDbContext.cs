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
    }
    partial void ConfigureOwner(ModelBuilder builder);
    partial void ConfigureInventory(ModelBuilder builder);
    partial void ConfigureOperations(ModelBuilder builder);
    partial void ConfigureCli(ModelBuilder builder);
}
