using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;

namespace AstralRecordApi.Authentication;

[AttributeUsage(AttributeTargets.Method)]
public sealed class BuilderSchematicAccessAttribute : TypeFilterAttribute
{
    public const string DevKeyHeader = "X-Builder-Dev-Key";

    public BuilderSchematicAccessAttribute(bool devOnly = false) : base(typeof(BuilderSchematicAccessFilter))
    {
        Arguments = [devOnly];
    }
}

internal sealed class BuilderSchematicAccessFilter(
    IConfiguration configuration, IBuilderSchematicRepository repository, bool devOnly) : IResourceFilter
{
    public void OnResourceExecuting(ResourceExecutingContext context)
    {
        var expectedKey = configuration["BuilderSchematics:DevKey"];
        if (string.IsNullOrWhiteSpace(expectedKey) || expectedKey == configuration["ApiKey:Key"] || !repository.IsEnabled)
        {
            context.Result = new StatusCodeResult(StatusCodes.Status503ServiceUnavailable);
            return;
        }

        if (!devOnly)
            return;

        var headers = context.HttpContext.Request.Headers;
        if (!headers.TryGetValue(BuilderSchematicAccessAttribute.DevKeyHeader, out var values)
            || values.Count != 1
            || values[0] is not { } suppliedKey
            || !CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(Encoding.UTF8.GetBytes(expectedKey)),
                SHA256.HashData(Encoding.UTF8.GetBytes(suppliedKey))))
        {
            context.Result = new UnauthorizedResult();
        }
    }

    public void OnResourceExecuted(ResourceExecutedContext context) { }
}
