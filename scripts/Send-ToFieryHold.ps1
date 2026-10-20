# Send-ToFieryHold.ps1
# Sends ONE file to the Fiery Hold queue and verifies the Fiery received it. Called by the Java app
# with a JSON job file, but can be run by hand for testing:
#   powershell -ExecutionPolicy Bypass -File .\Send-ToFieryHold.ps1 -JobFile C:\temp\job.json
#
# Methods:
#   LPR (default) - LPD protocol (RFC 1179) to port 515, queue "hold". The Fiery acknowledges the queue
#                   name, the data file and the control file separately, so a successful run means the
#                   job is in the Fiery's Hold queue.
#   RAW           - port 9100. Success = Fiery read all data and closed the connection. Lands in the
#                   queue the Fiery has configured for port 9100.
#
# Job attributes (copies, media, finishing, notes) travel in the PJL header. The Java app builds the
# PJL lines from config.properties and passes them in "pjl".
#
# Last output line is always:  RESULT|OK|<message>  (exit 0)  or  RESULT|FAIL|<message>  (exit 1)

param([Parameter(Mandatory = $true)][string]$JobFile)

$ErrorActionPreference = 'Stop'

try {
    $job = Get-Content -Raw -Encoding UTF8 -Path $JobFile | ConvertFrom-Json
} catch {
    Write-Output "RESULT|FAIL|Cannot read job file ${JobFile}: $($_.Exception.Message)"; exit 1
}

$LogFile = if ($job.logFile) { $job.logFile } else { "C:\PrintLog\fiery_hold_send.log" }
New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

function Log([string]$Step, [string]$Msg, [string]$Level = "INFO") {
    $line = "{0:yyyy-MM-dd HH:mm:ss.fff} [{1,-5}] [{2,-8}] {3}" -f (Get-Date), $Level, $Step, $Msg
    Add-Content -Path $LogFile -Value $line
    [Console]::Out.WriteLine($line)   # not Write-Output: that would leak into function return values
}

function Finish([bool]$Ok, [string]$Msg) {
    if ($Ok) { Log "DONE" $Msg "OK"; [Console]::Out.WriteLine("RESULT|OK|$Msg"); exit 0 }
    Log "DONE" $Msg "ERROR"; [Console]::Out.WriteLine("RESULT|FAIL|$Msg"); exit 1
}

