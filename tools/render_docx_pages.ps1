param(
    [Parameter(Mandatory = $true)]
    [string]$DocxPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;

public static class ClipboardNative {
    [DllImport("user32.dll")]
    public static extern bool OpenClipboard(IntPtr hWndNewOwner);

    [DllImport("user32.dll")]
    public static extern bool CloseClipboard();

    [DllImport("user32.dll")]
    public static extern IntPtr GetClipboardData(uint uFormat);

    [DllImport("gdi32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr CopyEnhMetaFile(IntPtr hemfSrc, string lpszFile);

    [DllImport("gdi32.dll")]
    public static extern bool DeleteEnhMetaFile(IntPtr hemf);
}
"@

function Save-ClipboardMetafileAsPng([string]$outputPath, [int]$page) {
    $emfPath = [System.IO.Path]::ChangeExtension($outputPath, ".emf")
    $opened = $false
    for ($attempt = 0; $attempt -lt 10 -and -not $opened; $attempt++) {
        $opened = [ClipboardNative]::OpenClipboard([IntPtr]::Zero)
        if (-not $opened) { Start-Sleep -Milliseconds 100 }
    }
    if (-not $opened) {
        throw "Could not open clipboard for page $page"
    }
    try {
        $sourceHandle = [ClipboardNative]::GetClipboardData(14)
        if ($sourceHandle -eq [IntPtr]::Zero) {
            return $false
        }
        $copyHandle = [ClipboardNative]::CopyEnhMetaFile($sourceHandle, $emfPath)
        if ($copyHandle -eq [IntPtr]::Zero) {
            return $false
        }
        [void][ClipboardNative]::DeleteEnhMetaFile($copyHandle)
    }
    finally {
        [void][ClipboardNative]::CloseClipboard()
    }

    $metafile = New-Object System.Drawing.Imaging.Metafile($emfPath)
    try {
        $scale = 2
        $bitmap = New-Object System.Drawing.Bitmap([Math]::Max(1, $metafile.Width * $scale), [Math]::Max(1, $metafile.Height * $scale))
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.Clear([System.Drawing.Color]::White)
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.DrawImage($metafile, 0, 0, $bitmap.Width, $bitmap.Height)
            }
            finally {
                $graphics.Dispose()
            }
            $bitmap.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $bitmap.Dispose()
        }
    }
    finally {
        $metafile.Dispose()
        Remove-Item -LiteralPath $emfPath -Force -ErrorAction SilentlyContinue
    }
    return $true
}

$resolvedDocx = (Resolve-Path $DocxPath).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$resolvedOutput = (Resolve-Path $OutputDirectory).Path

$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
    $doc = $word.Documents.Open($resolvedDocx, $false, $true)
    $doc.Repaginate()
    $pageCount = $doc.ComputeStatistics(2)

    for ($page = 1; $page -le $pageCount; $page++) {
        [void]$word.Selection.GoTo(1, 1, $page)
        $pageRange = $word.Selection.Bookmarks.Item("\Page").Range
        $pageRange.CopyAsPicture()
        Start-Sleep -Milliseconds 250
        $image = [System.Windows.Forms.Clipboard]::GetImage()
        if ($null -eq $image) {
            $outputPath = Join-Path $resolvedOutput ("page-{0:D2}.png" -f $page)
            if (-not (Save-ClipboardMetafileAsPng $outputPath $page)) {
                throw "Clipboard did not contain a renderable image for page $page"
            }
            Write-Output $outputPath
            [System.Windows.Forms.Clipboard]::Clear()
            continue
        }
        try {
            $outputPath = Join-Path $resolvedOutput ("page-{0:D2}.png" -f $page)
            $image.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Output $outputPath
        }
        finally {
            $image.Dispose()
            [System.Windows.Forms.Clipboard]::Clear()
        }
    }
    $doc.Close(0)
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($doc) | Out-Null
    $doc = $null
}
finally {
    if ($null -ne $doc) {
        try { $doc.Close(0) } catch {}
        try { [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($doc) | Out-Null } catch {}
    }
    $word.Quit()
    [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word) | Out-Null
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
