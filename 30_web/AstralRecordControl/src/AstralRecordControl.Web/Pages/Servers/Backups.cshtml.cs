using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordControl.Web.Pages.Servers;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class BackupsModel(ControlDbContext db, IConfiguration configuration) : ServerDataPageModel(db, configuration);
