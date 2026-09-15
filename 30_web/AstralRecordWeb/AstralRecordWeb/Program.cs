using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using AstralRecordWeb.Authorization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddRazorPages();
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddSingleton<IMinecraftStatusProbe, MinecraftStatusProbe>();
builder.Services.AddSingleton<MinecraftServerStatusService>();
builder.Services
    .AddOptions<PublicSiteOptions>()
    .Bind(builder.Configuration.GetSection(PublicSiteOptions.SectionName))
    .Validate(
        options => Enum.IsDefined(options.Phase),
        $"{PublicSiteOptions.SectionName}:Phase must be OpenAlpha or Release.")
    .Validate(
        options => !string.IsNullOrWhiteSpace(options.JavaServerAddress),
        $"{PublicSiteOptions.SectionName}:JavaServerAddress is required.")
    .Validate(
        options => options.JavaServerPort is >= 1 and <= 65535,
        $"{PublicSiteOptions.SectionName}:JavaServerPort must be between 1 and 65535.")
    .Validate(
        options => options.BedrockServerPort is >= 1 and <= 65535,
        $"{PublicSiteOptions.SectionName}:BedrockServerPort must be between 1 and 65535.")
    .ValidateOnStart();
builder.Services.Configure<AstralRecordApiOptions>(
    builder.Configuration.GetSection(AstralRecordApiOptions.SectionName));
builder.Services
    .AddOptions<ReleaseNoteOptions>()
    .Bind(builder.Configuration.GetSection(ReleaseNoteOptions.SectionName))
    .Validate(
        options => Uri.TryCreate(options.PublicBaseUrl, UriKind.Absolute, out var uri)
            && uri.Scheme == Uri.UriSchemeHttps,
        $"{ReleaseNoteOptions.SectionName}:PublicBaseUrl must be an HTTPS URL.")
    .ValidateOnStart();
builder.Services.PostConfigure<ReleaseNoteOptions>(options =>
{
    if (builder.Environment.IsDevelopment())
        options.SyncOnStartup = false;
});
builder.Services
    .AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme)
    .AddCookie(options =>
    {
        options.LoginPath = "/Login";
        options.LogoutPath = "/Logout";
        options.AccessDeniedPath = "/Login";
        options.ExpireTimeSpan = TimeSpan.FromHours(12);
        options.SlidingExpiration = false;
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
    });
builder.Services.AddAuthorization(options =>
{
    options.AddPolicy("WebAdminOnly", policy =>
    {
        policy.RequireAuthenticatedUser();
        policy.Requirements.Add(new WebAdminRequirement());
    });
});
builder.Services.AddScoped<IAuthorizationHandler, WebAdminAuthorizationHandler>();
builder.Services.AddHttpClient<PlayerProfileApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);
    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddHttpClient<WebAuthApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);

    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddHttpClient<NetworkManagementApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);
    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddHttpClient<ItemMasterApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);

    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddHttpClient<MarketApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);

    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddHttpClient<ReleaseNoteApiClient>((serviceProvider, httpClient) =>
{
    var options = serviceProvider.GetRequiredService<IOptions<AstralRecordApiOptions>>().Value;
    httpClient.BaseAddress = new Uri(options.BaseUrl);

    if (!string.IsNullOrWhiteSpace(options.ApiKey))
        httpClient.DefaultRequestHeaders.Add("X-Api-Key", options.ApiKey);
});
builder.Services.AddSingleton<ReleaseNoteCatalog>();
builder.Services.AddHostedService<ReleaseNotePublicationHostedService>();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error");
    // The default HSTS value is 30 days. You may want to change this for production scenarios, see https://aka.ms/aspnetcore-hsts.
    app.UseHsts();
}

app.UseHttpsRedirection();

app.UseStaticFiles();

app.UseRouting();

app.UseAuthentication();
app.UseAuthorization();

app.MapStaticAssets();
app.MapRazorPages()
   .WithStaticAssets();

app.Run();

public partial class Program;
