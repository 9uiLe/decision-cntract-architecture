#!/bin/sh
# 予約状況の操作（照会・再試行）の実クリック試験。端末が要るので、標準の check.sh には含めない。
#   sh Tools/ui-click.sh positive --device <UDID>   製品の画面で、更新後のクリックが新しい券・実行先へ届くこと
#   sh Tools/ui-click.sh negative --device <UDID>   製品の複製に退行を入れ、同じクリック試験が失敗すること
# 端末は作らない。起動済みの専用 Simulator の UDID を渡す。
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$root/Tools/ui-action/run.py" "$@"
