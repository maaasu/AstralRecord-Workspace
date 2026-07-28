# Status Catalog Code Generator

`40_filebase/75.shared.status/v1.status_types.yml`を検証し、Plugin/API/Skill Tree Editor向けの型と表示メタデータを生成する.NETツールです。

通常はリポジトリルートから`60_tool`配下のラッパースクリプトを実行します。

```powershell
.\60_tool\generate-status-types.ps1
```

エクスプローラーやコマンドプロンプトから実行する場合は`E:\AstralRecord-Workspace\60_tool\07-generate-status-types.bat`も利用できます。

生成物がYAMLと一致するかだけを確認する場合:

```powershell
.\60_tool\generate-status-types.ps1 -Check
```

BATでは`07-generate-status-types.bat -Check`が同じ検査です。

検査ではschemaVersion、ID形式、カテゴリ参照、ID重複、小数桁範囲も確認します。生成物は管理対象ソースとしてGitへcommitし、直接編集しません。
