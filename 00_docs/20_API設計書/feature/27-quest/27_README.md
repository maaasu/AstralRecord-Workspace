# 27-quest API

アカウント単位のクエスト受領・進行・完了・クールダウン状態を API / SQL Server で永続化します。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountQuestController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountQuestStateModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IAccountQuestStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/AccountQuestStateRepository.cs`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/quest/repository/QuestPlayerStateRepository.java`

## DB

- `dbo.account_quest_state`
- `dbo.account_quest_active`
- `dbo.account_quest_objective_progress`
- `dbo.account_quest_completion`
- `dbo.account_quest_cooldown`

1. [[27_0.00-概要]]
2. [[27_1.00-モデル定義]]
3. [[27_3.00-索引]]
