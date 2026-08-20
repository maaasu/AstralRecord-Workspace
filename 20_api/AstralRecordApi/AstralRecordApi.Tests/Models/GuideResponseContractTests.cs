using System.Text.Json;
using AstralRecordApi.Models;
using Xunit;

namespace AstralRecordApi.Tests.Models;

public class GuideResponseContractTests
{
    [Fact]
    public void GuideStep_DeserializesDetailsAndNpcAction()
    {
        const string json = """
            {
              "schemaVersion": 3,
              "id": "beginner_onboarding",
              "category": "beginner",
              "displayOrder": 10,
              "title": "冒険を始めよう",
              "steps": [
                {
                  "id": "claim_login_bonus",
                  "text": "ログインボーナスを受け取る",
                  "details": ["案内人に話しかける", "今日の報酬をクリックする"],
                  "condition": { "type": "LOGIN_BONUS_CLAIMED" },
                  "action": {
                    "type": "NAVIGATE_NPC",
                    "description": "クリックで案内する",
                    "npcId": "login_bonus_clerk"
                  }
                }
              ]
            }
            """;

        var response = JsonSerializer.Deserialize<GuideResponse>(
            json,
            new JsonSerializerOptions(JsonSerializerDefaults.Web));

        Assert.NotNull(response);
        var step = Assert.Single(response.Steps);
        Assert.Equal(3, response.SchemaVersion);
        Assert.Equal(new[] { "案内人に話しかける", "今日の報酬をクリックする" }, step.Details);
        Assert.NotNull(step.Action);
        Assert.Equal("NAVIGATE_NPC", step.Action.Type);
        Assert.Equal("login_bonus_clerk", step.Action.NpcId);
    }

    [Fact]
    public void GuideStep_AllowsMenuActionToBeOmitted()
    {
        const string json = """
            {
              "schemaVersion": 3,
              "id": "mail_guide",
              "category": "beginner",
              "title": "メール",
              "steps": [
                {
                  "id": "receive",
                  "text": "メールを受け取る",
                  "condition": { "type": "MAIL_RECEIVED", "targetId": "welcome_mail" }
                }
              ]
            }
            """;

        var response = JsonSerializer.Deserialize<GuideResponse>(
            json,
            new JsonSerializerOptions(JsonSerializerDefaults.Web));

        Assert.NotNull(response);
        var step = Assert.Single(response.Steps);
        Assert.Empty(step.Details);
        Assert.Null(step.Action);
    }
}
