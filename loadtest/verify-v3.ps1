# v3 压测判定：核心是「等消费者追平，再数成功单」。
#
# v0/v1/v2 压测一结束就能直接查库，因为那些版本是同步的。
# v3 不行：压测结束时队列里可能还堆着几百条没消费完的消息，
# 此刻查 status=1 的订单数会偏小，误判成「少卖」。
#
# 所以这个脚本做三件事：
#   1. 轮询等队列清空（或超时）
#   2. 查最终一致结果：订单状态分布、库存守恒、Redis 对账
#   3. 按五条判据逐项打 ✅ / ❌
#
# 用法（在项目根目录）：
#   .\loadtest\verify-v3.ps1
#   .\loadtest\verify-v3.ps1 -CourseId 88 -TimeoutSeconds 120

param(
    [int]$CourseId = 88,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$redisCli = 'D:\tools\Redis\memurai-cli.exe'

$root = Split-Path -Parent $PSScriptRoot
$cfgPath = Join-Path $root 'src\main\resources\application-local.yaml'
$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8)
$pw = [regex]::Match($cfg, '(?m)^\s*password:\s*(\S+)\s*$').Groups[1].Value
$m2 = [regex]::Match($cfg, '(?ms)rabbitmq:.*?password:\s*(\S+)\s*$')
# YAML 值可能带引号，正则会把引号吃进来，必须剥掉否则 Basic 认证头错误 → 管理台 401
# 不写死默认密码：脚本要进版本库，密码只能来自被 .gitignore 忽略的本地配置
$rmqPass = if ($m2.Success) { $m2.Groups[1].Value.Trim('"', "'") } else { $null }
if (-not $rmqPass) {
    throw "没能从 application-local.yaml 的 rabbitmq 段读出密码；请确认本地配置里有 spring.rabbitmq.password"
}
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("seckill:$rmqPass"))

$cnf = Join-Path $env:TEMP ("seckill-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")
[System.IO.File]::WriteAllText($cnf, "[client]`r`nuser=root`r`npassword=$pw`r`n", (New-Object System.Text.UTF8Encoding($false)))

function Get-QueueMessages($name) {
    try {
        $url = "http://localhost:15672/api/queues/%2F/$name"
        $r = [System.Net.WebRequest]::Create($url)
        $r.Headers.Add('Authorization', "Basic $auth")
        $resp = $r.GetResponse()
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
        $json = $sr.ReadToEnd()
        $sr.Close(); $resp.Close()
        return ($json | ConvertFrom-Json).messages
    } catch {
        return -1
    }
}

