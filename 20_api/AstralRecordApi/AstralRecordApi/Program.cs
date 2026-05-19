using AstralRecordApi.Authentication;
using AstralRecordApi.Data;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Diagnostics;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.OpenApi;
using Scalar.AspNetCore;

var builder = WebApplication.CreateBuilder(args);

builder.Logging.AddSimpleConsole(options =>
{
    options.TimestampFormat = "yyyy-MM-dd HH:mm:ss ";
});

// Add services to the container.

builder.Services.Configure<FileDatabaseOptions>(
    builder.Configuration.GetSection(FileDatabaseOptions.SectionName));

builder.Services.AddDbContext<AstralRecordDbContext>(options =>
    options.UseSqlServer(
        builder.Configuration.GetConnectionString("SqlServer")
        ?? throw new InvalidOperationException("Connection string 'SqlServer' is not configured."),
        sqlServerOptions => sqlServerOptions.EnableRetryOnFailure()));

builder.Services.AddProblemDetails();

builder.Services.AddSingleton<IBuffRepository, BuffRepository>();
builder.Services.AddSingleton<IClassRepository, ClassRepository>();
builder.Services.AddSingleton<IItemRepository, ItemRepository>();
builder.Services.AddSingleton<ILootRepository, LootRepository>();
builder.Services.AddSingleton<IRecipeRepository, RecipeRepository>();
builder.Services.AddSingleton<ISetEffectRepository, SetEffectRepository>();
builder.Services.AddSingleton<ISkillRepository, SkillRepository>();
builder.Services.AddScoped<IUserRepository, UserRepository>();
builder.Services.AddScoped<IAccountRepository, AccountRepository>();
builder.Services.AddScoped<IInventoryRepository, InventoryRepository>();
builder.Services.AddScoped<IEquipmentRepository, EquipmentRepository>();
builder.Services.AddScoped<IEquipmentLoadoutRepository, EquipmentLoadoutRepository>();
builder.Services.AddScoped<IRuneRepository, RuneRepository>();
builder.Services.AddScoped<IEquipmentService, EquipmentService>();
builder.Services.AddScoped<IRuneService, RuneService>();

builder.Services.AddAuthentication(ApiKeyAuthenticationHandler.SchemeName)
    .AddScheme<AuthenticationSchemeOptions, ApiKeyAuthenticationHandler>(
        ApiKeyAuthenticationHandler.SchemeName, _ => { });

builder.Services.AddAuthorization();

builder.Services.AddControllers();
builder.Services.AddOpenApi(options =>
{
    options.AddDocumentTransformer((document, _, _) =>
    {
        document.Info.Title = "Astral Record API";
        document.Info.Version = "v1";
        document.Info.Description = "Minecraft Purpur サーバー向け MMO RPG プラグイン Astral Record の Web API";

        // API キー認証スキームを OpenAPI ドキュメントに追加
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes ??= new Dictionary<string, IOpenApiSecurityScheme>();
        document.Components.SecuritySchemes[ApiKeyAuthenticationHandler.SchemeName] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.ApiKey,
            In = ParameterLocation.Header,
            Name = ApiKeyAuthenticationHandler.HeaderName,
            Description = "リクエストヘッダー `X-Api-Key` に API キーを指定してください。"
        };

        document.Security ??= [];
        document.Security.Add(new OpenApiSecurityRequirement
        {
            [new OpenApiSecuritySchemeReference(ApiKeyAuthenticationHandler.SchemeName, document)] = []
        });

        return Task.CompletedTask;
    });
});

var app = builder.Build();

var logger = app.Services.GetRequiredService<ILoggerFactory>().CreateLogger("Startup");

app.UseExceptionHandler(handler =>
{
    handler.Run(async context =>
    {
        var exception = context.Features.Get<IExceptionHandlerFeature>()?.Error;

        if (IsDatabaseUnavailable(exception))
        {
            logger.LogError(exception, "データベース接続に失敗しました");
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await Results.Problem(
                title: "Database unavailable",
                detail: "SQL Server に接続できないため、リクエストを処理できません。接続文字列と DB 状態を確認してください。",
                statusCode: StatusCodes.Status503ServiceUnavailable).ExecuteAsync(context);
            return;
        }

        logger.LogError(exception, "未処理の例外が発生しました");
        context.Response.StatusCode = StatusCodes.Status500InternalServerError;
        await Results.Problem(
            title: "Internal server error",
            detail: "サーバー内部で予期しないエラーが発生しました。",
            statusCode: StatusCodes.Status500InternalServerError).ExecuteAsync(context);
    });
});

// OpenAPI ドキュメント (/openapi/v1.json)
app.MapOpenApi();

// Scalar API ドキュメント UI (/scalar)
app.MapScalarApiReference("/scalar");

// Swagger UI (/swagger)
app.UseSwaggerUI(options =>
{
    options.SwaggerEndpoint("/openapi/v1.json", "Astral Record API v1");
    options.RoutePrefix = "swagger";
});

app.UseHttpsRedirection();

app.UseAuthentication();
app.UseAuthorization();

app.MapControllers().RequireAuthorization();

logger.LogInformation("静的データの読み込みを開始します");
_ = app.Services.GetRequiredService<IBuffRepository>();
_ = app.Services.GetRequiredService<IItemRepository>();
_ = app.Services.GetRequiredService<ILootRepository>();
_ = app.Services.GetRequiredService<IRecipeRepository>();
logger.LogInformation("静的データの読み込みが完了しました");

app.Run();

static bool IsDatabaseUnavailable(Exception? exception)
{
    if (exception is null)
        return false;

    if (exception is SqlException sqlException)
    {
        return sqlException.Number is 4060 or 18456 or -2 or 53;
    }

    if (exception is Microsoft.EntityFrameworkCore.Storage.RetryLimitExceededException retryLimitExceededException)
        return IsDatabaseUnavailable(retryLimitExceededException.InnerException);

    if (exception is DbUpdateException dbUpdateException)
        return IsDatabaseUnavailable(dbUpdateException.InnerException);

    if (exception is InvalidOperationException invalidOperationException)
        return IsDatabaseUnavailable(invalidOperationException.InnerException);

    return IsDatabaseUnavailable(exception.InnerException);
}
