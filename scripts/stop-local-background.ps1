<#
 로컬 백그라운드(API/WEB/BATCH) 종료 스크립트.

 작성자 : 안태욱
 현재날짜 : 2026년 02월 20일
#>

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$pidFile = Join-Path $projectRoot "run-logs\local-background-pids.json"

if (-not (Test-Path $pidFile)) {
    Write-Host "[GND] PID 파일이 없어 종료할 프로세스를 찾지 못했습니다: $pidFile"
    $pids = $null
} else {
    $pids = Get-Content -Path $pidFile -Raw | ConvertFrom-Json
}

function Stop-ByPid($name, $pidValue) {
    if (-not $pidValue) {
        return
    }
    try {
        Stop-Process -Id $pidValue -Force -ErrorAction Stop
        Write-Host "[GND] 종료 완료: $name (pid=$pidValue)"
    } catch {
        Write-Host "[GND] 이미 종료됨 또는 접근 불가: $name (pid=$pidValue)"
    }
}

foreach ($name in @("api", "web", "batch")) {
    if ($pids -and $pids.PSObject.Properties.Name -contains "app") {
        Stop-ByPid $name $pids.app.$name
        Stop-ByPid "$name-wrapper" $pids.wrapper.$name
        continue
    }
    if ($pids) {
        Stop-ByPid $name $pids.$name
    }
}

$mainClasses = @{
    "api" = "com.wangbyul.gnd.api.GndApiApplication"
    "web" = "com.wangbyul.gnd.web.WebApplication"
    "batch" = "com.wangbyul.gnd.batch.BatchApplication"
}

foreach ($entry in $mainClasses.GetEnumerator()) {
    $match = Get-CimInstance Win32_Process |
        Where-Object { $_.CommandLine -like ("*" + $entry.Value + "*") }
    foreach ($proc in $match) {
        Stop-ByPid "$($entry.Key)-java" $proc.ProcessId
    }
}

Remove-Item -Path $pidFile -Force -ErrorAction SilentlyContinue
