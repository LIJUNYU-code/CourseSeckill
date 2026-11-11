# 压测后判定：三句话回答「到底有没有超卖」。
#
# 用法（在项目根目录）：
#   .\loadtest\verify.ps1
#   .\loadtest\verify.ps1 -CourseId 88
#
# 判据（任务书 §8.3）：
#   ① success_orders > capacity  → 超卖，成功单数超过了名额
#   ② stock + sold != capacity   → 库存账不平，说明有更新丢失
#   ③ stock < 0                  → 本表有 CHECK 约束，正常不会出现
#   ④ 同一学生 status=1 的记录 > 1 → 唯一索引失效
#
# 密码用临时 option file 传给 mysql，理由见 reset.ps1 顶部注释。

param(
    [int]$CourseId = 88
)

$ErrorActionPreference = 'Stop'

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path $mysql)) {
    throw "找不到 mysql.exe：$mysql（请改成你的安装路径）"
}

$root = Split-Path -Parent $PSScriptRoot
$cfgPath = Join-Path $root 'src\main\resources\application-local.yaml'
$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8)
$pw = [regex]::Match($cfg, '(?m)^\s*password:\s*(\S+)\s*$').Groups[1].Value

$cnf = Join-Path $env:TEMP ("seckill-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")
[System.IO.File]::WriteAllText($cnf, "[client]`r`nuser=root`r`npassword=$pw`r`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    $sql = @"
SELECT '=== 1. 库存行 ===' AS section;
SELECT capacity, stock, sold, stock + sold AS sum_stock_sold,
       capacity - (stock + sold) AS conservation_gap
FROM seckill.course_stock WHERE course_id = $CourseId;

SELECT '=== 2. 成功订单数 vs 名额 ===' AS section;
SELECT COUNT(*) AS success_orders,
       (SELECT capacity FROM seckill.course_stock WHERE course_id = $CourseId) AS capacity,
       COUNT(*) - (SELECT capacity FROM seckill.course_stock WHERE course_id = $CourseId) AS oversold_by
FROM seckill.seckill_order
WHERE course_id = $CourseId AND status = 1;

SELECT '=== 3. 订单状态分布 ===' AS section;
SELECT status, COUNT(*) AS cnt FROM seckill.seckill_order
WHERE course_id = $CourseId GROUP BY status ORDER BY status;

SELECT '=== 4. 同一学生多条成功单（应为空）===' AS section;
SELECT student_id, COUNT(*) AS cnt FROM seckill.seckill_order
WHERE course_id = $CourseId AND status = 1
GROUP BY student_id HAVING COUNT(*) > 1;
"@
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e $sql
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }
}
finally {
    Remove-Item $cnf -ErrorAction SilentlyContinue
}
