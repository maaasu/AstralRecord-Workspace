using System.Security.Cryptography;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent;

public sealed record AgentConfiguration
{
    public Guid HostId { get; init; }
    public string ServerUrl { get; init; } = "";
    public string RunnerPipeName { get; init; } = ControlProtocol.RunnerPipeName;
    public string TokenFile { get; init; } = "credentials/token.bin";
    public HostResources LocalResources { get; init; } = new();
    public bool EnableFileControl { get; init; }
    public static string DefaultDataRoot => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "Agent");
    public Uri Validate()
    {
        if (HostId == Guid.Empty || !Uri.TryCreate(ServerUrl, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps
            || uri.UserInfo.Length != 0 || uri.Query.Length != 0 || uri.Fragment.Length != 0 || uri.AbsolutePath != "/"
            || !ControlProtocol.IsRelativePath(TokenFile) || string.IsNullOrWhiteSpace(RunnerPipeName) || RunnerPipeName.Contains('\\') || RunnerPipeName.Contains('/'))
            throw new ArgumentException("Invalid Agent configuration. An HTTPS origin and HostId are required.");
        AgentResourceBoundary.ValidateResources(LocalResources);
        return uri;
    }
}

public sealed class AgentFault(ErrorCode code) : Exception(code.ToString())
{
    public ErrorCode Code { get; } = code;
}

public sealed class ProtectedTokenFile(string dataRoot, string relativePath)
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("AstralRecordControl.Agent.v1");
    public string Read()
    {
        var path = AgentResourceBoundary.Resolve(dataRoot, relativePath);
        CredentialAcl.Validate(path);
        var bytes = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.LocalMachine);
        try
        {
            var token = new UTF8Encoding(false, true).GetString(bytes);
            ValidateToken(token);
            return token;
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }
    public void Write(string token)
    {
        ValidateToken(token);
        var path = AgentResourceBoundary.Resolve(dataRoot, relativePath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        CredentialAcl.ProtectDirectory(Path.GetDirectoryName(path)!);
        var plain = Encoding.UTF8.GetBytes(token);
        try
        {
            var encrypted = ProtectedData.Protect(plain, Entropy, DataProtectionScope.LocalMachine);
            var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            File.WriteAllBytes(temporary, encrypted);
            File.Move(temporary, path, true);
        }
        finally { CryptographicOperations.ZeroMemory(plain); }
    }
    private static void ValidateToken(string token)
    {
        if (token.Length is < 32 or > 256 || token.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not '-' and not '_' and not '+' and not '/' and not '='))
            throw new ArgumentException("Invalid Agent token format.");
    }
}

public static class CredentialAcl
{
    private static SecurityIdentifier[] AllowedSids() =>
    [
        (SecurityIdentifier)new NTAccount("NT SERVICE", "AstralRecordControl.Agent").Translate(typeof(SecurityIdentifier)),
        new(WellKnownSidType.LocalSystemSid, null),
        new(WellKnownSidType.BuiltinAdministratorsSid, null)
    ];
    public static void RequireAdministrator()
    {
        using var identity = WindowsIdentity.GetCurrent();
        if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator)) throw new UnauthorizedAccessException("Administrator privileges are required for configuration.");
    }
    public static void ProtectDirectory(string path)
    {
        RequireAdministrator();
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false);
        foreach (var sid in AllowedSids()) security.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl, InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(path).SetAccessControl(security);
    }
    public static void Validate(string path)
    {
        var allowed = AllowedSids();
        var directory = new DirectoryInfo(Path.GetDirectoryName(path)!);
        var directorySecurity = directory.GetAccessControl();
        if (!directorySecurity.AreAccessRulesProtected) throw new UnauthorizedAccessException("Credential directory ACL must be protected.");
        ValidateRules(directorySecurity.GetAccessRules(true, true, typeof(SecurityIdentifier)), allowed);
        ValidateRules(new FileInfo(path).GetAccessControl().GetAccessRules(true, true, typeof(SecurityIdentifier)), allowed);
    }
    private static void ValidateRules(AuthorizationRuleCollection rules, SecurityIdentifier[] allowed)
    {
        foreach (FileSystemAccessRule rule in rules)
            if (rule.AccessControlType == AccessControlType.Allow && !allowed.Contains((SecurityIdentifier)rule.IdentityReference))
                throw new UnauthorizedAccessException("Credential ACL grants access to an unexpected principal.");
    }
}
