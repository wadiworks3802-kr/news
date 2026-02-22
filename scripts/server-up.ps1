<#
 서버 배포(백그라운드) 실행 스크립트.

 작성자 : 안태욱
 현재날짜 : 2026년 02월 20일

 사용:
   powershell -ExecutionPolicy Bypass -File .\scripts\server-up.ps1
   powershell -ExecutionPolicy Bypass -File .\scripts\server-up.ps1 -NoBuild
#>

param(
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker 명령을 찾을 수 없습니다. Docker Desktop(또는 Docker Engine) 설치 후 다시 실행하세요."
}

$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
Set-Location $projectRoot

Write-Host "[GND] docker compose 백그라운드 기동 시작..."
if ($NoBuild) {
    docker compose up -d
} else {
    docker compose up -d --build
}

Write-Host "[GND] 현재 컨테이너 상태:"
docker compose ps
