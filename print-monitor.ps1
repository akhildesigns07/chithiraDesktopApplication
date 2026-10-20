# print-monitor.ps1
# POC: poll all Windows print queues and append every new print job to a text file.
# Run:  powershell -ExecutionPolicy Bypass -File .\print-monitor.ps1
# Stop: Ctrl+C

param(
    [string]$LogFile = "C:\PrintLog\print_jobs.txt",
    [int]$IntervalMs = 500
)

New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
$seen = @{}

Write-Host "Watching print queues... logging to $LogFile (Ctrl+C to stop)"

while ($true) {
    foreach ($printer in Get-Printer) {
        $jobs = Get-PrintJob -PrinterName $printer.Name -ErrorAction SilentlyContinue
        foreach ($job in $jobs) {
            # Job IDs get reused, so include submit time in the key
            $key = "$($printer.Name)|$($job.Id)|$($job.SubmittedTime.Ticks)"
            if ($seen.ContainsKey($key)) { continue }
            $seen[$key] = Get-Date

            $line = "{0:yyyy-MM-dd HH:mm:ss} | Printer={1} | JobId={2} | Document={3} | User={4} | Pages={5} | Bytes={6} | Status={7}" -f `
                (Get-Date), $printer.Name, $job.Id, $job.DocumentName, $job.UserName,
                $job.TotalPages, $job.Size, $job.JobStatus

            Add-Content -Path $LogFile -Value $line
            Write-Host $line
        }
    }

    # Forget keys older than 1 hour so the table doesn't grow forever
    $cutoff = (Get-Date).AddHours(-1)
    foreach ($k in @($seen.Keys)) { if ($seen[$k] -lt $cutoff) { $seen.Remove($k) } }

    Start-Sleep -Milliseconds $IntervalMs
}