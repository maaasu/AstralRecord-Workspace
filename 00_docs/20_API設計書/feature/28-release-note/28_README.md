# 28_README

このディレクトリは API `release-note` 機能の設計書です。プロジェクト内の Markdown をWeb公開メタデータへ登録し、Discord通知をOutboxから非同期送信します。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/ReleaseNoteController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/ReleaseNoteModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IReleaseNoteRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/ReleaseNoteRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/ReleaseNotificationHostedService.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/DiscordReleaseNotificationSender.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/ReleaseNoteEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/ReleaseNotificationOutboxEntity.cs`

## 対応プラグイン feature

- Plugin 専用の呼び出し元は持たない。Web の起動時同期を受け付けるAPI専用機能である。

## ドキュメント一覧

1. [[28_0.00-概要]]
2. [[28_1.00-モデル定義]]
3. [[28_2.00-ユースケース]]
4. [[28_3.00-エンドポイント仕様]]
5. [[28_4.00-統合フロー]]
6. [[28_5.00-例外・ログ・運用]]

## 依存 feature

- `Web`: Web が公開済み Markdown を読み取り、APIキー付きで登録する。
- `AstralRecord`: リリースノートとDiscord通知Outboxを保存する。
- Discord API: APIワーカーが token.txt のBotトークンで指定チャンネルへ送信する。

## 更新ルール

- DTO・API契約変更: [[28_1.00-モデル定義]] / [[28_3.00-エンドポイント仕様]]
- 通知処理変更: [[28_4.00-統合フロー]] / [[28_5.00-例外・ログ・運用]]
- DBスキーマ変更: `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.release_*.md`
