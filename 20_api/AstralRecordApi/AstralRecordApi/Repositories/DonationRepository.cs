using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>受領前審査・承認台帳と、ゲームDBへ再送できる配布指示を管理します。</summary>
public sealed class DonationRepository(ManagementDbContext management, AstralRecordDbContext game,
    IDonationDiscordRepository discord, IDataProtectionProvider protection, TimeProvider clock) : IDonationRepository
{
    private readonly IDataProtector protector = protection.CreateProtector("AstralRecord.Donations.Entries.v1");
    private DbSet<DonationRequestEntity> Requests => management.Set<DonationRequestEntity>();
    private DbSet<DonationLedgerEntity> Ledgers => management.Set<DonationLedgerEntity>();
    private DbSet<DonationGrantEntity> Grants => management.Set<DonationGrantEntity>();
    private DateTime Now => clock.GetUtcNow().UtcDateTime;

    public async Task<DonationListResponse> ListAsync(Guid actor, bool all, int page, int pageSize)
    {
        page = Math.Clamp(page, 1, 1_000_000);
        pageSize = Math.Clamp(pageSize, 1, 100);
        var query = Requests.AsNoTracking().Where(x => all || x.UserUuid == actor);
        var count = await query.CountAsync();
        var rows = await query.OrderByDescending(x => x.CreatedAtUtc).ThenBy(x => x.Id)
            .Skip((page - 1) * pageSize).Take(pageSize).ToListAsync();
        var total = await Ledgers.Where(x => all || x.UserUuid == actor).SumAsync(x => (long?)x.TotalApprovedAmount) ?? 0;
        return new(total, rows.Select(x => Map(x, false)).ToArray(), await discord.GetAsync(actor), count);
    }

    public async Task<DonationResponse?> GetAsync(Guid id, Guid actor, bool admin)
    {
        var row = await Requests.AsNoTracking().SingleOrDefaultAsync(x => x.Id == id && (admin || x.UserUuid == actor));
        return row is null ? null : Map(row, true);
    }

    public async Task<DonationResponse> CreateAsync(Guid user, DonationCreateRequest request)
    {
        var entries = Normalize(request);
        var link = await discord.VerifyMembershipAsync(user);
        var mcid = await management.Players.Where(x => x.PlayerUuid == user).Select(x => x.Mcid).SingleOrDefaultAsync()
            ?? throw new UnauthorizedAccessException("ログインし直してください。");
        return await Locked(user, async _ =>
        {
            var existing = await Requests.SingleOrDefaultAsync(x => x.Id == request.OperationId);
            if (existing is not null)
            {
                if (existing.UserUuid != user || existing.DeclaredAmount != request.DeclaredAmount
                    || JsonSerializer.Serialize(ReadEntries(existing)) != JsonSerializer.Serialize(entries))
                    throw new DonationConflictException("同じ申請番号で異なる申請は送信できません。");
                return Map(existing, true);
            }
            if (await Requests.CountAsync(x => x.UserUuid == user && (x.Status == DonationRules.Pending || x.Status == DonationRules.Reviewing)) >= 2)
                throw new DonationConflictException("未完了の申請は2件までです。結果を待つか、申請中のものを取り消してください。");
            var hashes = entries.Select(Fingerprint).ToArray();
            if (await management.Set<DonationEntryFingerprintEntity>().AnyAsync(x => hashes.Contains(x.Fingerprint)))
                throw new DonationConflictException("同じ支払い情報が既に申請されています。");
            var row = new DonationRequestEntity
            {
                Id = request.OperationId, UserUuid = user, Mcid = mcid, DeclaredAmount = request.DeclaredAmount,
                ProtectedEntries = protector.Protect(JsonSerializer.Serialize(entries)), TermsVersion = request.TermsVersion,
                DiscordUserId = link.DiscordUserId, DiscordName = link.DiscordName, CreatedAtUtc = Now,
            };
            Requests.Add(row);
            management.Set<DonationEntryFingerprintEntity>().AddRange(hashes.Select(hash => new DonationEntryFingerprintEntity
            { Fingerprint = hash, RequestId = row.Id }));
            Notify(user, "Submitted", row.DeclaredAmount, $"{row.DeclaredAmount:N0}円の寄付申請を受け付けました。受領確認後に結果をお知らせします。");
            return Map(row, true);
        });
    }

    public async Task<DonationResponse> TransitionAsync(Guid id, Guid actor, string action, int? amount = null, string? reason = null)
    {
        var owner = await Requests.Where(x => x.Id == id).Select(x => (Guid?)x.UserUuid).SingleOrDefaultAsync()
            ?? throw new KeyNotFoundException();
        return await Locked(owner, async ledger =>
        {
            var row = await Requests.SingleAsync(x => x.Id == id);
            if (action == "cancel")
            {
                if (owner != actor) throw new UnauthorizedAccessException();
                if (row.Status == DonationRules.Cancelled) return Map(row, true);
                Require(row.Status == DonationRules.Pending, "確認開始後は取り消せません。");
                row.Status = DonationRules.Cancelled;
                row.DecidedAtUtc = Now;
                await ReleaseFingerprints(id);
                return Map(row, true);
            }
            if (action == "review")
            {
                if (row.Status == DonationRules.Reviewing && row.ReviewerUuid == actor) return Map(row, true);
                Require(row.Status == DonationRules.Pending, "別の管理者が確認中、または処理済みです。");
                row.Status = DonationRules.Reviewing;
                row.ReviewerUuid = actor;
                row.ReviewStartedAtUtc = Now;
                return Map(row, true);
            }
            Require(row.ReviewerUuid == actor, "確認を開始した管理者が処理してください。");
            if (action == "approve")
            {
                var actual = amount ?? row.DeclaredAmount;
                if (actual is < 1 or > DonationRules.MaximumAmount) throw new ArgumentException("承認額は1～1,000,000円の整数で入力してください。");
                if (row.Status == DonationRules.Approved && row.ApprovedAmount == actual) return Map(row, true);
                Require(row.Status == DonationRules.Reviewing, "確認中の申請のみ承認できます。");
                row.ApprovedAmount = actual;
                row.Status = DonationRules.Approved;
                row.Reason = actual < DonationRules.MinimumAmount
                    ? "今回は実受領額で処理しました。次回からは実際にお送りいただく金額も500円以上となるようお願いいたします。" : null;
                ledger.TotalApprovedAmount = checked(ledger.TotalApprovedAmount + actual);
                // 受領記録はゲームDBの停止中も確定できる。配布は永続累計から後続で照合する。
                row.ApprovedThroughAmount = ledger.TotalApprovedAmount;
                Notify(owner, "Approved", actual, $"寄付申請が{actual:N0}円で承認されました。お礼のアイテムはゲーム内メールでお届けします。{row.Reason}");
            }
            else if (action == "reject")
            {
                var text = CleanReason(reason);
                if (row.Status == DonationRules.Rejected && row.Reason == text) return Map(row, true);
                Require(row.Status == DonationRules.Reviewing, "確認中の申請のみ否認できます。");
                row.Status = DonationRules.Rejected;
                row.Reason = text;
                await ReleaseFingerprints(id);
                Notify(owner, "Rejected", row.DeclaredAmount, $"寄付申請が否認されました。金額は受領していません。理由: {text}");
            }
            else throw new ArgumentException("不正な操作です。");
            row.DecidedAtUtc = Now;
            return Map(row, true);
        });
    }

    public async Task<IReadOnlyList<DonationNotificationResponse>> NotificationsAsync(Guid user) =>
        await management.Set<DonationNotificationEntity>().AsNoTracking()
            .Where(x => x.UserUuid == user && x.AcknowledgedAtUtc == null)
            .OrderBy(x => x.CreatedAtUtc).ThenBy(x => x.Id).Take(50)
            .Select(x => new DonationNotificationResponse(x.Id, x.Kind, x.Amount, x.Message)).ToListAsync();

    public async Task AcknowledgeAsync(Guid user, Guid id)
    {
        await management.Set<DonationNotificationEntity>().Where(x => x.Id == id && x.UserUuid == user && x.AcknowledgedAtUtc == null)
            .ExecuteUpdateAsync(update => update.SetProperty(x => x.AcknowledgedAtUtc, Now));
    }

    /// <summary>承認済み累計との差分を予約し、未配信指示を固定メールIDで再送します。</summary>
    public async Task ReconcileAsync(CancellationToken cancellationToken)
    {
        var users = await Ledgers.AsNoTracking().Where(x => x.TotalApprovedAmount > 0).Select(x => x.UserUuid).ToListAsync(cancellationToken);
        foreach (var user in users)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await Locked(user, async ledger =>
            {
                await PrepareGrants(ledger);
                return true;
            });
        }
        // 削除・リセット後の旧UUID宛て指示を飛ばし、後続の有効な指示も必ず走査する。
        var deferred = 0;
        while (true)
        {
            var pending = await Grants.AsNoTracking().Where(x => x.DeliveredAtUtc == null)
                .OrderBy(x => x.CreatedAtUtc).ThenBy(x => x.Id).Skip(deferred).Take(200).ToListAsync(cancellationToken);
            if (pending.Count == 0) break;
            foreach (var grant in pending)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (!await Deliver(grant)) { deferred++; continue; }
                await Locked(grant.UserUuid, async _ =>
                {
                    var current = await Grants.SingleAsync(x => x.Id == grant.Id);
                    if (current.DeliveredAtUtc is null)
                    {
                        current.DeliveredAtUtc = Now;
                        Notify(grant.UserUuid, "MailDelivered", grant.Amount, $"寄付へのお礼としてアストラルド(有償){grant.Amount:N0}個のメールが届きました。{grant.Message}");
                    }
                    return true;
                });
            }
        }
    }

    private async Task PrepareGrants(DonationLedgerEntity ledger)
    {
        var accounts = await game.Accounts.AsNoTracking().Where(x => x.UserId == ledger.UserUuid && !x.IsDeleted)
            .Select(x => new { x.Uuid, x.CreatedAt }).ToListAsync();
        var approvals = await Requests.AsNoTracking().Where(x => x.UserUuid == ledger.UserUuid && x.Status == DonationRules.Approved)
            .OrderBy(x => x.ApprovedThroughAmount).ToListAsync();
        foreach (var account in accounts)
        {
            var through = await Grants.Where(x => x.AccountUuid == account.Uuid).MaxAsync(x => (long?)x.ThroughAmount) ?? 0;
            // 新規アカウントは作成以前の承認分を累計メールにまとめる。
            if (through == 0)
            {
                var initial = approvals.LastOrDefault(x => x.DecidedAtUtc <= account.CreatedAt);
                if (initial is not null)
                    through = AddGrants(ledger.UserUuid, account.Uuid, through, initial.ApprovedThroughAmount!.Value,
                        $"これまでの承認済み寄付累計{initial.ApprovedThroughAmount:N0}円分をお届けします。");
            }
            // 既存アカウントには申請ごとの申告額・承認額・警告を保持して届ける。
            foreach (var approved in approvals.Where(x => x.ApprovedThroughAmount > through))
                through = AddGrants(ledger.UserUuid, account.Uuid, through, approved.ApprovedThroughAmount!.Value, ApprovalMessage(approved));
        }
    }

    private long AddGrants(Guid user, Guid account, long through, long target, string message)
    {
        while (through < target)
        {
            var amount = (int)Math.Min(DonationRules.MaximumAmount, target - through);
            through += amount;
            Grants.Add(new DonationGrantEntity
            {
                Id = Guid.NewGuid(), UserUuid = user, AccountUuid = account,
                ThroughAmount = through, Amount = amount, Message = message, CreatedAtUtc = Now,
            });
        }
        return through;
    }

    private async Task<bool> Deliver(DonationGrantEntity grant)
    {
        return await game.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            game.ChangeTracker.Clear();
            await using var transaction = await game.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            // 複数APIインスタンスの配信とアカウント削除を直列化する。
            var account = game.Database.IsSqlServer()
                ? await game.Accounts.FromSqlInterpolated($"SELECT * FROM dbo.account WITH (UPDLOCK,HOLDLOCK) WHERE uuid={grant.AccountUuid}").SingleOrDefaultAsync()
                : await game.Accounts.SingleOrDefaultAsync(x => x.Uuid == grant.AccountUuid);
            if (account is null || account.IsDeleted || account.UserId != grant.UserUuid) return false;
            var mailId = $"donation-{grant.Id:N}";
            if (!await game.PlayerMailDeliveries.AnyAsync(x => x.AccountId == grant.AccountUuid && x.MailId == mailId))
            {
                var mail = new MailResponse
                {
                    SchemaVersion = 1, Id = mailId, Icon = "PLAYER_HEAD", Title = "寄付へのお礼",
                    Body = $"{grant.Message}\n配布数量: {grant.Amount:N0}個\nご支援ありがとうございます。添付アイテムは手動でお受け取りください。",
                    PublishFrom = grant.CreatedAtUtc, PublishTo = null, ReceiveOnRead = true,
                    Rewards = [new() { ItemId = DonationRules.PaidAstraldItemId, Category = "CURRENCY", Amount = grant.Amount }],
                };
                game.PlayerMailDeliveries.Add(new PlayerMailDeliveryEntity
                {
                    PlayerMailDeliveryId = grant.Id, AccountId = grant.AccountUuid, MailId = mailId,
                    PayloadJson = JsonSerializer.Serialize(mail, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
                    Version = 1, CreatedAt = grant.CreatedAtUtc, UpdatedAt = grant.CreatedAtUtc,
                    CreatedBy = grant.UserUuid, UpdatedBy = grant.UserUuid,
                });
                await game.SaveChangesAsync();
            }
            await transaction.CommitAsync();
            return true;
        });
    }

    private async Task<T> Locked<T>(Guid user, Func<DonationLedgerEntity, Task<T>> operation)
    {
        try
        {
            return await management.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
            {
                management.ChangeTracker.Clear();
                await using var transaction = await management.Database.BeginTransactionAsync(IsolationLevel.Serializable);
                var ledger = management.Database.IsSqlServer()
                    ? await Ledgers.FromSqlInterpolated($"SELECT * FROM dbo.donation_ledger WITH (UPDLOCK,HOLDLOCK) WHERE user_uuid={user}").SingleOrDefaultAsync()
                    : await Ledgers.SingleOrDefaultAsync(x => x.UserUuid == user);
                if (ledger is null) { ledger = new() { UserUuid = user }; Ledgers.Add(ledger); }
                var result = await operation(ledger);
                ledger.Revision++;
                await management.SaveChangesAsync();
                await transaction.CommitAsync();
                return result;
            });
        }
        catch (DbUpdateException)
        {
            throw new DonationConflictException("他の処理と競合しました。履歴を再読み込みしてから操作してください。");
        }
    }

    private async Task ReleaseFingerprints(Guid id)
    {
        var rows = await management.Set<DonationEntryFingerprintEntity>().Where(x => x.RequestId == id).ToListAsync();
        management.RemoveRange(rows);
    }

    private void Notify(Guid user, string kind, int amount, string message) => management.Set<DonationNotificationEntity>().Add(new()
    { Id = Guid.NewGuid(), UserUuid = user, Kind = kind, Amount = amount, Message = message, CreatedAtUtc = Now });

    private DonationResponse Map(DonationRequestEntity row, bool detail) => new(row.Id, row.UserUuid, row.Mcid,
        row.DeclaredAmount, row.ApprovedAmount, row.Status, row.Reason, row.CreatedAtUtc, row.DecidedAtUtc,
        row.ReviewerUuid, detail ? ReadEntries(row) : [], row.DiscordUserId, row.DiscordName);
    private DonationEntry[] ReadEntries(DonationRequestEntity row) => JsonSerializer.Deserialize<DonationEntry[]>(protector.Unprotect(row.ProtectedEntries))!;
    private static string ApprovalMessage(DonationRequestEntity row) => $"申告額: {row.DeclaredAmount:N0}円 / 承認額: {row.ApprovedAmount:N0}円。{row.Reason}";
    private static void Require(bool value, string message) { if (!value) throw new DonationConflictException(message); }
    private static string CleanReason(string? text)
    {
        if (string.IsNullOrWhiteSpace(text) || text.Trim().Length > 1000 || text.Any(c => char.IsControl(c) && c is not '\n' and not '\r'))
            throw new ArgumentException("否認理由を1～1,000文字で入力してください。");
        return text.Trim();
    }

    public static DonationEntry[] Normalize(DonationCreateRequest request)
    {
        if (request.OperationId == Guid.Empty || request.TermsVersion != DonationRules.TermsVersion)
            throw new ArgumentException("最新の寄付規約を確認して同意してください。");
        if (request.DeclaredAmount is < DonationRules.MinimumAmount or > DonationRules.MaximumAmount)
            throw new ArgumentException("申告額は500～1,000,000円で入力してください。");
        if (request.Entries is null || request.Entries.Count is < 1 or > DonationRules.MaximumEntries)
            throw new ArgumentException("支払い情報は1～10件で入力してください。");
        var entries = request.Entries.Select(entry =>
        {
            if (entry is null || entry.DeclaredAmount is < 1 or > DonationRules.MaximumAmount) throw new ArgumentException("明細金額が不正です。");
            var value = entry.Value?.Trim() ?? "";
            if (entry.Method == "amazon")
            {
                value = value.ToUpperInvariant();
                if (!Regex.IsMatch(value, "^[A-Z0-9-]{10,32}$", RegexOptions.CultureInvariant)) throw new ArgumentException("Amazonギフトカード番号を確認してください。");
            }
            else if (entry.Method == "paypay")
            {
                if (value.Length > 512 || !Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != "https"
                    || uri.Host != "pay.paypay.ne.jp" || !uri.IsDefaultPort || uri.UserInfo.Length > 0
                    || uri.Fragment.Length > 0 || uri.Query.Length > 0 || !Regex.IsMatch(uri.AbsolutePath, "^/[A-Za-z0-9_-]+/?$"))
                    throw new ArgumentException("PayPayの受け取りリンク（https://pay.paypay.ne.jp/…）を入力してください。");
                value = $"https://pay.paypay.ne.jp/{uri.AbsolutePath.Trim('/')}";
            }
            else throw new ArgumentException("支払い種別が不正です。");
            return new DonationEntry(entry.Method, value, entry.DeclaredAmount);
        }).ToArray();
        if (entries.Sum(x => (long)x.DeclaredAmount) != request.DeclaredAmount) throw new ArgumentException("明細の合計と申告額が一致しません。");
        if (entries.Select(Fingerprint).Distinct().Count() != entries.Length) throw new ArgumentException("同じ支払い情報を重複して入力できません。");
        return entries;
    }

    private static string Fingerprint(DonationEntry entry) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
        entry.Method + ":" + (entry.Method == "amazon" ? entry.Value.Replace("-", "") : entry.Value))));
}
