param([string[]]$Names)

$ErrorActionPreference = "Stop"
$outDir = "T:\Zfile\qishui\app\src\main\res\drawable"

# ��� PowerShell ������ -f ֻ֧��λ�ò�������ϣ��ռλ������ FormatError
$header = @(
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="24dp"',
    '    android:height="24dp"',
    '    android:viewportWidth="24"',
    '    android:viewportHeight="24">'
)
$stroke = 'android:strokeColor="#FF000000" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"'

function New-Path([string]$d) {
    '  <path android:pathData="' + $d + '" ' + $stroke + ' />'
}

foreach ($name in $Names) {
    $url = "https://unpkg.com/lucide-static@latest/icons/$name.svg"
    try {
        $svg = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 30).Content
    } catch {
        Write-Output "MISS $name :: $($_.Exception.Message)"
        continue
    }

    $shapes = New-Object System.Collections.Generic.List[string]
    foreach ($m in [regex]::Matches($svg, '<(path|circle|rect|line|polyline|polygon|ellipse)\s([^>]*?)>')) {
        $tag = $m.Groups[1].Value
        $attrs = $m.Groups[2].Value
        $d = [regex]::Match($attrs, 'd="([^"]+)"').Groups[1].Value
        $cx = [regex]::Match($attrs, 'cx="([^"]+)"').Groups[1].Value
        $cy = [regex]::Match($attrs, 'cy="([^"]+)"').Groups[1].Value
        $r = [regex]::Match($attrs, 'r="([^"]+)"').Groups[1].Value
        $x = [regex]::Match($attrs, 'x="([^"]+)"').Groups[1].Value
        $y = [regex]::Match($attrs, 'y="([^"]+)"').Groups[1].Value
        $w = [regex]::Match($attrs, 'width="([^"]+)"').Groups[1].Value
        $h = [regex]::Match($attrs, 'height="([^"]+)"').Groups[1].Value

        if ($tag -eq "path" -and $d) {
            $shapes.Add((New-Path $d))
        } elseif ($tag -eq "circle" -and $cx -and $cy -and $r) {
            # 同点两段弧画不出圆，必须走左右两个半圆
            $left = [double]$cx - [double]$r
            $right = [double]$cx + [double]$r
            $shapes.Add((New-Path ("M$left $cy A$r $r 0 1 0 $right $cy A$r $r 0 1 0 $left $cy Z")))
        } elseif ($tag -eq "rect" -and $x -ne "" -and $w) {
            $x2 = [double]$x + [double]$w
            $y2 = [double]$y + [double]$h
            $shapes.Add((New-Path ("M$x $y H$x2 V$y2 H$x Z")))
        } elseif ($tag -eq "line") {
            $g = [regex]::Match($attrs, 'x1="([^"]+)" y1="([^"]+)" x2="([^"]+)" y2="([^"]+)"')
            if ($g.Success) {
                $shapes.Add((New-Path ("M" + $g.Groups[1].Value + " " + $g.Groups[2].Value + " L" + $g.Groups[3].Value + " " + $g.Groups[4].Value)))
            }
        } elseif ($tag -eq "polyline" -or $tag -eq "polygon") {
            $pts = [regex]::Match($attrs, 'points="([^"]+)"').Groups[1].Value
            if ($pts) {
                $d2 = "M" + ($pts -split '\s+' -join ' L')
                if ($tag -eq "polygon") { $d2 = $d2 + " Z" }
                $shapes.Add((New-Path $d2))
            }
        }
    }

    if ($shapes.Count -eq 0) {
        Write-Output "EMPTY $name"
        continue
    }
    $xml = ($header + $shapes + '</vector>') -join "`n"
    Set-Content -Path (Join-Path $outDir ("ic_" + ($name -replace "-", "_") + ".xml")) -Value $xml -Encoding UTF8
    Write-Output "OK $name ($($shapes.Count))"
}

