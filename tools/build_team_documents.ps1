param(
    [string]$ProjectRoot = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)),

    [ValidateSet("All", "Meeting", "Developer", "Product")]
    [string]$Only = "All"
)

$ErrorActionPreference = "Stop"

$pandoc = Get-Command pandoc -ErrorAction Stop
$outputDir = Join-Path $ProjectRoot "docs/deliverables"
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-mall-docs-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

function Get-WordColor([int]$red, [int]$green, [int]$blue) {
    return $red + (256 * $green) + (65536 * $blue)
}

function Set-StyleFont($style, [string]$name, [double]$size, [int]$color, [bool]$bold) {
    $style.Font.Name = $name
    try { $style.Font.NameFarEast = $name } catch {}
    $style.Font.Size = $size
    $style.Font.Color = $color
    $style.Font.Bold = if ($bold) { -1 } else { 0 }
}

function Set-DocumentStyles($doc, [string]$documentTitle, [string]$documentKind) {
    # compact_reference_guide preset: Letter portrait, 1-inch margins,
    # compact body, blue hierarchy, dense tables and restrained accents.
    $navy = Get-WordColor 24 48 79
    $blue = Get-WordColor 46 116 181
    $darkBlue = Get-WordColor 31 78 121
    $body = Get-WordColor 36 52 71
    $muted = Get-WordColor 91 105 120
    $white = Get-WordColor 255 255 255
    $lightBlue = Get-WordColor 232 238 245
    $calloutFill = Get-WordColor 244 246 249
    $stripe = Get-WordColor 246 249 252
    $border = Get-WordColor 199 211 223
    $codeBackground = Get-WordColor 245 247 249

    $sectionNumber = 0
    foreach ($section in $doc.Sections) {
        $sectionNumber++
        $section.PageSetup.PageWidth = 612
        $section.PageSetup.PageHeight = 792
        $section.PageSetup.Orientation = 0
        $section.PageSetup.TopMargin = 72
        $section.PageSetup.BottomMargin = 72
        $section.PageSetup.LeftMargin = 72
        $section.PageSetup.RightMargin = 72
        $section.PageSetup.HeaderDistance = 35.4
        $section.PageSetup.FooterDistance = 35.4
        $section.PageSetup.OddAndEvenPagesHeaderFooter = 0
        $section.PageSetup.DifferentFirstPageHeaderFooter = 0

        $header = $section.Headers.Item(1).Range
        $header.Text = "AI 商城智能搜索  ·  $documentKind"
        $header.Font.Name = "Microsoft YaHei"
        try { $header.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $header.Font.Size = 8
        $header.Font.Color = $muted
        $header.ParagraphFormat.Alignment = 0
        $header.Borders.Item(-3).LineStyle = 1
        $header.Borders.Item(-3).Color = $border
        $header.Borders.Item(-3).LineWidth = 2

        $evenHeader = $section.Headers.Item(3).Range
        $evenHeader.Text = "AI 商城智能搜索  ·  $documentKind"
        $evenHeader.Font.Name = "Microsoft YaHei"
        try { $evenHeader.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $evenHeader.Font.Size = 8
        $evenHeader.Font.Color = $muted
        $evenHeader.ParagraphFormat.Alignment = 0
        $evenHeader.Borders.Item(-3).LineStyle = 1
        $evenHeader.Borders.Item(-3).Color = $border
        $evenHeader.Borders.Item(-3).LineWidth = 2

        if ($sectionNumber -eq 1) {
            $firstHeader = $section.Headers.Item(2).Range
            $firstHeader.Text = ""
        }

        $footer = $section.Footers.Item(1).Range
        $footer.Text = "内部协作材料  |  V1.0  |  "
        $footer.Font.Name = "Microsoft YaHei"
        try { $footer.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $footer.Font.Size = 8
        $footer.Font.Color = $muted
        $footer.ParagraphFormat.Alignment = 2
        $fieldRange = $footer.Duplicate
        $fieldRange.Collapse(0)
        [void]$footer.Fields.Add($fieldRange, 33)

        $evenFooter = $section.Footers.Item(3).Range
        $evenFooter.Text = "内部协作材料  |  V1.0  |  "
        $evenFooter.Font.Name = "Microsoft YaHei"
        try { $evenFooter.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $evenFooter.Font.Size = 8
        $evenFooter.Font.Color = $muted
        $evenFooter.ParagraphFormat.Alignment = 2
        $evenFieldRange = $evenFooter.Duplicate
        $evenFieldRange.Collapse(0)
        [void]$evenFooter.Fields.Add($evenFieldRange, 33)

        if ($sectionNumber -eq 1) {
            $firstFooter = $section.Footers.Item(2).Range
            $firstFooter.Text = "内部协作材料  |  $documentTitle"
            $firstFooter.Font.Name = "Microsoft YaHei"
            try { $firstFooter.Font.NameFarEast = "Microsoft YaHei" } catch {}
            $firstFooter.Font.Size = 8
            $firstFooter.Font.Color = $muted
            $firstFooter.ParagraphFormat.Alignment = 2
        }
    }

    $normal = $doc.Styles.Item(-1)
    Set-StyleFont $normal "Microsoft YaHei" 11 $body $false
    $normal.ParagraphFormat.SpaceBefore = 0
    $normal.ParagraphFormat.SpaceAfter = 6
    $normal.ParagraphFormat.LineSpacingRule = 5
    $normal.ParagraphFormat.LineSpacing = 15
    $normal.ParagraphFormat.WidowControl = -1

    $title = $doc.Styles.Item(-63)
    Set-StyleFont $title "Microsoft YaHei" 28 $navy $true
    $title.ParagraphFormat.Alignment = 0
    $title.ParagraphFormat.SpaceBefore = 0
    $title.ParagraphFormat.SpaceAfter = 16
    $title.ParagraphFormat.KeepWithNext = -1

    $heading1 = $doc.Styles.Item(-2)
    Set-StyleFont $heading1 "Microsoft YaHei" 16 $blue $true
    $heading1.ParagraphFormat.SpaceBefore = 18
    $heading1.ParagraphFormat.SpaceAfter = 10
    $heading1.ParagraphFormat.KeepWithNext = -1
    $heading1.ParagraphFormat.KeepTogether = -1

    $heading2 = $doc.Styles.Item(-3)
    Set-StyleFont $heading2 "Microsoft YaHei" 13 $blue $true
    $heading2.ParagraphFormat.SpaceBefore = 14
    $heading2.ParagraphFormat.SpaceAfter = 7
    $heading2.ParagraphFormat.KeepWithNext = -1
    $heading2.ParagraphFormat.KeepTogether = -1

    $heading3 = $doc.Styles.Item(-4)
    Set-StyleFont $heading3 "Microsoft YaHei" 12 $darkBlue $true
    $heading3.ParagraphFormat.SpaceBefore = 10
    $heading3.ParagraphFormat.SpaceAfter = 5
    $heading3.ParagraphFormat.KeepWithNext = -1
    $heading3.ParagraphFormat.KeepTogether = -1

    # The first four paragraphs are inserted as a dedicated cover before the TOC.
    if ($doc.Paragraphs.Count -gt 0) {
        $doc.Paragraphs.Item(1).Range.Style = $title
        $doc.Paragraphs.Item(1).Range.ParagraphFormat.SpaceAfter = 16
        $doc.Paragraphs.Item(1).Range.Borders.Item(-3).LineStyle = 1
        $doc.Paragraphs.Item(1).Range.Borders.Item(-3).Color = $blue
        $doc.Paragraphs.Item(1).Range.Borders.Item(-3).LineWidth = 18
    }
    if ($doc.Paragraphs.Count -ge 2) {
        $coverSubtitle = $doc.Paragraphs.Item(2).Range
        $coverSubtitle.Font.Name = "Microsoft YaHei"
        try { $coverSubtitle.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $coverSubtitle.Font.Size = 17
        $coverSubtitle.Font.Bold = -1
        $coverSubtitle.Font.Color = $blue
        $coverSubtitle.ParagraphFormat.SpaceAfter = 12
    }
    if ($doc.Paragraphs.Count -ge 3) {
        $coverMeta = $doc.Paragraphs.Item(3).Range
        $coverMeta.Font.Name = "Microsoft YaHei"
        try { $coverMeta.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $coverMeta.Font.Size = 10
        $coverMeta.Font.Color = $muted
        $coverMeta.ParagraphFormat.SpaceAfter = 18
    }
    if ($doc.Paragraphs.Count -ge 4) {
        $coverPurpose = $doc.Paragraphs.Item(4).Range
        $coverPurpose.Font.Name = "Microsoft YaHei"
        try { $coverPurpose.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $coverPurpose.Font.Size = 11.5
        $coverPurpose.Font.Color = $body
        $coverPurpose.ParagraphFormat.LeftIndent = 12
        $coverPurpose.ParagraphFormat.RightIndent = 12
        $coverPurpose.ParagraphFormat.SpaceBefore = 8
        $coverPurpose.ParagraphFormat.SpaceAfter = 8
        $coverPurpose.Shading.BackgroundPatternColor = $calloutFill
        $coverPurpose.Borders.Item(-2).LineStyle = 1
        $coverPurpose.Borders.Item(-2).Color = $blue
        $coverPurpose.Borders.Item(-2).LineWidth = 18
    }

    foreach ($paragraph in $doc.Paragraphs) {
        $range = $paragraph.Range
        $range.Font.Name = "Microsoft YaHei"
        try { $range.Font.NameFarEast = "Microsoft YaHei" } catch {}
        $paragraph.Format.WidowControl = -1

        if ($range.ListFormat.ListType -ne 0) {
            $paragraph.Format.LeftIndent = 27
            $paragraph.Format.FirstLineIndent = -13.5
            $paragraph.Format.SpaceAfter = 4
            $paragraph.Format.LineSpacingRule = 5
            $paragraph.Format.LineSpacing = 15
        }

        $styleName = ""
        try { $styleName = [string]$range.Style.NameLocal } catch {}
        if ($range.Text.Trim() -eq "Table of Contents") {
            $range.Text = "目录`r"
            $range.Font.Name = "Microsoft YaHei"
            try { $range.Font.NameFarEast = "Microsoft YaHei" } catch {}
            $range.Font.Size = 16
            $range.Font.Bold = -1
            $range.Font.Color = $blue
        }
        if ($styleName -match "Source|Verbatim|代码|HTML") {
            $range.Font.Name = "Consolas"
            try { $range.Font.NameFarEast = "Microsoft YaHei" } catch {}
            $range.Font.Size = 8.5
            $paragraph.Format.LeftIndent = 10
            $paragraph.Format.RightIndent = 10
            $paragraph.Format.SpaceBefore = 4
            $paragraph.Format.SpaceAfter = 6
            $range.Shading.BackgroundPatternColor = $codeBackground
            $range.Borders.OutsideLineStyle = 1
            $range.Borders.OutsideColor = $border
        }
    }

    # Style the metadata block directly under the title as a quiet agenda/cover panel.
    for ($index = 2; $index -le [Math]::Min(7, $doc.Paragraphs.Count); $index++) {
        $paragraph = $doc.Paragraphs.Item($index)
        $text = $paragraph.Range.Text.Trim()
        if ($text -match "版本：|基线日期：|参会角色：|会议目标：|适用对象：|约束：") {
            $paragraph.Range.Font.Color = $muted
            $paragraph.Range.Font.Size = 9.5
            $paragraph.Format.LeftIndent = 10
            $paragraph.Format.SpaceAfter = 3
            $paragraph.Range.Shading.BackgroundPatternColor = $lightBlue
            $paragraph.Range.Borders.Item(-2).LineStyle = 1
            $paragraph.Range.Borders.Item(-2).Color = $blue
            $paragraph.Range.Borders.Item(-2).LineWidth = 12
        }
    }

    foreach ($table in $doc.Tables) {
        $table.AllowAutoFit = 0
        $table.PreferredWidthType = 3
        $table.PreferredWidth = 468
        $table.Rows.Alignment = 0
        try { $table.Rows.LeftIndent = 6 } catch {}
        $table.LeftPadding = 6
        $table.RightPadding = 6
        $table.TopPadding = 4
        $table.BottomPadding = 4
        $table.Borders.InsideLineStyle = 1
        $table.Borders.OutsideLineStyle = 1
        $table.Borders.InsideColor = $border
        $table.Borders.OutsideColor = $border
        try { $table.Rows.AllowBreakAcrossPages = 0 } catch {}

        if ($table.Rows.Count -ge 1) {
            $headerRow = $table.Rows.Item(1)
            $headerRow.HeadingFormat = -1
            $headerRow.Range.Shading.BackgroundPatternColor = $lightBlue
            $headerRow.Range.Font.Color = $darkBlue
            $headerRow.Range.Font.Bold = -1
            $headerRow.Range.Font.Size = 9
        }

        for ($rowIndex = 2; $rowIndex -le $table.Rows.Count; $rowIndex++) {
            $row = $table.Rows.Item($rowIndex)
            $row.Range.Font.Size = 8.5
            $row.Range.Shading.BackgroundPatternColor = $white
        }

        $columnCount = $table.Columns.Count
        $columnWidths = switch ($columnCount) {
            1 { @(468) }
            2 { @(135, 333) }
            3 { @(108, 180, 180) }
            4 { @(54, 108, 188, 118) }
            5 { @(72, 96, 96, 108, 96) }
            6 { @(78, 102, 56, 74, 96, 62) }
            default { @() }
        }
        if ($columnWidths.Count -eq $columnCount) {
            for ($columnIndex = 1; $columnIndex -le $columnCount; $columnIndex++) {
                $table.Columns.Item($columnIndex).Width = $columnWidths[$columnIndex - 1]
            }
        }

        foreach ($cell in $table.Range.Cells) {
            $cell.VerticalAlignment = 1
            $cell.Range.ParagraphFormat.SpaceAfter = 2
            $cell.Range.ParagraphFormat.LineSpacingRule = 0
        }
    }

    foreach ($hyperlink in $doc.Hyperlinks) {
        $hyperlink.Range.Font.Color = $blue
        $hyperlink.Range.Font.Underline = 1
    }

    try {
        ($doc.BuiltInDocumentProperties.Item("Title")).Value = $documentTitle
        ($doc.BuiltInDocumentProperties.Item("Subject")).Value = "AI 商城智能搜索项目内部协作材料"
        ($doc.BuiltInDocumentProperties.Item("Author")).Value = "AI 商城智能搜索项目组"
    } catch {}

    foreach ($toc in $doc.TablesOfContents) {
        $toc.Update()
    }
    $doc.Fields.Update() | Out-Null
}

function Build-Document(
    [string]$sourceRelative,
    [string]$outputName,
    [string]$documentTitle,
    [string]$documentKind
) {
    $sourcePath = Join-Path $ProjectRoot $sourceRelative
    $pandocSourcePath = Join-Path $tempDir ($outputName + ".md")
    $intermediatePath = Join-Path $tempDir ($outputName + ".raw.docx")
    $outputPath = Join-Path $outputDir ($outputName + ".docx")

    # The Word cover supplies title/metadata. Strip only the Markdown title and
    # its leading quote metadata, leaving the complete numbered body unchanged.
    $sourceLines = @(Get-Content -LiteralPath $sourcePath)
    $bodyStart = 0
    if ($sourceLines.Count -gt 0 -and $sourceLines[0] -match '^#\s') {
        $bodyStart = 1
        while ($bodyStart -lt $sourceLines.Count -and
               ([string]::IsNullOrWhiteSpace($sourceLines[$bodyStart]) -or $sourceLines[$bodyStart] -match '^>')) {
            $bodyStart++
        }
    }
    $sourceBody = if ($bodyStart -lt $sourceLines.Count) { $sourceLines[$bodyStart..($sourceLines.Count - 1)] } else { @() }
    Set-Content -LiteralPath $pandocSourcePath -Value $sourceBody -Encoding utf8

    & $pandoc.Source $pandocSourcePath `
        --from=gfm `
        --to=docx `
        --standalone `
        --toc `
        --toc-depth=2 `
        --metadata=lang:zh-CN `
        --output=$intermediatePath
    if ($LASTEXITCODE -ne 0) {
        throw "Pandoc failed for $sourcePath"
    }

    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    try {
        $doc = $word.Documents.Open($intermediatePath)
        $coverDetail = switch ($documentKind) {
            "会议版" { "5 名开发 · 3 名产品 · 60 分钟会议材料 —— 统一项目流程、当前边界、岗位选择、首个改进点与两周验收" }
            "产品手册" { "3 名产品负责人 —— 从业务真值、Gold Query 到搜索体验、数据指标和版本验收的可填写工作手册" }
            default { "Java 8 · MySQL · Elasticsearch · NER · Redis —— 从本地启动到模块开发、测试、评测和交付的可执行指南" }
        }
        $coverSubtitle = switch ($documentKind) {
            "会议版" { "项目流程与团队分工 · 会议版" }
            "产品手册" { "产品团队工作手册" }
            default { "开发人员上手与改进手册" }
        }
        $coverRange = $doc.Range(0, 0)
        $coverRange.InsertBefore("AI 商城智能搜索`r$coverSubtitle`r基线日期：2026-07-22 · 版本：V1.0 · 内部协作材料`r$coverDetail`r`f")
        Set-DocumentStyles $doc $documentTitle $documentKind
        $doc.Repaginate()
        $doc.SaveAs2($outputPath, 16)
        $pdfPath = Join-Path $tempDir ($outputName + ".pdf")
        $doc.ExportAsFixedFormat($pdfPath, 17)
        $pageCount = $doc.ComputeStatistics(2)
        $doc.Close(0)
        [System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($doc) | Out-Null
        $doc = $null
        return [PSCustomObject]@{
            Docx = $outputPath
            Pdf = $pdfPath
            Pages = $pageCount
        }
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
}

try {
    if ($Only -eq "All" -or $Only -eq "Meeting") {
        $meeting = Build-Document `
            "docs/MEETING_PROJECT_FLOW_AND_ROLES.md" `
            "AI商城智能搜索-会议版项目流程与分工" `
            "AI 商城智能搜索：项目流程与团队分工" `
            "会议版"
        Write-Output ("MEETING_DOCX=" + $meeting.Docx)
        Write-Output ("MEETING_PDF=" + $meeting.Pdf)
        Write-Output ("MEETING_PAGES=" + $meeting.Pages)
    }

    if ($Only -eq "All" -or $Only -eq "Developer") {
        $developer = Build-Document `
            "docs/DEVELOPER_LOCAL_GUIDE.md" `
            "AI商城智能搜索-开发人员上手与改进手册" `
            "AI 商城智能搜索：开发人员上手与改进手册" `
            "开发手册"
        Write-Output ("DEVELOPER_DOCX=" + $developer.Docx)
        Write-Output ("DEVELOPER_PDF=" + $developer.Pdf)
        Write-Output ("DEVELOPER_PAGES=" + $developer.Pages)
    }

    if ($Only -eq "All" -or $Only -eq "Product") {
        $product = Build-Document `
            "docs/PRODUCT_TEAM_WORKBOOK.md" `
            "AI商城智能搜索-产品团队工作手册" `
            "AI 商城智能搜索：产品团队工作手册" `
            "产品手册"
        Write-Output ("PRODUCT_DOCX=" + $product.Docx)
        Write-Output ("PRODUCT_PDF=" + $product.Pdf)
        Write-Output ("PRODUCT_PAGES=" + $product.Pages)
    }
    Write-Output ("TEMP_DIR=" + $tempDir)
}
catch {
    Write-Error $_
    throw
}
