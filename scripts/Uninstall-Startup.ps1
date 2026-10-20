# Uninstall-Startup.ps1 - stops Fiery Hold Sender and removes its logon task.
param([string]$TaskName = "FieryHoldSender")

if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Removed scheduled task '$TaskName'" -ForegroundColor Green
} else {
    Write-Host "Scheduled task '$TaskName' not found"
}
