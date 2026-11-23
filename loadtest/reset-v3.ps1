# 压测前复位（v3 版）：比 reset.ps1 多清两样东西。
#
# 相比 reset.ps1 多做了：
#   1. 清 Redis 库存 key（v3 的库存门禁在 Redis 里，不清会让下一轮开始就不是满额）
#   2. purge 两个 RabbitMQ 队列（业务队列 + 死信队列，上一轮的残留消息会干扰计数）
#   3. 清 mq_consume_log（幂等闸的记录，不清会让重发的 msgId 被当成"已处理"）
#
# 队列用 purge（清空消息）而不是 delete（删队列），原因：
# 队列参数一旦被声明就不能改，删了重建万一参数不一致就要排错；purge 没有这个风险。
#
# 用法（在项目根目录）：
#   .\loadtest\reset-v3.ps1
#   .\loadtest\reset-v3.ps1 -CourseId 88

param(
    [int]$CourseId = 88
)

$ErrorActionPreference = 'Stop'

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path $mysql)) {
    throw "找不到 mysql.exe：$mysql（请改成你的安装路径）"
}

$redisCli = 'D:\tools\Redis\memurai-cli.exe'
if (-not (Test-Path $redisCli)) {
    throw "找不到 memurai-cli.exe：$redisCli（请改成你的安装路径）"
}

$root = Split-Path -Parent $PSScriptRoot
$cfgPath = Join-Path $root 'src\main\resources\application-local.yaml'
if (-not (Test-Path $cfgPath)) {
    throw "找不到 $cfgPath"
}

$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8)
$pw = [regex]::Match($cfg, '(?m)^\s*password:\s*(\S+)\s*$').Groups[1].Value
if (-not $pw) {
    throw "没能从 $cfgPath 里读出 mysql 密码"
}

# RabbitMQ 管理台凭据：用户名从 application.yaml 的默认值里读，密码从本地配置读。
# 两者都不写死在这里 —— 脚本要能进版本库，密码不能跟着进去。
$appCfgPath = Join-Path $root 'src\main\resources\application.yaml'
$rmqUser = 'seckill'
if (Test-Path $appCfgPath) {
    $appCfg = [System.IO.File]::ReadAllText($appCfgPath, [System.Text.Encoding]::UTF8)
    $m = [regex]::Match($appCfg, '(?m)^\s*username:\s*\$\{RABBITMQ_USER:([^}]+)\}')
    if ($m.Success) { $rmqUser = $m.Groups[1].Value }
}

$rmqPass = $null
if (Test-Path $cfgPath) {
    $m2 = [regex]::Match($cfg, '(?ms)rabbitmq:.*?password:\s*(\S+)\s*$')
    if ($m2.Success) {
        # YAML 里的值可能带引号（password: "xxx"），正则会把引号也吃进来，
        # 必须剥掉，否则拼出来的 Basic 认证头是错的 → 管理台返回 401。
        $rmqPass = $m2.Groups[1].Value.Trim('"', "'")
    }
}
if (-not $rmqPass) {
    throw "没能从 application-local.yaml 的 rabbitmq 段读出密码；请确认本地配置里有 spring.rabbitmq.password"
}

$cnf = Join-Path $env:TEMP ("seckill-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")
[System.IO.File]::WriteAllText($cnf, "[client]`r`nuser=root`r`npassword=$pw`r`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    # --- 1. MySQL ---
    $sql = @"
TRUNCATE TABLE seckill.seckill_order;
TRUNCATE TABLE seckill.mq_consume_log;
UPDATE seckill.course_stock SET stock = capacity, sold = 0, version = 0 WHERE course_id = $CourseId;
"@
    $sqlFile = Join-Path $env:TEMP ("seckill-reset-" + [guid]::NewGuid().ToString('N') + ".sql")
    [System.IO.File]::WriteAllText($sqlFile, $sql, (New-Object System.Text.UTF8Encoding($false)))

    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -e "source $($sqlFile -replace '\\','/')"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }
    Remove-Item $sqlFile -ErrorAction SilentlyContinue

    # --- 2. Redis ---
    $key = "seckill:stock:$CourseId"
    $delOut = & $redisCli del $key 2>&1
    Write-Host "Redis DEL $key -> $delOut"

    # --- 3. RabbitMQ 队列 ---
    $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${rmqUser}:${rmqPass}"))
    foreach ($qn in @('seckill.order.queue', 'seckill.order.dlq')) {
        try {
            $url = "http://localhost:15672/api/queues/%2F/$qn/contents"
            $rq = [System.Net.WebRequest]::Create($url)
            $rq.Method = 'DELETE'
            $rq.Headers.Add('Authorization', "Basic $auth")
            $resp = $rq.GetResponse()
            $code = [int]$resp.StatusCode
            $resp.Close()
            Write-Host "purge $qn -> HTTP $code"
        } catch {
            Write-Warning "purge $qn 失败（可能队列还不存在）：$($_.Exception.Message)"
        }
    }

    # --- 4. 复核 ---
    Write-Host '--- 复位后 ---'
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e `
        "SELECT COUNT(*) AS orders FROM seckill.seckill_order;
         SELECT COUNT(*) AS consume_log FROM seckill.mq_consume_log;
         SELECT course_id, capacity, stock, sold, stock + sold AS sum_stock_sold
         FROM seckill.course_stock WHERE course_id = $CourseId;"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    $redisVal = & $redisCli get $key 2>&1
    Write-Host "Redis $key = '${redisVal}'（空表示未预热，第一次抢课时会自动预热）"
}
finally {
    Remove-Item $cnf -ErrorAction SilentlyContinue
}
