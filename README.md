# YW Editor (Shizuku)

`main.bin` を Shizuku 経由で読み込み、`game0.yw`〜`game3.yw` の妖怪データ(レベル / IVA / IVB1 / IVB2 / CB)を編集して保存する Android アプリです。

## 実装内容

- `crypt.py` / `check.py` の暗号・復号ロジックを Kotlin に移植
- `main.bin` から `game0.yw` / `game1.yw` / `game2.yw` / `game3.yw` を選択して妖怪リストを抽出
- 個体値などを Compose UI で編集
- 保存時に `main.bin.bak` を作成してから上書き

## 対象ファイル

- `/data/user/0/com.Level5.YokaiWatch/files/main.bin`

## 使い方

1. 端末で Shizuku を起動
2. アプリを起動し、`Shizuku許可` を押す
3. `game0`〜`game3` から編集対象を選ぶ
4. `main.bin読込` で一覧を表示
5. 妖怪を選択して値を編集
6. `保存` で選択中セーブに反映

## 注意

- Shizuku が shell 権限で動作している場合、端末設定によっては `/data/user/0/...` へアクセスできないことがあります。
- 書き込み前に自動バックアップ(`.bak`)を作成しますが、自己責任で利用してください。

