using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB の <c>master_type = guide</c> からガイドマスターを取得します。</summary>
public class GuideRepository(MasterDataDbContext dbContext) : IGuideRepository
{
    private const string MasterTypeGuide = "guide";
    private const string ChapterOneId = "beginner_onboarding";

    private static readonly string[] ChapterOneSteps =
    [
        "open_menu", "receive_welcome_mail", "open_initial_bundle",
        "buy_starter_weapon", "equip_starter_weapon", "defeat_grassboar",
    ];

    public IReadOnlyList<GuideResponse> GetAll()
    {
        var chapter = GetById(ChapterOneId);
        return chapter is null ? [] : [chapter];
    }

    public GuideResponse? GetById(string guideId)
    {
        if (guideId != ChapterOneId) return null;

        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterTypeGuide
                && entry.MasterId == guideId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        var chapter = payload is null ? null : MasterDataPayloadJson.Deserialize<GuideResponse>(payload);
        return chapter is { SchemaVersion: 3, Id: ChapterOneId }
            && chapter.Steps.Select(step => step.Id).SequenceEqual(ChapterOneSteps) ? chapter : null;
    }
}
