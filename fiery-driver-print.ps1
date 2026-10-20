# fiery-driver-print.ps1
# Print any file (or a whole folder) through the installed Fiery printer DRIVER, so the Fiery receives
# a proper job ticket (Paper Catalog, size, tray) exactly like printing from an app.
#   Images (jpg/png/bmp/gif/tif) -> printed with .NET PrintDocument on the chosen paper (13x19 by default)
#   Text (.txt/.log/.json/...)   -> printed with .NET PrintDocument
#   PDF                          -> SumatraPDF (silent), else the PDF app's "PrintTo" command
#   Word / Excel / PowerPoint    -> printed by Microsoft Office
#   Anything else                -> Windows "PrintTo" verb of the file's associated app
#
# Examples:
#   .\fiery-driver-print.ps1 -ListPrinters
#   .\fiery-driver-print.ps1 -ListPapers
#   .\fiery-driver-print.ps1 -Path C:\jobs\TestPrint.jpg
#   .\fiery-driver-print.ps1 -Path C:\jobs\ -Copies 2

param(
    [Alias("File")][string]$Path,
    [string]$Printer,                  # exact printer name; default: the printer whose port is $PrinterIp
    [string]$PrinterIp  = "192.168.1.104",
    [int]   $Copies     = 1,
    [string]$PaperMatch = '13.{0,4}19', # regex matched against the driver's paper names
    [switch]$Recurse,
    [switch]$ListPrinters,
    [switch]$ListPapers,
    [string]$LogFile    = "C:\PrintLog\fiery_driver.log"
)

New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null
Add-Type -AssemblyName System.Drawing

$ImageExt = '.png', '.bmp', '.gif', '.tif', '.tiff', '.jfif', '.jpe', '.jpeg', '.jpg'
$TextExt  = '.txt', '.log', '.md', '.ini', '.cfg', '.json', '.xml', '.yaml', '.yml', '.sql', '.java', '.py', '.ps1', '.csv'
$WordExt  = '.doc', '.docx', '.docm', '.rtf', '.odt', '.htm', '.html', '.mht', '.dot', '.dotx'
$ExcelExt = '.xls', '.xlsx', '.xlsm', '.ods'
$PptExt   = '.ppt', '.pptx', '.pps', '.ppsx', '.odp'

function Log([string]$Step, [string]$Msg, [string]$Level = "INFO") {
    $line = "{0:yyyy-MM-dd HH:mm:ss.fff} [{1,-5}] [{2,-8}] {3}" -f (Get-Date), $Level, $Step, $Msg
    Add-Content -Path $LogFile -Value $line
    $color = switch ($Level) { "ERROR" { "Red" } "WARN" { "Yellow" } "OK" { "Green" } default { "Gray" } }
    Write-Host $line -ForegroundColor $color
}
function Release($o) { if ($o) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($o) } }

# ---------------- pick printer ----------------
if ($ListPrinters) { Get-Printer | Select-Object Name, DriverName, PortName | Format-Table -AutoSize; exit 0 }

