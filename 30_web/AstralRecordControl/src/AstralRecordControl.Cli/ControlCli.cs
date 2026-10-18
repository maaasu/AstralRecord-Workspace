using System.Buffers.Binary;
using System.IO.Pipes;
using System.Net.Http.Headers;
using System.Runtime.Versioning;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Cli;

[SupportedOSPlatform("windows")]
public static class ControlCli
{
    public static string DefaultSessionFile => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AstralRecordControl", "Cli", "session.bin");
    public static async Task<int> RunAsync(string[] args)
    {
        try
        {
            if (args.Length == 0) throw new ArgumentException();
            var subcommand = args[0] == "login" || args[0] == "logout" ? "" : args.ElementAtOrDefault(1) ?? "";
            var options = ParseOptions(args.Skip(subcommand.Length == 0 ? 1 : 2).ToArray());
            var sessionPath = options.GetValueOrDefault("session-file", DefaultSessionFile);
            if (args[0] == "login")
            {
                RequireOptions(options, "server-url", "session-file");
                if (Console.IsInputRedirected || Console.IsOutputRedirected) throw new InvalidOperationException("InteractiveTerminalRequired");
                var origin = ValidateOrigin(Required(options, "server-url"));
                Console.Error.Write("管理者名: "); var name = Console.ReadLine() ?? "";
                var password = ReadSecret("パスワード: "); var code = ReadSecret("TOTP または回復コード: ");
                using var client = CreateClient(origin);
                var issued = await SendAsync<CliTokenIssued>(client, HttpMethod.Post, "/api/control/v1/auth/cli-token", new CliLoginRequest(name, password, code));
                SecureFiles.Write(sessionPath, ControlJson.Serialize(new CliSession(origin.AbsoluteUri, issued.CredentialId, issued.Token, issued.ExpiresAtUtc)), protect: true);
                Console.WriteLine("CLI認証完了。有効期限15分。資格情報は現在のWindowsユーザーにDPAPIで保存しました。");
                return 0;
            }
            if (args[0] == "runner")
            {
                RequireOptions(options, "pipe-name", "server-id", "session-id", "action", "reason");
                var pipe = options.GetValueOrDefault("pipe-name", ControlProtocol.RunnerPipeName);
                if (subcommand is "snapshot" or "check-update")
                {
                    var snapshot = await RunnerAsync<RunnerSnapshot>(pipe, "GetSnapshot", new { });
                    if (subcommand == "check-update" && (snapshot.Servers.Any(s => s.ProcessState != ProcessState.Stopped) || snapshot.Holds.Length != 0)) throw new InvalidOperationException("RunnerNotSafeToUpdate");
                    Console.WriteLine(ControlJson.Serialize(snapshot)); return 0;
                }
                var server = Guid.Parse(Required(options, "server-id"));
                if (subcommand == "inspect") Console.WriteLine(ControlJson.Serialize(await RunnerAsync<RecoveryInspection>(pipe, "InspectRecovery", new InspectRecoveryRequest(server))));
                else if (subcommand == "recover")
                {
                    using var identity = WindowsIdentity.GetCurrent();
                    if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator)) throw new UnauthorizedAccessException();
                    var action = Enum.Parse<WorkflowRecoveryAction>(Required(options, "action"), false);
                    if (!Enum.IsDefined(action) || action == WorkflowRecoveryAction.Resume) throw new ArgumentException();
                    Guid? session = options.TryGetValue("session-id", out var value) ? Guid.Parse(value) : null;
                    Console.WriteLine(ControlJson.Serialize(await RunnerAsync<RecoveryInspection>(pipe, "Recover", new RunnerRecoveryRequest(server, session, action, Required(options, "reason")))));
                }
                else throw new ArgumentException();
                return 0;
            }
            var cached = ControlJson.Deserialize<CliSession>(SecureFiles.Read(sessionPath, protect: true));
            if (args[0] == "gate") ValidateExpectedOrigin(cached.ServerUrl, Required(options, "expected-server-url"));
            using var http = CreateClient(ValidateOrigin(cached.ServerUrl));
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", cached.Token);
            if (args[0] == "logout")
            {
                RequireOptions(options, "session-file");
                await SendEmptyAsync(http, HttpMethod.Post, "/api/control/v1/auth/cli-token/revoke", null);
                File.Delete(SecureFiles.LocalPath(sessionPath)); Console.WriteLine("CLI認証を失効しました。"); return 0;
            }
            if (cached.ExpiresAtUtc <= DateTimeOffset.UtcNow) throw new InvalidOperationException("CliSessionExpired");
            if (args[0] == "gate")
            {
                RequireOptions(options, "session-file", "request-file", "lease-file", "step", "outcome", "run-reference", "expected-server-url");
                var leasePath = Required(options, "lease-file");
                if (subcommand == "acquire")
                {
                    var request = ControlJson.Deserialize<ReservationAcquireRequest>(ReadBounded(Required(options, "request-file")));
                    if (request.WorkflowId == Guid.Empty) throw new ArgumentException("WorkflowIdRequired");
                    var lease = await SendAsync<GateLease>(http, HttpMethod.Post, "/api/control/v1/reservations", request, request.WorkflowId);
                    if (lease.Status != ReservationStatus.Prepared || lease.WorkflowId != request.WorkflowId) throw new InvalidOperationException("ReservationNotPrepared");
                    if (File.Exists(leasePath))
                    {
                        var previous = ControlJson.Deserialize<GateLease>(SecureFiles.Read(leasePath, protect: false));
                        if (previous.WorkflowId != lease.WorkflowId || previous.ReservationId != lease.ReservationId) throw new InvalidOperationException("LeaseIdentityMismatch");
                    }
                    SecureFiles.Write(leasePath, ControlJson.Serialize(lease), protect: false);
                    Console.WriteLine(ControlJson.Serialize(lease)); return 0;
                }
                var local = ControlJson.Deserialize<GateLease>(SecureFiles.Read(leasePath, protect: false));
                var reference = options.GetValueOrDefault("run-reference", local.RunReference ?? "");
                var prefix = "/api/control/v1/reservations/" + local.ReservationId;
                if (subcommand == "validate")
                {
                    await SendEmptyAsync(http, HttpMethod.Post, prefix + "/validate", new GateValidateRequest(local.WorkflowId, local.Fences, Required(options, "step"), reference));
                    Console.WriteLine("Gate validated.");
                }
                else if (subcommand == "release")
                {
                    var outcome = Enum.Parse<GateOutcome>(Required(options, "outcome"), false);
                    if (!Enum.IsDefined(outcome)) throw new ArgumentException();
                    await SendEmptyAsync(http, HttpMethod.Post, prefix + "/release", new GateReleaseRequest(local.WorkflowId, local.Fences, outcome, reference));
                    SecureFiles.Write(leasePath, ControlJson.Serialize(local with { Status = ReservationStatus.Released, RunReference = reference }), protect: false);
                    Console.WriteLine("Gate released.");
                }
                else if (subcommand == "inspect") Console.WriteLine(ControlJson.Serialize(await SendAsync<GateLease>(http, HttpMethod.Get, prefix, null)));
                else throw new ArgumentException();
                return 0;
            }
            throw new ArgumentException();
        }
        catch (Exception exception) when (exception is ArgumentException or FormatException or InvalidOperationException or IOException or HttpRequestException or UnauthorizedAccessException or CryptographicException or System.Text.Json.JsonException or OperationCanceledException or System.Security.SecurityException)
        {
            // Remote bodies, commands, credentials and exception messages are never echoed.
            Console.Error.WriteLine("Control CLI処理を拒否しました。設定、認証期限、予約状態を確認してください。"); return 1;
        }
    }
    public static Dictionary<string, string> ParseOptions(string[] args)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        for (var index = 0; index < args.Length; index += 2)
            if (!args[index].StartsWith("--", StringComparison.Ordinal) || index + 1 == args.Length || !values.TryAdd(args[index][2..], args[index + 1])) throw new ArgumentException("InvalidArguments");
        return values;
    }
    private static void RequireOptions(Dictionary<string, string> options, params string[] allowed)
    { if (options.Keys.Any(k => !allowed.Contains(k, StringComparer.Ordinal))) throw new ArgumentException(); }
    private static string Required(Dictionary<string, string> options, string key) => options.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value) ? value : throw new ArgumentException("MissingArgument");
    public static Uri ValidateOrigin(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme == "https" && uri.AbsolutePath == "/" && uri.UserInfo.Length == 0 && uri.Query.Length == 0 && uri.Fragment.Length == 0 ? uri : throw new ArgumentException("FixedHttpsOriginRequired");
    public static void ValidateExpectedOrigin(string actual, string expected)
    {
        if (ValidateOrigin(actual) != ValidateOrigin(expected)) throw new InvalidOperationException("ControlOriginMismatch");
    }
    private static HttpClient CreateClient(Uri origin) => new(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false }) { BaseAddress = origin, Timeout = TimeSpan.FromSeconds(45) };
    private static string ReadBounded(string path)
    {
        var local = SecureFiles.LocalPath(path);
        if (new FileInfo(local).Length > ControlProtocol.MaxMessageBytes) throw new IOException();
        return File.ReadAllText(local, new UTF8Encoding(false, true));
    }
    private static async Task<T> SendAsync<T>(HttpClient client, HttpMethod method, string path, object? body, Guid? requestId = null)
    {
        using var response = await SendResponseAsync(client, method, path, body, requestId);
        if (response.Content.Headers.ContentLength > ControlProtocol.MaxMessageBytes) throw new IOException();
        await using var input = await response.Content.ReadAsStreamAsync();
        using var output = new MemoryStream(); var buffer = new byte[8192]; int count;
        while ((count = await input.ReadAsync(buffer)) > 0) { if (output.Length + count > ControlProtocol.MaxMessageBytes) throw new IOException(); output.Write(buffer, 0, count); }
        return ControlJson.Deserialize<T>(new UTF8Encoding(false, true).GetString(output.ToArray()));
    }
    private static async Task SendEmptyAsync(HttpClient client, HttpMethod method, string path, object? body) { using var response = await SendResponseAsync(client, method, path, body, null); }
    private static async Task<HttpResponseMessage> SendResponseAsync(HttpClient client, HttpMethod method, string path, object? body, Guid? requestId)
    {
        using var message = new HttpRequestMessage(method, path);
        if (body is not null) message.Content = new StringContent(ControlJson.Serialize(body), Encoding.UTF8, "application/json");
        if (requestId.HasValue) { message.Headers.Add("Idempotency-Key", requestId.Value.ToString()); message.Headers.IfNoneMatch.Add(EntityTagHeaderValue.Any); }
        var response = await client.SendAsync(message, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode) { response.Dispose(); throw new HttpRequestException(); }
        return response;
    }
    public static async Task<T> RunnerAsync<T>(string pipeName, string method, object body)
    {
        if (string.IsNullOrWhiteSpace(pipeName) || pipeName.Contains('\\') || pipeName.Contains('/')) throw new ArgumentException();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        using var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous,
            method == "Recover" ? TokenImpersonationLevel.Impersonation : TokenImpersonationLevel.Identification);
        await pipe.ConnectAsync(timeout.Token);
        var request = new IpcRequest(1, Guid.NewGuid(), method, ControlJson.Element(body));
        var bytes = Encoding.UTF8.GetBytes(ControlJson.Serialize(request));
        if (bytes.Length > ControlProtocol.MaxMessageBytes) throw new IOException();
        var header = new byte[4]; BinaryPrimitives.WriteInt32LittleEndian(header, bytes.Length);
        await pipe.WriteAsync(header, timeout.Token); await pipe.WriteAsync(bytes, timeout.Token); await pipe.FlushAsync(timeout.Token);
        await pipe.ReadExactlyAsync(header, timeout.Token); var length = BinaryPrimitives.ReadUInt32LittleEndian(header);
        if (length is 0 or > ControlProtocol.MaxMessageBytes) throw new IOException();
        var responseBytes = new byte[(int)length]; await pipe.ReadExactlyAsync(responseBytes, timeout.Token);
        var response = ControlJson.Deserialize<IpcResponse>(new UTF8Encoding(false, true).GetString(responseBytes));
        if (!response.Ok || response.RequestId != request.RequestId) throw new IOException();
        return ControlJson.Deserialize<T>(response.Body.GetRawText());
    }
    private static string ReadSecret(string prompt)
    {
        Console.Error.Write(prompt); var input = new StringBuilder();
        while (true) { var key = Console.ReadKey(true); if (key.Key == ConsoleKey.Enter) { Console.Error.WriteLine(); return input.ToString(); } if (key.Key == ConsoleKey.Backspace) { if (input.Length > 0) input.Length--; } else if (!char.IsControl(key.KeyChar) && input.Length < 1024) input.Append(key.KeyChar); }
    }
}