# ---------------- JPEG -> one-page PostScript (from fiery-send-debug.ps1) ----------------
function Convert-JpegToPostScript([string]$Path, [byte[]]$Jpeg) {
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($Path)
    $w = $img.Width; $h = $img.Height; $pf = "$($img.PixelFormat)"
    $img.Dispose()

    if ($pf -match 'Cmyk')               { $cs = '/DeviceCMYK'; $dec = '[1 0 1 0 1 0 1 0]' }
    elseif ($pf -eq 'Format8bppIndexed') { $cs = '/DeviceGray'; $dec = '[0 1]' }
    else                                 { $cs = '/DeviceRGB';  $dec = '[0 1 0 1 0 1]' }

    # Fit onto A4 (595 x 842 pt) with 36 pt margins, keeping aspect ratio
    $pw = 595; $ph = 842; $m = 36
    $s  = [Math]::Min(($pw - 2*$m) / $w, ($ph - 2*$m) / $h)
    $dw = [Math]::Round($w * $s, 2); $dh = [Math]::Round($h * $s, 2)
    $x  = [Math]::Round(($pw - $dw) / 2, 2); $y = [Math]::Round(($ph - $dh) / 2, 2)
    $name = [System.IO.Path]::GetFileName($Path)

    $head = @"
%!PS-Adobe-3.0
%%Title: $name
%%BoundingBox: 0 0 $pw $ph
%%Pages: 1
%%EndComments
<< /PageSize [$pw $ph] >> setpagedevice
gsave
$x $y translate
$dw $dh scale
$cs setcolorspace
<< /ImageType 1 /Width $w /Height $h /BitsPerComponent 8 /Decode $dec /ImageMatrix [$w 0 0 -$h 0 $h] /DataSource currentfile /DCTDecode filter >> image

"@ -replace "`r`n", "`n"
    $tail = "`ngrestore`nshowpage`n%%EOF`n"

    $ms = New-Object System.IO.MemoryStream
    $hb = [Text.Encoding]::ASCII.GetBytes($head); $ms.Write($hb, 0, $hb.Length)
    $ms.Write($Jpeg, 0, $Jpeg.Length)
    $tb = [Text.Encoding]::ASCII.GetBytes($tail); $ms.Write($tb, 0, $tb.Length)
    return ,$ms.ToArray()
}

# ---------------- build the print data: PJL header + PDF/PS ----------------
function Build-Payload([string]$File, [string]$JobName) {
    $bytes = [System.IO.File]::ReadAllBytes($File)
    if ($bytes.Length -eq 0) { throw "File is empty (0 bytes)" }
    $header = [Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(8, $bytes.Length))
    $isJpeg = ($bytes.Length -gt 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8)
    $format = if ($header.StartsWith("%PDF")) { "PDF" } elseif ($header.StartsWith("%!PS")) { "PostScript" } elseif ($isJpeg) { "JPEG" } else { "UNKNOWN" }
    Log "FILE" "Read $($bytes.Length) bytes, detected format=$format" "OK"

    if ($format -eq "JPEG") {
        $bytes = Convert-JpegToPostScript $File $bytes
        Log "CONVERT" "Wrapped JPEG in PostScript page ($($bytes.Length) bytes)" "OK"
    } elseif ($format -eq "UNKNOWN") {
        throw "Unsupported format (send PDF, PostScript or JPEG) - the Fiery would silently discard it"
    }

    $lang = if ($format -eq "PDF") { "PDF" } else { "POSTSCRIPT" }
    $esc  = [char]27
    $user = $env:USERNAME
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("$esc%-12345X@PJL`r`n")
    [void]$sb.Append("@PJL JOB NAME=`"$JobName`"`r`n")
    [void]$sb.Append("@PJL SET USERNAME=`"$user`"`r`n")
    foreach ($line in @($job.pjl)) {
        if ($line) { [void]$sb.Append("$line`r`n"); Log "PJL" $line }
    }
    [void]$sb.Append("@PJL ENTER LANGUAGE=$lang`r`n")
    $pjlHead = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $pjlTail = [Text.Encoding]::UTF8.GetBytes("$esc%-12345X@PJL EOJ NAME=`"$JobName`"`r`n$esc%-12345X")

    $ms = New-Object System.IO.MemoryStream
    $ms.Write($pjlHead, 0, $pjlHead.Length); $ms.Write($bytes, 0, $bytes.Length); $ms.Write($pjlTail, 0, $pjlTail.Length)
    return ,$ms.ToArray()
}

function Get-InnerMessage([Exception]$E) {
    while ($E.InnerException) { $E = $E.InnerException }
    return $E.Message
}

function Connect-Fiery([string]$TargetHost, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $connected = $client.ConnectAsync($TargetHost, $Port).Wait([int]$job.connectTimeoutSeconds * 1000)
    } catch {
        $client.Close(); throw "Connect to ${TargetHost}:$Port failed: $(Get-InnerMessage $_.Exception)"
    }
    if (-not $connected) {
        $client.Close(); throw "Timed out after $($job.connectTimeoutSeconds)s connecting to ${TargetHost}:$Port"
    }
    Log "CONNECT" "Connected to ${TargetHost}:$Port in $($sw.ElapsedMilliseconds) ms" "OK"
    $client.SendTimeout = [int]$job.ackTimeoutSeconds * 1000
    return $client
}

function Write-Chunked($Stream, [byte[]]$Data) {
    $chunk = 65536; $sent = 0
    while ($sent -lt $Data.Length) {
        $n = [Math]::Min($chunk, $Data.Length - $sent)
        $Stream.Write($Data, $sent, $n)
        $sent += $n
    }
    $Stream.Flush()
}

# ---------------- LPR / LPD (RFC 1179) ----------------
function Read-Ack($Stream, [string]$Step) {
    try { $b = $Stream.ReadByte() }
    catch { throw "No acknowledgement from Fiery for '$Step' within $($job.ackTimeoutSeconds)s ($(Get-InnerMessage $_.Exception))" }
    if ($b -eq -1) { throw "Fiery closed the connection during '$Step'" }
    if ($b -ne 0)  { throw "Fiery rejected '$Step' (LPD code $b). Check the queue name '$($job.queue)' is published on the Fiery." }
    Log "ACK" "Fiery acknowledged $Step" "OK"
}

