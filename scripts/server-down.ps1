<#
 서버 배포(백그라운드) 종료 스크립트.

 작성자 : 안태욱
 현재날짜 : 2026년 02월 20일

 사용:
   powershell -ExecutionPolicy Bypass -File .\scripts\server-down.ps1
#>

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker 명령을 찾을 수 없습니다. Docker 설치 상태를 확인하세요."
}

$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
Set-Location $projectRoot

Write-Host "[GND] docker compose 백그라운드 종료..."
docker compose down
