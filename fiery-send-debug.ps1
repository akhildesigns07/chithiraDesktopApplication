# fiery-send-debug.ps1
# Sends a file to a Fiery over raw port 9100, logging every step so you can see where it fails.
# Run:  powershell -ExecutionPolicy Bypass -File .\fiery-send-debug.ps1 -Ip 192.168.1.50 -File C:\jobs\invoice.pdf

param(
    [string]$Ip      = "192.168.1.50",
    [string]$File    = "C:\jobs\invoice.pdf",
    [int]   $Port    = 9100,
    [string]$LogFile = "C:\PrintLog\fiery_send.log"
)

New-Item -ItemType Directory -Force -Path (Split-Path $LogFile) | Out-Null

function Log([string]$Step, [string]$Msg, [string]$Level = "INFO") {
    $line = "{0:yyyy-MM-dd HH:mm:ss.fff} [{1,-5}] [{2,-8}] {3}" -f (Get-Date), $Level, $Step, $Msg
    Add-Content -Path $LogFile -Value $line
    $color = switch ($Level) { "ERROR" { "Red" } "WARN" { "Yellow" } "OK" { "Green" } default { "Gray" } }
    Write-Host $line -ForegroundColor $color
}

function Convert-JpegToPostScript([string]$Path, [byte[]]$Jpeg) {
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($Path)
    $w = $img.Width; $h = $img.Height; $pf = "$($img.PixelFormat)"
    $img.Dispose()

    # Color space from the JPEG's pixel format
    if ($pf -match 'Cmyk')           { $cs = '/DeviceCMYK'; $dec = '[1 0 1 0 1 0 1 0]' }
    elseif ($pf -eq 'Format8bppIndexed') { $cs = '/DeviceGray'; $dec = '[0 1]' }
    else                             { $cs = '/DeviceRGB';  $dec = '[0 1 0 1 0 1]' }

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

function Send-ToFiery($Ip, $File, $Port) {
    Log "START" "---- New send: file=$File target=${Ip}:$Port ----"

    # STEP 1: file checks
    if (-not (Test-Path $File)) { Log "FILE" "File not found: $File" "ERROR"; return $false }
    $bytes = [System.IO.File]::ReadAllBytes($File)
    if ($bytes.Length -eq 0) { Log "FILE" "File is empty (0 bytes)" "ERROR"; return $false }
    $header = [Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(8, $bytes.Length))
    $isJpeg = ($bytes.Length -gt 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8)
    $format = if ($header.StartsWith("%PDF")) { "PDF" } elseif ($header.StartsWith("%!PS")) { "PostScript" } elseif ($isJpeg) { "JPEG" } else { "UNKNOWN" }
    Log "FILE" "Read $($bytes.Length) bytes, detected format=$format" "OK"

    # STEP 1b: JPEG is not a print language, so wrap it in a one-page PostScript job
    if ($format -eq "JPEG") {
        try {
            $bytes = Convert-JpegToPostScript $File $bytes
            Log "CONVERT" "Wrapped JPEG in PostScript page ($($bytes.Length) bytes)" "OK"
        } catch {
            Log "CONVERT" "JPEG to PostScript conversion failed: $($_.Exception.Message)" "ERROR"; return $false
        }
    } elseif ($format -eq "UNKNOWN") {
        Log "FILE" "Fiery will silently discard this format on port 9100 (send PDF, PostScript or JPEG)" "ERROR"; return $false
    }

    # STEP 1c: wrap in PJL so Fiery gets job name, user and the data language
    $lang  = if ($format -eq "PDF") { "PDF" } else { "POSTSCRIPT" }
    $esc   = [char]27
    $jname = [System.IO.Path]::GetFileName($File)
    $juser = $env:USERNAME
    $pjlHead = [Text.Encoding]::ASCII.GetBytes("$esc%-12345X@PJL`r`n@PJL JOB NAME=`"$jname`"`r`n@PJL SET USERNAME=`"$juser`"`r`n@PJL ENTER LANGUAGE=$lang`r`n")
    $pjlTail = [Text.Encoding]::ASCII.GetBytes("$esc%-12345X@PJL EOJ NAME=`"$jname`"`r`n$esc%-12345X")
    $ms = New-Object System.IO.MemoryStream
    $ms.Write($pjlHead, 0, $pjlHead.Length); $ms.Write($bytes, 0, $bytes.Length); $ms.Write($pjlTail, 0, $pjlTail.Length)
    $bytes = $ms.ToArray()
    Log "PJL" "Added PJL header: name=$jname user=$juser language=$lang (total $($bytes.Length) bytes)" "OK"

    # STEP 2: reachability (ping may be blocked on Fiery, so only a warning)
    if (Test-Connection -ComputerName $Ip -Count 1 -Quiet) { Log "NETWORK" "Ping to $Ip OK" "OK" }
    else { Log "NETWORK" "Ping to $Ip failed (may just be blocked; continuing)" "WARN" }

    # STEP 3: TCP connect with timeout
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        if (-not $client.ConnectAsync($Ip, $Port).Wait(5000)) {
            Log "CONNECT" "Timed out after 5s connecting to ${Ip}:$Port" "ERROR"; return $false
        }
        Log "CONNECT" "Connected in $($sw.ElapsedMilliseconds) ms (local $($client.Client.LocalEndPoint) -> remote $($client.Client.RemoteEndPoint))" "OK"
    } catch {
        Log "CONNECT" "Connect failed: $($_.Exception.InnerException.Message)" "ERROR"; return $false
    }

    $stream = $client.GetStream()
    try {
        # STEP 4: send in chunks so progress is visible
        $chunk = 65536; $sent = 0
        while ($sent -lt $bytes.Length) {
            $n = [Math]::Min($chunk, $bytes.Length - $sent)
            $stream.Write($bytes, $sent, $n)
            $sent += $n
        }
        $stream.Flush()
        Log "SEND" "Wrote $sent / $($bytes.Length) bytes to socket" "OK"

        # STEP 5: signal end of job (instead of an abrupt Close that can discard data)
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
        Log "SHUTDOWN" "Sent end-of-data (FIN) to Fiery" "OK"

        # STEP 6: wait for Fiery to finish reading / reply / close its side
        $stream.ReadTimeout = 10000
        $buf = New-Object byte[] 4096
        try {
            $r = $stream.Read($buf, 0, $buf.Length)
            if ($r -eq 0) {
                Log "RESPONSE" "Fiery closed the connection after receiving data (normal = job accepted by port)" "OK"
            } else {
                $reply = [Text.Encoding]::ASCII.GetString($buf, 0, $r)
                Log "RESPONSE" "Fiery replied ($r bytes): $reply" "WARN"
            }
        } catch [System.IO.IOException] {
            Log "RESPONSE" "No reply or close from Fiery within 10s (it may still be processing)" "WARN"
        }
    } catch {
        Log "SEND" "Error during send: $($_.Exception.Message)" "ERROR"; return $false
    } finally {
        $stream.Close(); $client.Close()
        Log "CLOSE" "Socket closed"
    }

    Log "DONE" "Transfer finished. Now check Command WorkStation: Hold, Printing, Printed, Job Log." "OK"
    return $true
}

Send-ToFiery $Ip $File $Port | Out-Null