if (-not $Printer) {
    # pick the printer installed on the Fiery's IP port (e.g. 192.168.1.104 or IP_192.168.1.104)
    $Printer = (Get-Printer | Where-Object { $_.PortName -match [regex]::Escape($PrinterIp) } | Select-Object -First 1).Name
}
if (-not $Printer -or -not (Get-Printer -Name $Printer -ErrorAction SilentlyContinue)) {
    Log "PRINTER" "No printer found on port $PrinterIp. Run with -ListPrinters and pass -Printer `"<exact name>`"." "ERROR"; exit 1
}
Log "PRINTER" "Using printer '$Printer'" "OK"

function New-PrintDoc([string]$DocName) {
    $pd = New-Object System.Drawing.Printing.PrintDocument
    $pd.PrinterSettings.PrinterName = $Printer
    $pd.PrinterSettings.Copies = [int16]$Copies
    $pd.DocumentName = $DocName
    $pd.PrintController = New-Object System.Drawing.Printing.StandardPrintController   # no progress dialog
    $paper = $pd.PrinterSettings.PaperSizes | Where-Object { $_.PaperName -match $PaperMatch } | Select-Object -First 1
    if ($paper) {
        $pd.DefaultPageSettings.PaperSize = $paper
        Log "PAPER" "Using '$($paper.PaperName)' ($([Math]::Round($paper.Width/100,2)) x $([Math]::Round($paper.Height/100,2)) in)" "OK"
    } else {
        Log "PAPER" "No paper matching '$PaperMatch' in driver - using driver default (see -ListPapers)" "WARN"
    }
    return $pd
}

if ($ListPapers) {
    $ps = New-Object System.Drawing.Printing.PrinterSettings
    $ps.PrinterName = $Printer
    Write-Host "Paper sizes offered by '$Printer':"
    $ps.PaperSizes | ForEach-Object { "  {0,-40} {1,6:N2} x {2,6:N2} in" -f $_.PaperName, ($_.Width / 100), ($_.Height / 100) }
    exit 0
}

if (-not $Path) { Log "INPUT" "Pass -Path <file|folder|wildcard>" "ERROR"; exit 1 }

# ---------------- images ----------------
function Print-Image([string]$File, [string]$Name) {
    $script:img   = [System.Drawing.Image]::FromFile($File)
    $script:dim   = [System.Drawing.Imaging.FrameDimension]::Page
    $script:count = try { $script:img.GetFrameCount($script:dim) } catch { 1 }
    $script:idx   = 0
    try {
        $pd = New-PrintDoc $Name
        $pd.DefaultPageSettings.Landscape = ($script:img.Width -gt $script:img.Height)
        $pd.DefaultPageSettings.Margins = New-Object System.Drawing.Printing.Margins(25, 25, 25, 25)  # 0.25 in
        $pd.add_PrintPage({
            param($sender, $e)
            if ($script:count -gt 1) { [void]$script:img.SelectActiveFrame($script:dim, $script:idx) }
            $r = $e.MarginBounds
            $s = [Math]::Min($r.Width / $script:img.Width, $r.Height / $script:img.Height)
            $w = $script:img.Width * $s; $h = $script:img.Height * $s
            $x = $r.Left + ($r.Width - $w) / 2; $y = $r.Top + ($r.Height - $h) / 2
            $e.Graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $e.Graphics.DrawImage($script:img, [single]$x, [single]$y, [single]$w, [single]$h)
            $script:idx++
            $e.HasMorePages = ($script:idx -lt $script:count)
        })
        $pd.Print()
        $pd.Dispose()
        Log "PRINT" "Image sent to driver ($script:count page(s))" "OK"
    } finally { $script:img.Dispose() }
}

# ---------------- text ----------------
function Print-Text([string]$File, [string]$Name) {
    $script:lines = @(Get-Content -Path $File -Encoding UTF8)
    if ($script:lines.Count -eq 0) { $script:lines = @('') }
    $script:li   = 0
    $script:font = New-Object System.Drawing.Font("Consolas", 10)
    try {
        $pd = New-PrintDoc $Name
        $pd.add_PrintPage({
            param($sender, $e)
            $r = $e.MarginBounds
            $lh = $script:font.GetHeight($e.Graphics)
            $y = [single]$r.Top
            while ($script:li -lt $script:lines.Count -and ($y + $lh) -le $r.Bottom) {
                $t = "$($script:lines[$script:li])".Replace("`t", "    ")
                $e.Graphics.DrawString($t, $script:font, [System.Drawing.Brushes]::Black, [single]$r.Left, $y)
                $y += $lh; $script:li++
            }
            $e.HasMorePages = ($script:li -lt $script:lines.Count)
        })
        $pd.Print()
        $pd.Dispose()
        Log "PRINT" "Text sent to driver ($($script:lines.Count) lines)" "OK"
    } finally { $script:font.Dispose() }
}

