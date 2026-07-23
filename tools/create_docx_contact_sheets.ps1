param(
    [Parameter(Mandatory = $true)]
    [string]$InputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [int]$PagesPerSheet = 5
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$inputPath = (Resolve-Path $InputDirectory).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$outputPath = (Resolve-Path $OutputDirectory).Path
$pages = @(Get-ChildItem -LiteralPath $inputPath -Filter "page-*.png" | Sort-Object Name)
if ($pages.Count -eq 0) {
    throw "No rendered pages found in $inputPath"
}

$thumbWidth = 360
$thumbHeight = 510
$gap = 20
$labelHeight = 28
$columns = 2
$rows = [Math]::Ceiling($PagesPerSheet / $columns)
$sheetWidth = ($columns * $thumbWidth) + (($columns + 1) * $gap)
$sheetHeight = ($rows * ($thumbHeight + $labelHeight)) + (($rows + 1) * $gap)
$font = New-Object System.Drawing.Font("Segoe UI", 11, [System.Drawing.FontStyle]::Bold)

try {
    for ($start = 0; $start -lt $pages.Count; $start += $PagesPerSheet) {
        $sheetNumber = [int]($start / $PagesPerSheet) + 1
        $bitmap = New-Object System.Drawing.Bitmap($sheetWidth, $sheetHeight)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.Clear([System.Drawing.Color]::FromArgb(232, 236, 241))
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                for ($offset = 0; $offset -lt $PagesPerSheet -and ($start + $offset) -lt $pages.Count; $offset++) {
                    $pageFile = $pages[$start + $offset]
                    $column = $offset % $columns
                    $row = [Math]::Floor($offset / $columns)
                    $x = $gap + ($column * ($thumbWidth + $gap))
                    $y = $gap + ($row * ($thumbHeight + $labelHeight + $gap))
                    $source = [System.Drawing.Image]::FromFile($pageFile.FullName)
                    try {
                        $ratio = [Math]::Min($thumbWidth / $source.Width, $thumbHeight / $source.Height)
                        $drawWidth = [int]($source.Width * $ratio)
                        $drawHeight = [int]($source.Height * $ratio)
                        $drawX = $x + [int](($thumbWidth - $drawWidth) / 2)
                        $drawY = $y + [int](($thumbHeight - $drawHeight) / 2)
                        $graphics.FillRectangle([System.Drawing.Brushes]::White, $x, $y, $thumbWidth, $thumbHeight)
                        $graphics.DrawImage($source, $drawX, $drawY, $drawWidth, $drawHeight)
                        $graphics.DrawRectangle([System.Drawing.Pens]::Gray, $x, $y, $thumbWidth, $thumbHeight)
                        $graphics.DrawString($pageFile.BaseName, $font, [System.Drawing.Brushes]::Black, $x, $y + $thumbHeight + 3)
                    }
                    finally {
                        $source.Dispose()
                    }
                }
            }
            finally {
                $graphics.Dispose()
            }
            $sheetPath = Join-Path $outputPath ("sheet-{0:D2}.png" -f $sheetNumber)
            $bitmap.Save($sheetPath, [System.Drawing.Imaging.ImageFormat]::Png)
            Write-Output $sheetPath
        }
        finally {
            $bitmap.Dispose()
        }
    }
}
finally {
    $font.Dispose()
}
