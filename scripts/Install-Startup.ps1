# Install-Startup.ps1
# Registers Fiery Hold Sender to start automatically when the current user logs on to Windows, and
# restarts it if it crashes. Uses Task Scheduler (no admin rights needed for your own account).
#
# Why not a Windows Service? Services run in "session 0" and cannot show windows, so the job popup
# would never appear. A logon task runs in the user's desktop session but behaves like a service
# (starts automatically, no console window, restarted on failure).
#
#   powershell -ExecutionPolicy Bypass -File .\Install-Startup.ps1
#   powershell -ExecutionPolicy Bypass -File .\Install-Startup.ps1 -InstallDir "C:\FieryHoldSender" -StartNow

param(
    [string]$InstallDir = (Split-Path -Parent $PSScriptRoot),   # folder containing fiery-hold-sender.jar
    [string]$JavaHome   = $env:JAVA_HOME,
    [string]$TaskName   = "FieryHoldSender",
    [switch]$StartNow
)

$ErrorActionPreference = 'Stop'

# Prefer the jpackage app-image exe (bundled Java), else javaw + jar
$exe = Join-Path $InstallDir "FieryHoldSender\FieryHoldSender.exe"
$jar = @((Join-Path $InstallDir "fiery-hold-sender.jar"), (Join-Path $InstallDir "target\fiery-hold-sender.jar")) |
       Where-Object { Test-Path $_ } | Select-Object -First 1
$config = @((Join-Path $InstallDir "config.properties"), (Join-Path $InstallDir "config\config.properties")) |
          Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $config) { throw "config.properties not found in $InstallDir or $InstallDir\config" }

if (Test-Path $exe) {
    $action = New-ScheduledTaskAction -Execute $exe -Argument "`"$config`"" -WorkingDirectory (Split-Path $exe)
    Write-Host "Using app image: $exe"
} elseif ($jar) {
    $javaw = if ($JavaHome -and (Test-Path "$JavaHome\bin\javaw.exe")) { "$JavaHome\bin\javaw.exe" }
             else { (Get-Command javaw.exe -ErrorAction SilentlyContinue).Source }
    if (-not $javaw) { throw "javaw.exe not found. Install Java 21 or pass -JavaHome." }
    $version = & ($javaw -replace 'javaw\.exe$', 'java.exe') -version 2>&1 | Select-Object -First 1
    Write-Host "Using $javaw ($version)"
    $action = New-ScheduledTaskAction -Execute $javaw -Argument "-jar `"$jar`" `"$config`"" -WorkingDirectory $InstallDir
} else {
    throw "Neither $exe nor fiery-hold-sender.jar found in $InstallDir. Build with 'mvn package' first."
}

$user      = "$env:USERDOMAIN\$env:USERNAME"
$trigger   = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = "PT30S"   # let the network / mapped drives come up first
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
               -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
               -MultipleInstances IgnoreNew -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Description "Watches the hot folder and sends jobs to the Fiery Hold queue" -Force | Out-Null
Write-Host "Scheduled task '$TaskName' registered: starts at logon of $user, config $config" -ForegroundColor Green

if ($StartNow) {
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Started. Look for the tray icon." -ForegroundColor Green
}
