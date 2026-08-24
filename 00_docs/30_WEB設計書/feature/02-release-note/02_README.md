# 02_README

Web `release-note` feature は、プロジェクト内 Markdown を公開済みリリースノートとして表示し、Web起動時にAPIへ登録します。公開済みノートの通知はAPIのOutboxがDiscordへ送信します。

## 対象実装パス

- `30_web/AstralRecordWeb/AstralRecordWeb/Pages/Releases/Index.cshtml`
- `30_web/AstralRecordWeb/AstralRecordWeb/Pages/Releases/Details.cshtml`
- `30_web/AstralRecordWeb/AstralRecordWeb/Services/ReleaseNoteCatalog.cs`
- `30_web/AstralRecordWeb/AstralRecordWeb/Services/ReleaseNoteApiClient.cs`
- `30_web/AstralRecordWeb/AstralRecordWeb/Services/ReleaseNotePublicationHostedService.cs`
- `00_docs/70_リリースノート/`

## ドキュメント一覧

1. [[02_0.00-概要]]
2. [[02_1.00-画面モデル]]
3. [[02_3.00-画面索引]]
4. [[02_4.00-統合フロー]]
5. [[02_5.00-例外・ログ・運用]]

## 更新ルール

- Markdown front matter の変更は `00_docs/70_リリースノート/README.md` と同期する。
- URL・表示項目の変更は [[02_1.00-画面モデル]] / [[02_3.00-画面索引]] を更新する。
- API契約変更は `00_docs/20_API設計書/feature/28-release-note` も更新する。
