# Ярлык «Учитель языков»: поднимает уже собранные контейнеры и открывает сайт.
$ErrorActionPreference = "Continue"
Set-Location -LiteralPath $PSScriptRoot

function Get-HealthBody {
    if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
        $out = & curl.exe -sS -m 5 "http://localhost/api/health" 2>&1 | Out-String
        return $out.Trim()
    }
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost/api/health" -UseBasicParsing -TimeoutSec 5
        return [string]$resp.Content
    } catch {
        $response = $_.Exception.Response
        if (-not $response) { return "" }
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
}

function Get-DockerDesktopExe {
    $candidates = @()
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($docker) {
        $installRoot = Split-Path (Split-Path (Split-Path $docker.Source))
        $candidates += Join-Path $installRoot "Docker Desktop.exe"
        $candidates += Join-Path $installRoot "frontend\Docker Desktop.exe"
    }
    $candidates += Join-Path $Env:LocalAppData "Programs\DockerDesktop\Docker Desktop.exe"
    $candidates += Join-Path $Env:ProgramFiles "Docker\Docker\Docker Desktop.exe"
    if (${Env:ProgramFiles(x86)}) {
        $candidates += Join-Path ${Env:ProgramFiles(x86)} "Docker\Docker\Docker Desktop.exe"
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Test-DockerReady {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) { return $false }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $docker.Source
    $info.Arguments = 'info --format "{{.ServerVersion}}"'
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { return $false }
    if (-not $process.WaitForExit(5000)) {
        try { $process.Kill() } catch {}
        return $false
    }
    return $process.ExitCode -eq 0
}

if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot ".env"))) {
    Write-Host "Нет .env. Сначала запустите install.bat в папке проекта."
    exit 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Docker не найден. Установите Docker Desktop и запустите install.bat."
    Write-Host "https://docs.docker.com/desktop/setup/install/windows-install/"
    exit 1
}

Write-Host "Проверяю Docker..."
if (-not (Test-DockerReady)) {
    $dockerExe = Get-DockerDesktopExe
    if (-not $dockerExe) {
        Write-Host "Docker не запущен, и Docker Desktop.exe не найден."
        exit 1
    }
    Write-Host "Запускаю Docker Desktop..."
    Start-Process -FilePath $dockerExe
    $engineUp = $false
    $dockerDeadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $dockerDeadline) {
        Write-Host "Жду Docker Desktop: движок ещё запускается"
        Start-Sleep -Seconds 5
        if (Test-DockerReady) {
            $engineUp = $true
            break
        }
    }
    if (-not $engineUp) {
        Write-Host "Docker Desktop не ответил за 5 минут. Откройте его и запустите ярлык снова."
        exit 1
    }
}

if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    Write-Host "Не найден nvidia-smi. Без драйвера NVIDIA сервис ollama не стартует."
    exit 1
}

Write-Host "Поднимаю контейнеры..."
& docker compose up -d --no-build
if ($LASTEXITCODE -ne 0) {
    Write-Host "Контейнеры не запустились. Для первой сборки запустите install.bat."
    & docker compose ps
    exit 1
}

$deadline = (Get-Date).AddMinutes(10)
$ready = $false
while ((Get-Date) -lt $deadline) {
    $raw = Get-HealthBody
    if ($raw) {
        try {
            $json = $raw | ConvertFrom-Json
            if ($json.status -eq "up") {
                $ready = $true
                break
            }
            $parts = @()
            foreach ($p in $json.components.PSObject.Properties) {
                if ($p.Value.status -ne "up") {
                    $parts += ("{0}: {1}" -f $p.Name, $p.Value.detail)
                }
            }
            $line = "сайт ещё не отвечает"
            if ($parts.Count -gt 0) { $line = $parts -join "; " }
            Write-Host ("Жду готовности: {0}" -f $line)
        } catch {
            Write-Host "Жду готовности: сайт ещё не отвечает"
        }
    } else {
        Write-Host "Жду готовности: сайт ещё не отвечает"
    }
    Start-Sleep -Seconds 5
}

if (-not $ready) {
    Write-Host "Сайт не ответил за 10 минут. Контейнеры не удалялись."
    Write-Host "Если проект ещё не устанавливали, запустите install.bat."
    & docker compose ps
    & docker compose logs --tail 40 ollama-pull
    exit 1
}

Write-Host "Готово. Открываю http://localhost"
Start-Process "http://localhost"
exit 0
