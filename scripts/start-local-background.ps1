<#
 로컬 3개 프로세스(API/WEB/BATCH) 백그라운드 기동 스크립트.

 작성자 : 안태욱
 현재날짜 : 2026년 02월 20일
#>

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$jdkPath = Join-Path $projectRoot ".tools\jdk-21"
$logDir = Join-Path $projectRoot "run-logs"
$pidFile = Join-Path $logDir "local-background-pids.json"

if (-not (Test-Path $jdkPath)) {
    throw "JDK 21 경로를 찾지 못했습니다: $jdkPath"
}

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$services = @(
    @{ name = "api"; task = ":api:bootRun"; args = "--spring.profiles.active=local --server.port=8080"; mainClass = "com.wangbyul.gnd.api.GndApiApplication" },
    @{ name = "web"; task = ":web:bootRun"; args = "--server.port=8081"; mainClass = "com.wangbyul.gnd.web.WebApplication" },
    @{ name = "batch"; task = ":batch:bootRun"; args = "--spring.profiles.active=local"; mainClass = "com.wangbyul.gnd.batch.BatchApplication" }
)

$wrapperPids = @{}
foreach ($svc in $services) {
    $logFile = Join-Path $logDir ("{0}-background.log" -f $svc.name)
    $cmd = "`$env:JAVA_HOME='{0}'; `$env:Path='{0}\bin;' + `$env:Path; Set-Location '{1}'; .\gradlew.bat {2} --args='{3}' --console=plain *> '{4}'" -f `
        $jdkPath, $projectRoot, $svc.task, $svc.args, $logFile

    $proc = Start-Process -FilePath "powershell" `
        -ArgumentList "-NoProfile", "-Command", $cmd `
        -WindowStyle Hidden `
        -PassThru

    $wrapperPids[$svc.name] = $proc.Id
    Start-Sleep -Milliseconds 350
}

$appPids = @{}
foreach ($svc in $services) {
    $foundPid = $null
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        $found = Get-CimInstance Win32_Process |
            Where-Object { $_.CommandLine -like ("*" + $svc.mainClass + "*") } |
            Select-Object -First 1
        if ($found) {
            $foundPid = [int]$found.ProcessId
            break
        }
        Start-Sleep -Milliseconds 500
    }
    $appPids[$svc.name] = $foundPid
}

$payload = @{
    wrapper = $wrapperPids
    app = $appPids
}

$payload | ConvertTo-Json | Set-Content -Path $pidFile -Encoding UTF8
Write-Host "[GND] 백그라운드 기동 완료. PID 파일: $pidFile"
Write-Host ($payload | ConvertTo-Json -Compress)
