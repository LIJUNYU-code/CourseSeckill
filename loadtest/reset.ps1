# 压测前复位：清空订单、库存恢复满额。
#
# 用法（在项目根目录）：
#   .\loadtest\reset.ps1
#   .\loadtest\reset.ps1 -CourseId 88
#
# 为什么必须复位：v0 有丢失更新，上一轮跑完 stock/sold 可能已经不对。
# 不复位就压第二轮，数据是叠加上去的，无法判断这一轮到底超卖了几单。
#
# 关于密码怎么传给 mysql（踩过两个坑）：
#   坑 1：写成 -p$pw 参数 → mysql 往 stderr 打
#         "Using a password on the command line interface can be insecure"，
#         PowerShell 5.1 会把原生程序 stderr 当成 NativeCommandError，
#         配合 $ErrorActionPreference='Stop' 直接中断脚本。
#   坑 2：改用 MYSQL_PWD 环境变量 → 本机这个 mysql 客户端不认，报 Access denied。
#   正解：临时 option file（MySQL 官方推荐）。密码既不进命令行、也不打 warning。
#         文件写在 $env:TEMP 下，用完在 finally 里删掉。

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
if (-not (Test-Path $cfgPath)) {
    throw "找不到 $cfgPath"
}

$cfg = [System.IO.File]::ReadAllText($cfgPath, [System.Text.Encoding]::UTF8)
$pw = [regex]::Match($cfg, '(?m)^\s*password:\s*(\S+)\s*$').Groups[1].Value
if (-not $pw) {
    throw "没能从 $cfgPath 里读出 mysql 密码"
}

$cnf = Join-Path $env:TEMP ("seckill-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")
[System.IO.File]::WriteAllText($cnf, "[client]`r`nuser=root`r`npassword=$pw`r`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    $sql = @"
TRUNCATE TABLE seckill.seckill_order;
UPDATE seckill.course_stock SET stock = capacity, sold = 0 WHERE course_id = $CourseId;
"@
    $sqlFile = Join-Path $env:TEMP ("seckill-reset-" + [guid]::NewGuid().ToString('N') + ".sql")
    [System.IO.File]::WriteAllText($sqlFile, $sql, (New-Object System.Text.UTF8Encoding($false)))

    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -e "source $($sqlFile -replace '\\','/')"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    Write-Host '--- 复位后 ---'
    & $mysql "--defaults-extra-file=$cnf" --default-character-set=utf8mb4 -t -e `
        "SELECT COUNT(*) AS orders FROM seckill.seckill_order;
         SELECT course_id, capacity, stock, sold, stock + sold AS sum_stock_sold
         FROM seckill.course_stock WHERE course_id = $CourseId;"
    if ($LASTEXITCODE -ne 0) { throw "mysql 退出码 $LASTEXITCODE" }

    Remove-Item $sqlFile -ErrorAction SilentlyContinue
}
finally {
    Remove-Item $cnf -ErrorAction SilentlyContinue
}
