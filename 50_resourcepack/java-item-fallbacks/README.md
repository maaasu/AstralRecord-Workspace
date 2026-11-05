# バニラのアイテム表示

`scripts/item-models.ps1` は `custom_model_data` が一致しないアイテムに、ここにある元の `model` 定義を使う。カスタムモデルへ切り替えるために、時計の時刻セレクター、ヘッドのspecial renderer、ブロック用モデル参照等を簡略化しない。

アクセサリーの12基本素材の定義は Mojang の Minecraft Java Edition 1.21.11 client JAR の `assets/minecraft/items/` から取得した。`player_head` 以外の11素材は26.3の定義とも一致する。`transformed_item_models/player_head.json` は26.1 client JARの定義（26.2・26.3も同じ）で、pack format 83以降のヘッド座標変換を保持する。

player_headの変換クォータニオンの負のゼロは正のゼロへ正規化している。回転は同じで、PowerShell 5.1と7のJSON数値のシリアライズ差を避ける。

これらはアイテム定義JSONのみ。バニラのモデルとテクスチャ本体はクライアントのリソースを参照し、このリポジトリへ複製しない。