public sealed record CliSession(string ServerUrl, Guid CredentialId, string Token, DateTimeOffset ExpiresAtUtc);

[SupportedOSPlatform("windows")]
public static class SecureFiles
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AstralRecordControl.Cli.v1");
    public static string LocalPath(string path)
    {
        if (!Path.IsPathFullyQualified(path)) throw new ArgumentException();
        var full = Path.GetFullPath(path);
        if (full.StartsWith(@"\\", StringComparison.Ordinal) || full.AsSpan(Math.Min(2, full.Length)).Contains(':')) throw new ArgumentException();
        for (var current = full; !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
            if ((File.Exists(current) || Directory.Exists(current)) && File.GetAttributes(current).HasFlag(FileAttributes.ReparsePoint)) throw new IOException();
        return full;
    }
    public static void Write(string path, string value, bool protect)
    {
        var full = LocalPath(path); Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        var bytes = Encoding.UTF8.GetBytes(value);
        var temporary = full + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, protect ? ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser) : bytes);
            var security = new FileSecurity(); security.SetAccessRuleProtection(true, false);
            using var identity = WindowsIdentity.GetCurrent();
            foreach (var sid in new[] { identity.User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) }.Distinct())
                security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, AccessControlType.Allow));
            new FileInfo(temporary).SetAccessControl(security);
            LocalPath(full); File.Move(temporary, full, true);
        }
        finally { CryptographicOperations.ZeroMemory(bytes); if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static string Read(string path, bool protect)
    {
        var full = LocalPath(path); if (new FileInfo(full).Length > ControlProtocol.MaxMessageBytes) throw new IOException();
        using var identity = WindowsIdentity.GetCurrent();
        var allowed = new[] { identity.User!, new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null) };
        var security = new FileInfo(full).GetAccessControl();
        if (!security.AreAccessRulesProtected || security.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<FileSystemAccessRule>().Any(r => r.AccessControlType == AccessControlType.Allow && !allowed.Contains((SecurityIdentifier)r.IdentityReference))) throw new UnauthorizedAccessException();
        var bytes = File.ReadAllBytes(full); var plain = protect ? ProtectedData.Unprotect(bytes, Entropy, DataProtectionScope.CurrentUser) : bytes;
        try { return new UTF8Encoding(false, true).GetString(plain); }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }
}
