namespace AstralRecordApi.Models;

/// <summary>アカウントモード、ユーザー権限、管理用プロファイルの API 入力契約です。</summary>
public static class AccessControlContract
{
    public const byte PlayerAccountMode = 0;
    public const byte AdminAccountMode = 2;
    public const int PlayerPermission = 0;
    public const int DonorPermission = 5;
    public const int AdminPermission = 99;
    public const string GameProfile = "GAME";
    public const string AdminProfile = "ADMIN";

    public static bool IsValidAccountMode(byte mode) =>
        mode is PlayerAccountMode or AdminAccountMode;

    public static bool IsValidUserPermission(int permission) =>
        permission is PlayerPermission or DonorPermission or AdminPermission;

    public static bool IsValidProfile(string? profile) =>
        string.Equals(profile, GameProfile, StringComparison.Ordinal)
        || string.Equals(profile, AdminProfile, StringComparison.Ordinal);
}
