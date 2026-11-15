# API/Web配布

`../10-release-management-deploy.bat` はLegacy運用のAPIとWebを更新します。設定と処理は [01](../01-deploy-debug/README.md) を使用し、ビルド成果物は設定の `api.buildOutputPath` と `web.buildOutputPath` に出力します。事前検査のみ行う場合は `-PreflightOnly` を指定します。Managed運用で利用できるのは事前検査です。