try {
    # ===== 1. 等消费者追平 =====
    Write-Host "--- 等待队列排空（最多 $TimeoutSeconds 秒）---"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $settled = $false
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $q = Get-QueueMessages 'seckill.order.queue'
        $d = Get-QueueMessages 'seckill.order.dlq'
        if ($q -eq 0 -and $d -ge 0) {
            $settled = $true
            Write-Host ("  队列已空，用时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + " 秒（死信 $d 条）")
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $settled) {
        Write-Warning "等待超时，队列可能还有积压，下面的数字偏小"
    }

    # ===== 2. 查最终状态 =====
    Write-Host ''
    Write-Host '--- 订单状态分布 ---'
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e `
        "SELECT status, COUNT(*) AS cnt FROM seckill.seckill_order WHERE course_id = $CourseId GROUP BY status ORDER BY status;"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    Write-Host '--- 库存与守恒 ---'
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e `
        "SELECT capacity, stock, sold, stock + sold AS sum_stock_sold,
                CASE WHEN stock + sold = capacity THEN 'OK' ELSE 'BROKEN' END AS conservation
         FROM seckill.course_stock WHERE course_id = $CourseId;"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    Write-Host '--- 幂等日志 ---'
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e `
        "SELECT status, COUNT(*) AS cnt FROM seckill.mq_consume_log GROUP BY status ORDER BY status;"

    # ===== 3. 取指标做判定 =====
    $row = (& $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT capacity, stock, sold, stock + sold FROM seckill.course_stock WHERE course_id = $CourseId;" 2>&1) -split "`t"
    $capacity = [int]$row[0]
    $stock = [int]$row[1]
    $sold = [int]$row[2]
    $sum = [int]$row[3]

    $successOrders = [int]((& $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT COUNT(*) FROM seckill.seckill_order WHERE course_id = $CourseId AND status = 1;" 2>&1) | Select-Object -First 1)
    $pendingOrders = [int]((& $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT COUNT(*) FROM seckill.seckill_order WHERE course_id = $CourseId AND status = 0;" 2>&1) | Select-Object -First 1)
    $dupStudents = [int]((& $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT COUNT(*) FROM (SELECT student_id FROM seckill.seckill_order WHERE course_id = $CourseId AND status = 1 GROUP BY student_id HAVING COUNT(*) > 1) t;" 2>&1) | Select-Object -First 1)

    $redisStock = (& $redisCli get "seckill:stock:$CourseId" 2>&1 | Out-String).Trim()

    Write-Host ''
    Write-Host '--- 判定 ---'
    Write-Host ("  capacity        = $capacity")
    Write-Host ("  success_orders  = $successOrders")
    Write-Host ("  pending_orders  = $pendingOrders")
    Write-Host ("  stock / sold    = $stock / $sold   (sum=$sum)")
    Write-Host ("  redis_stock     = '$redisStock'")
    Write-Host ''

    $pass = $true

    # 前置判断：一条订单都没有 = 还没压测，不是「少卖」。
    # 没有这个判断，刚复位完直接跑本脚本会误报「少卖 100 单」，让人以为消息全丢了。
    $totalOrders = ((& $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -N -e `
        "SELECT COUNT(*) FROM seckill.seckill_order WHERE course_id = $CourseId;" 2>&1) | Select-Object -First 1)
    $totalOrders = [int]$totalOrders

    if ($totalOrders -eq 0) {
        Write-Host '  没有订单记录 —— 说明还没跑过压测，或刚复位过。'
        Write-Host '  下面只验库存一致性，跳过「少卖」判定。'
        Write-Host ''
        if ($sum -ne $capacity) {
            Write-Host ("  库存守恒       : [X] stock+sold=$sum != capacity=$capacity")
            Write-Host ''
            Write-Host '结论：FAIL'
            exit 1
        }
        Write-Host ("  库存守恒       : [OK] stock+sold = capacity = $capacity（干净状态）")
        Write-Host ''
        Write-Host '结论：N/A（未压测）'
        exit 0
    }

    # 判据 1：成功单数不能超过名额（超卖）
    if ($successOrders -gt $capacity) {
        Write-Host ("  1. 超卖检查       : [X] 超卖！成功 $successOrders > 名额 $capacity，超卖 " + ($successOrders - $capacity) + " 单")
        $pass = $false
    } else {
        Write-Host ("  1. 超卖检查       : [OK] 成功 $successOrders <= 名额 $capacity")
    }

    # 判据 2：不能少卖（v3 新增，异步特有）
    if ($successOrders -lt $capacity) {
        $lost = $capacity - $successOrders
        Write-Host ("  2. 少卖检查       : [X] 少卖 $lost 单（可能消息丢失或消费失败，查死信队列）")
        $pass = $false
    } else {
        Write-Host ("  2. 少卖检查       : [OK] 名额用满")
    }

    # 判据 3：库存守恒
    if ($sum -ne $capacity) {
        Write-Host ("  3. 库存守恒       : [X] stock+sold=$sum != capacity=$capacity（差 " + ($sum - $capacity) + "）")
        $pass = $false
    } else {
        Write-Host ("  3. 库存守恒       : [OK] stock+sold = capacity = $capacity")
    }

    # 判据 4：不能残留 PENDING
    if ($pendingOrders -gt 0) {
        Write-Host ("  4. 残留待处理     : [X] $pendingOrders 条仍是 status=0（消息可能丢了）")
        $pass = $false
    } else {
        Write-Host ("  4. 残留待处理     : [OK] 无孤儿订单")
    }

    # 判据 5：同一学生不能有多条成功
    if ($dupStudents -gt 0) {
        Write-Host ("  5. 唯一性         : [X] $dupStudents 个学生有多条成功订单")
        $pass = $false
    } else {
        Write-Host ("  5. 唯一性         : [OK] 无重复成功")
    }

    # 附加：Redis 对账（不算失败，只提示）
    if ($redisStock -eq '') {
        Write-Host '  附加 Redis 对账   : [-] key 不存在（未预热，不算错）'
    } elseif ([int]$redisStock -ne $stock) {
        Write-Host ("  附加 Redis 对账   : [!] Redis=$redisStock, MySQL stock=$stock，差值 " + ([int]$redisStock - $stock))
    } else {
        Write-Host ("  附加 Redis 对账   : [OK] Redis = MySQL = $stock")
    }

    Write-Host ''
    if ($pass) {
        Write-Host '结论：PASS'
        exit 0
    } else {
        Write-Host '结论：FAIL'
        exit 1
    }
}
finally {
    Remove-Item $cnf -ErrorAction SilentlyContinue
}
