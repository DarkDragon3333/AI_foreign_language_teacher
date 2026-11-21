# Установщик Windows. Docker Desktop и драйвер NVIDIA не ставит — только проверяет.
param([switch]$ShortcutOnly)

$ErrorActionPreference = "Continue"
Set-Location -LiteralPath $PSScriptRoot

function Show-ComposeFailure {
    param([string]$Message)
    Write-Host $Message
    Write-Host "Контейнеры не удалялись."
    & docker compose ps
    & docker compose logs --tail 40 ollama-pull
    exit 1
}

function Install-DesktopShortcut {
    $icon = Join-Path $PSScriptRoot "assets\teacher.ico"
    $target = Join-Path $PSScriptRoot "launch.bat"
    if (-not (Test-Path -LiteralPath $icon) -or -not (Test-Path -LiteralPath $target)) {
        Write-Host "Ярлык не создан: нет launch.bat или assets\teacher.ico."
        exit 1
    }
    $shell = New-Object -ComObject WScript.Shell
    $places = @(
        [Environment]::GetFolderPath("Desktop"),
        [Environment]::GetFolderPath("Programs")
    )
    foreach ($dir in $places) {
        if (-not $dir) { continue }
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
        $lnk = $shell.CreateShortcut((Join-Path $dir "Учитель языков.lnk"))
        $lnk.TargetPath = $target
        $lnk.WorkingDirectory = $PSScriptRoot
        $lnk.IconLocation = "$icon,0"
        $lnk.Description = "Запуск учителя языков"
        $lnk.WindowStyle = 1
        $lnk.Save()
    }
    Write-Host "Ярлык «Учитель языков» на рабочем столе и в меню Пуск. Он поднимает контейнеры и открывает сайт."
}

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

if ($ShortcutOnly) {
    Install-DesktopShortcut
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "Docker не найден. Установите Docker Desktop с бэкендом WSL2, запустите его и снова откройте install.bat."
    Write-Host "https://docs.docker.com/desktop/setup/install/windows-install/"
    exit 1
}

& docker info *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker не запущен. Откройте Docker Desktop, дождитесь готовности и снова запустите install.bat."
    exit 1
}

& docker compose version *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Нет команды docker compose. Нужен Docker Desktop с Compose V2."
    exit 1
}

if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
    Write-Host "Не найден nvidia-smi. Установите драйвер NVIDIA: без него сервис ollama не стартует."
    Write-Host "Установщик драйвер не ставит и docker-compose.yaml не меняет."
    exit 1
}

$drive = (Get-Item -LiteralPath $PSScriptRoot).PSDrive
if ($drive.Free -and $drive.Free -lt 20GB) {
    $freeGb = [math]::Round($drive.Free / 1GB, 1)
    Write-Host "Предупреждение: на диске $($drive.Name) свободно около $freeGb ГБ. Для образов и моделей нужно порядка 20 ГБ. Данные Docker могут лежать на другом диске."
}

$envPath = Join-Path $PSScriptRoot ".env"
$examplePath = Join-Path $PSScriptRoot ".env.example"
if (-not (Test-Path -LiteralPath $envPath)) {
    if (-not (Test-Path -LiteralPath $examplePath)) {
        Write-Host "Нет файла .env.example. Запускайте install.bat из папки проекта."
        exit 1
    }
    $alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".ToCharArray()
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
    $content = [System.IO.File]::ReadAllText($examplePath)
    $updated = [regex]::Replace($content, "(?m)^POSTGRES_PASSWORD=change-me(?=\r?$)", "POSTGRES_PASSWORD=$password")
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($envPath, $updated, $utf8)
    if ($updated -eq $content) {
        Write-Host "Файл .env создан из примера. Строка POSTGRES_PASSWORD=change-me не найдена, пароль не заменён."
    } else {
        Write-Host "Создан .env. Пароль Postgres записан в файл и в консоль не выводится."
    }
} else {
    Write-Host "Файл .env уже есть, он не изменён."
}

Write-Host "Сборка и запуск. Первый раз это долго: образы, jar и модель урока (около 6 ГБ)."
& docker compose up -d --build
if ($LASTEXITCODE -ne 0) {
    Show-ComposeFailure "Сборка или запуск не удались."
}

Write-Host "Жду готовности: Postgres, LanguageTool и модель урока. До 90 минут."
Write-Host "Распознавание и озвучка в эту проверку не входят и могут догружаться после открытия сайта."
$deadline = (Get-Date).AddMinutes(90)
$ready = $false
while ((Get-Date) -lt $deadline) {
    $line = "сайт ещё не отвечает"
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
            if ($parts.Count -gt 0) { $line = $parts -join "; " }
        } catch {
            $line = "сайт ещё не отвечает"
        }
    }
    Write-Host ("Жду готовности: {0}" -f $line)
    Start-Sleep -Seconds 10
}

if (-not $ready) {
    Show-ComposeFailure "За 90 минут сервис не стал готов."
}

try { Install-DesktopShortcut } catch { Write-Host ("Ярлык не создан: {0}" -f $_.Exception.Message) }
Write-Host "Готово. Открываю http://localhost"
Write-Host "Голос (распознавание и озвучка) может догружаться ещё несколько минут."
Start-Process "http://localhost"
exit 0
