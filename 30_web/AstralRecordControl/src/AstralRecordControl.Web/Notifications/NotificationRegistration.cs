using Microsoft.Extensions.DependencyInjection;

namespace AstralRecordControl.Web.Notifications;

public static class NotificationRegistration
{
    public static IServiceCollection AddControlNotifications(this IServiceCollection services)
    {
        services.AddScoped<NotificationService>();
        services.AddScoped<NotificationRetentionService>();
        // A direct client avoids HttpClientFactory's default URL logging. The path
        // or query of a registered Webhook can itself be a credential.
        services.AddSingleton(new HttpClient(NotificationHttpHandler.Create()) { Timeout = TimeSpan.FromSeconds(5) });
        services.AddScoped<NotificationDeliveryService>();
        services.AddHostedService<NotificationDispatcher>();
        services.AddHostedService<NotificationRetentionWorker>();
        return services;
    }
}
