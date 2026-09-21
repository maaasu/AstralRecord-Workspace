using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>参加権限の読取ロックをSQL文内に限定し、別accountの更新との循環待ちを防ぎます。</summary>
internal static class SkillTreeSessionReads
{
    /// <summary>
    /// SQL Serverでは外側のSerializable transactionへsessionの共有範囲ロックを持ち越しません。
    /// sessionを変更する処理と保存権限の検証は、先に対象accountのUPDLOCK/HOLDLOCKを取得してください。
    /// 同accountの変更はそのロックで排他し、runtime行のboot fenceと外側のtransaction分離レベルは維持します。
    /// </summary>
    internal static IQueryable<SkillTreeAccountSessionEntity> Query(AstralRecordDbContext context) =>
        context.Database.IsSqlServer()
            ? context.SkillTreeAccountSessions.FromSqlRaw(
                "SELECT * FROM [dbo].[skilltree_account_session] WITH (READCOMMITTEDLOCK)")
            : context.SkillTreeAccountSessions;
}
