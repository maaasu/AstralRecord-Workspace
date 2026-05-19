# API Prompt

## 読むタイミング

- API を追加するとき
- 既存エンドポイントの契約を変えるとき
- API ドキュメントを更新するとき

## 必須ルール

- Controller、DTO、Repository の責務分離を守る。
- API 契約変更は利用側への影響を前提に扱う。
- README と詳細ドキュメントの同期を忘れない。

## 更新チェックリスト

1. Controller、DTO、Repository の責務が崩れていないか確認する。
2. `README.md` の API 一覧を更新する。
3. `docs/api/` がある場合は詳細資料も更新する。
4. サンプルリクエストや説明例がある場合はそれも見直す。
5. Plugin / Web / Database への影響がないか確認する。

## 非推奨

- Controller に永続化ロジックを寄せること
- DTO と Entity を兼用すること
- 契約変更を README へ反映しないこと
