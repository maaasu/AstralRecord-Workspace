namespace SkillTreeEditor.Server.Services;

/// <summary>ローカル専用エディタへのブラウザー経由の別サイトアクセスを拒否します。</summary>
public sealed class LocalEditorRequestBoundaryMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context)
    {
        var request = context.Request;
        if (!IsLoopbackHost(request.Host.Host)
            || !HasAllowedOrigin(request)
            || !HasAllowedFetchSite(request))
        {
            await Results.Problem(
                statusCode: StatusCodes.Status403Forbidden,
                title: "ローカル専用エディタへのアクセスを拒否しました。",
                detail: "localhost またはループバックアドレスでエディタを開いてください。別サイトからのアクセスは許可されていません。")
                .ExecuteAsync(context);
            return;
        }

        await next(context);
    }

    private static bool HasAllowedOrigin(HttpRequest request)
    {
        var values = request.Headers.Origin;
        // Origin を送らない CLI と、ブラウザーの通常の画面取得は維持します。
        if (values.Count == 0) return true;
        if (values.Count != 1
            || !Uri.TryCreate(values[0], UriKind.Absolute, out var origin)
            || origin.Scheme is not "http" and not "https"
            || !IsLoopbackHost(origin.Host)
            || origin.UserInfo.Length != 0
            || origin.AbsolutePath != "/"
            || origin.Query.Length != 0
            || origin.Fragment.Length != 0)
            return false;

        // ローカルの別ポートや localhost / 127.0.0.1 の変化を許容します。
        // Vite の文字列 proxy は Host を API 側へ書き換えるため、Origin と Host は一致しません。
        return true;
    }

    private static bool HasAllowedFetchSite(HttpRequest request)
    {
        var values = request.Headers["Sec-Fetch-Site"];
        return values.Count == 0
               || values.Count == 1 && (values[0] is "same-origin" or "same-site" or "none"
                   || values[0] == "cross-site" && IsTopLevelNavigation(request));
    }

    private static bool IsTopLevelNavigation(HttpRequest request)
        => (HttpMethods.IsGet(request.Method) || HttpMethods.IsHead(request.Method))
           && !request.Path.StartsWithSegments("/api", StringComparison.OrdinalIgnoreCase)
           && request.Headers["Sec-Fetch-Mode"] == "navigate"
           && request.Headers["Sec-Fetch-Dest"] == "document";

    private static bool IsLoopbackHost(string host)
        => host.Equals("localhost", StringComparison.OrdinalIgnoreCase)
           || host is "127.0.0.1" or "::1" or "[::1]";
}
