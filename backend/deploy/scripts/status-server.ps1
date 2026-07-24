$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$composeFile = Join-Path $projectRoot 'compose.yml'

Push-Location $projectRoot
try {
    docker compose -f $composeFile ps
    Write-Output ''
    Write-Output 'Public gateway:'
    curl.exe --noproxy '*' -sS -o NUL -w 'HTTP %{http_code} %{time_total}s' `
        --max-time 30 https://ai-search.tsong.xyz/
    Write-Output ''
    Write-Output 'Elasticsearch documents:'
    docker exec ai-search-next-es curl -fsS http://localhost:9200/products_v2/_count
    Write-Output ''
    Write-Output 'NER runtime:'
    curl.exe --noproxy '*' -sS http://127.0.0.1:18080/api/admin/ner/status
} finally {
    Pop-Location
}