# ---------------- PDF ----------------
function Print-Pdf([string]$File) {
    $sumatra = @("$env:LOCALAPPDATA\SumatraPDF\SumatraPDF.exe",
                 "$env:ProgramFiles\SumatraPDF\SumatraPDF.exe",
                 "${env:ProgramFiles(x86)}\SumatraPDF\SumatraPDF.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($sumatra) {
        Start-Process -FilePath $sumatra -ArgumentList '-print-to', "`"$Printer`"", '-print-settings', "`"${Copies}x,fit`"", '-silent', "`"$File`"" -Wait -NoNewWindow
        Log "PRINT" "PDF sent to driver with SumatraPDF (paper = driver default)" "OK"
    } else {
        Log "PRINT" "SumatraPDF not found - using the PDF app's PrintTo command (install SumatraPDF for silent printing)" "WARN"
        Print-Verb $File
    }
}

# ---------------- Office ----------------
function Print-Office([string]$File, [string]$Ext) {
    $full = (Resolve-Path $File).Path
    if ($WordExt -contains $Ext) {
        $app = New-Object -ComObject Word.Application; $app.Visible = $false; $app.DisplayAlerts = 0
        $doc = $app.Documents.Open($full, $false, $true)
        $app.ActivePrinter = $Printer
        $doc.PrintOut($false, $false, 0, "", "", "", 0, $Copies)    # Background=false: wait until spooled
        $doc.Close($false); $app.Quit(); Release $doc; Release $app
    } elseif ($ExcelExt -contains $Ext) {
        $app = New-Object -ComObject Excel.Application; $app.Visible = $false; $app.DisplayAlerts = $false
        $wb = $app.Workbooks.Open($full, 0, $true)
        $wb.PrintOut([Type]::Missing, [Type]::Missing, $Copies, $false, $Printer)
        $wb.Close($false); $app.Quit(); Release $wb; Release $app
    } else {
        $app = New-Object -ComObject PowerPoint.Application
        $pres = $app.Presentations.Open($full, -1, 0, 0)
        $pres.PrintOptions.ActivePrinter = $Printer
        $pres.PrintOptions.NumberOfCopies = $Copies
        $pres.PrintOptions.PrintInBackground = 0
        $pres.PrintOut()
        $pres.Close(); $app.Quit(); Release $pres; Release $app
    }
    Log "PRINT" "Printed with Microsoft Office (paper = document/driver setting)" "OK"
}

# ---------------- anything else ----------------
function Print-Verb([string]$File) {
    for ($c = 1; $c -le $Copies; $c++) {
        Start-Process -FilePath $File -Verb PrintTo -ArgumentList "`"$Printer`"" -Wait
    }
    Log "PRINT" "Sent with the associated app's PrintTo command" "OK"
}

# ---------------- main ----------------
if (Test-Path $Path -PathType Container) { $files = Get-ChildItem -Path $Path -File -Recurse:$Recurse }
else { $files = Get-ChildItem -Path $Path -File -ErrorAction SilentlyContinue }
if (-not $files) { Log "INPUT" "No files found at '$Path'" "ERROR"; exit 1 }

Log "INPUT" "$(@($files).Count) file(s) -> printer '$Printer', copies=$Copies" "OK"
$ok = 0; $fail = 0
foreach ($f in $files) {
    $ext = $f.Extension.ToLower()
    Log "START" "---- $($f.Name) ----"
    try {
        if ($ImageExt -contains $ext)      { Print-Image $f.FullName $f.Name }
        elseif ($TextExt -contains $ext)   { Print-Text  $f.FullName $f.Name }
        elseif ($ext -eq '.pdf')           { Print-Pdf   $f.FullName }
        elseif ($WordExt + $ExcelExt + $PptExt -contains $ext) { Print-Office $f.FullName $ext }
        else                               { Print-Verb  $f.FullName }
        $ok++
    } catch {
        Log "ERROR" "$($f.Name): $($_.Exception.Message)" "ERROR"
        $fail++
    }
}
$level = if ($fail) { "WARN" } else { "OK" }
Log "SUMMARY" "Printed: $ok   Failed: $fail   Log: $LogFile" $level
if ($fail) { exit 1 }