function Send-Lpr([byte[]]$Data, [string]$JobName, [string]$FileName) {
    $ascii   = [Text.Encoding]::ASCII
    $STX     = [string][char]2
    $ETX     = [string][char]3
    $lpdHost = ($env:COMPUTERNAME -replace '[^A-Za-z0-9\-]', '')
    if ($lpdHost.Length -gt 31) { $lpdHost = $lpdHost.Substring(0, 31) }
    $user    = if ($env:USERNAME.Length -gt 31) { $env:USERNAME.Substring(0, 31) } else { $env:USERNAME }
    $jobNo   = '{0:D3}' -f (Get-Random -Minimum 0 -Maximum 1000)
    $dfName  = "dfA$jobNo$lpdHost"
    $cfName  = "cfA$jobNo$lpdHost"
    $jName   = if ($JobName.Length -gt 99) { $JobName.Substring(0, 99) } else { $JobName }
    $nName   = if ($FileName.Length -gt 131) { $FileName.Substring(0, 131) } else { $FileName }
    # l = print literally (binary data, no filtering)
    $control = $ascii.GetBytes("H$lpdHost`nP$user`nJ$jName`nN$nName`nl$dfName`nU$dfName`n")

    $client = Connect-Fiery $job.host ([int]$job.port)
    try {
        $stream = $client.GetStream()
        $stream.ReadTimeout = [int]$job.ackTimeoutSeconds * 1000

        $cmd = $ascii.GetBytes("$STX$($job.queue)`n")
        $stream.Write($cmd, 0, $cmd.Length); Read-Ack $stream "queue '$($job.queue)'"

        $cmd = $ascii.GetBytes("$ETX$($Data.Length) $dfName`n")
        $stream.Write($cmd, 0, $cmd.Length); Read-Ack $stream "data file header ($($Data.Length) bytes)"
        Write-Chunked $stream $Data
        $stream.WriteByte(0); Read-Ack $stream "data file content"

        $cmd = $ascii.GetBytes("$STX$($control.Length) $cfName`n")
        $stream.Write($cmd, 0, $cmd.Length); Read-Ack $stream "control file header"
        $stream.Write($control, 0, $control.Length)
        $stream.WriteByte(0); Read-Ack $stream "control file (job $jobNo)"
        return "Fiery accepted job $jobNo into queue '$($job.queue)'"
    } finally {
        $client.Close()
    }
}

# ---------------- RAW port 9100 (from fiery-send-debug.ps1) ----------------
function Send-Raw([byte[]]$Data) {
    $client = Connect-Fiery $job.host ([int]$job.port)
    try {
        $stream = $client.GetStream()
        Write-Chunked $stream $Data
        Log "SEND" "Wrote $($Data.Length) bytes to socket" "OK"
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
        $stream.ReadTimeout = [int]$job.ackTimeoutSeconds * 1000
        $buf = New-Object byte[] 4096
        try { $r = $stream.Read($buf, 0, $buf.Length) }
        catch { throw "Fiery did not confirm (close) within $($job.ackTimeoutSeconds)s ($(Get-InnerMessage $_.Exception))" }
        if ($r -eq 0) { return "Fiery received all $($Data.Length) bytes on port $($job.port)" }
        $reply = [Text.Encoding]::ASCII.GetString($buf, 0, $r)
        throw "Unexpected reply from Fiery: $reply"
    } finally {
        $client.Close()
    }
}

# ---------------- main ----------------
$fileName = [System.IO.Path]::GetFileName($job.file)
Log "START" "---- $fileName -> $($job.method) $($job.host):$($job.port) queue=$($job.queue) ----"
Log "JOB" "name='$($job.jobName)' copies=$($job.copies) test=$($job.testPrint) media='$($job.media)' finishing='$($job.finishing)' customer='$($job.customerName)' instructions='$($job.instructions)'"

if (-not (Test-Path -LiteralPath $job.file)) { Finish $false "File not found: $($job.file)" }

try {
    $payload = Build-Payload $job.file $job.jobName
    if ($job.method -eq 'RAW') { $msg = Send-Raw $payload }
    else { $msg = Send-Lpr $payload $job.jobName $fileName }
    Finish $true $msg
} catch {
    Finish $false $_.Exception.Message
}
