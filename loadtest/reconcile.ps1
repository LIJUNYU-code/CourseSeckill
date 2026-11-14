# 压测后对账：Redis 里的库存 与 MySQL 里的库存 是否一致。
#
# 用法（在项目根目录）：
#   .\loadtest\reconcile.ps1                    # 只查课程 88
#   .\loadtest\reconcile.ps1 -CourseId 88
#   .\loadtest\reconcile.ps1 -All               # 查全部 100 门课
#
# 为什么需要它（verify.ps1 不够用）：
#   verify.ps1 只查 MySQL，能证明"没超卖、账平"。
#   但 v2 的库存被 Redis 接管了一部分，MySQL 账平不代表 Redis 也平。
#   Redis 偏高（比 MySQL 多）→ 后面的人能抢到不存在的名额 → 超卖；
#   Redis 偏低（比 MySQL 少）→ 只是浪费名额，安全但要知道。
#   所以必须两边都读出来比一比。
#
# 判据：
#   consistent = true   → 两边相等，正常
#   consistent = false 且 redis > db → 🔴 危险：Redis 偏高，可能超卖
#   consistent = false 且 redis < db → 🟡 安全：Redis 偏低，浪费名额（跑 preheat 修）
#   redis = null        → 该课程未预热，不算错
#
# 退出码：0 = 全部一致（或未预热）；1 = 存在不一致。可直接给 CI 用。
#
# 密码传给 mysql 的坑见 reset.ps1 顶部注释（临时 option file，不用 -p 也不用 MYSQL_PWD）。

param(
    [int]$CourseId = 88,
    [switch]$All
)

$ErrorActionPreference = 'Stop'

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$redisCli = 'D:\tools\Redis\memurai-cli.exe'

if (-not (Test-Path $mysql)) {
    throw "找不到 mysql.exe：$mysql（请改成你的安装路径）"
}
if (-not (Test-Path $redisCli)) {
    throw "找不到 memurai-cli.exe：$redisCli（请改成你的安装路径）"
}

$root = Split-Path -Parent $PSScriptRoot
$cfgPath = Join-Path $root 'src\main\resources\application-local.yaml'
$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8)
$pw = [regex]::Match($cfg, '(?m)^\s*password:\s*(\S+)\s*$').Groups[1].Value
if (-not $pw) {
    throw "没能从 $cfgPath 里读出 mysql 密码"
}

# ---- 先确认 Redis 活着。它不会开机自启，挂了的话下面全是假数据 ----
$pong = & $redisCli ping 2>&1
if ("$pong" -notmatch 'PONG') {
    throw "Redis 连不上（$pong）。先启动 Memurai：Start-Process D:\tools\Redis\memurai.exe -ArgumentList `"D:\tools\Redis\memurai.conf`" -WindowStyle Hidden"
}

$cnf = Join-Path $env:TEMP ("seckill-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")
[System.IO.File]::WriteAllText($cnf, "[client]`r`nuser=root`r`npassword=$pw`r`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    # ---- 从 MySQL 一次读出所有需要的行（-N 去掉表头，制表符分隔）----
    $where = if ($All) { '1 = 1' } else { "course_id = $CourseId" }
    $rows = & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT course_id, capacity, stock, sold FROM seckill.course_stock WHERE $where ORDER BY course_id;"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    if (-not $rows) {
        throw "没有查到任何库存行（-All 时说明表是空的）"
    }

    $results = @()
    foreach ($line in $rows) {
        if (-not $line.Trim()) { continue }
        $f = $line -split "`t"
        $cid = [int]$f[0]
        $capacity = [int]$f[1]
        $dbStock = [int]$f[2]
        $dbSold = [int]$f[3]

        $raw = & $redisCli get "seckill:stock:$cid" 2>&1
        $redisStock = $null
        if ("$raw" -ne '' -and "$raw" -notmatch 'nil|^\s*$') {
            $parsed = 0
            if ([int]::TryParse("$raw".Trim(), [ref]$parsed)) { $redisStock = $parsed }
        }

        # 三种判定：未预热 / 一致 / 不一致（再分偏高偏低）
        if ($null -eq $redisStock) {
            $state = '未预热'
            $consistent = $null          # 未预热不算错，不计入失败
        }
        elseif ($redisStock -eq $dbStock) {
            $state = '一致'
            $consistent = $true
        }
        elseif ($redisStock -gt $dbStock) {
            $state = "🔴 偏高 $($redisStock - $dbStock)"
            $consistent = $false
        }
        else {
            $state = "🟡 偏低 $($dbStock - $redisStock)"
            $consistent = $false
        }

        $results += [pscustomobject]@{
            course_id  = $cid
            capacity   = $capacity
            db_stock   = $dbStock
            db_sold    = $dbSold
            redis      = if ($null -eq $redisStock) { '(空)' } else { $redisStock }
            state      = $state
            consistent = $consistent
        }
    }

    Write-Host ''
    Write-Host '=== Redis 与 MySQL 库存对账 ===' -ForegroundColor Cyan
    $results | Format-Table -AutoSize

    $bad = @($results | Where-Object { $_.consistent -eq $false })
    $warm = @($results | Where-Object { $null -ne $_.consistent })

    Write-Host ("检查 {0} 门课；已预热 {1} 门；不一致 {2} 门。" -f $results.Count, $warm.Count, $bad.Count)

    if ($bad.Count -gt 0) {
        Write-Host ''
        Write-Host '不一致的课程：' -ForegroundColor Red
        $bad | Format-Table course_id, db_stock, redis, state -AutoSize

        $high = @($bad | Where-Object { $_.state -like '*偏高*' })
        if ($high.Count -gt 0) {
            Write-Host '🔴 存在 Redis 偏高：说明"扣减成功但 MySQL 回滚后没还回来"，' -ForegroundColor Red
            Write-Host '   后续请求可能抢到不存在的名额。先查日志里的"Redis 库存归还失败"。' -ForegroundColor Red
        }
        else {
            Write-Host '🟡 全部是 Redis 偏低：不影响正确性，跑一次预热即可修复。' -ForegroundColor Yellow
            Write-Host '   POST /api/admin/cache/stock   （或 -All 时逐门调用）' -ForegroundColor Yellow
        }

        exit 1
    }

    Write-Host '✅ 对账通过' -ForegroundColor Green
    exit 0
}
finally {
    Remove-Item $cnf -ErrorAction SilentlyContinue
}
