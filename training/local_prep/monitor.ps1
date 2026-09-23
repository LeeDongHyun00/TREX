# AI Hub 다운로드/변환 진행 상황 실시간 표시. 이 창을 닫아도 다운로드는 계속된다.
$ErrorActionPreference = 'SilentlyContinue'
$root = "C:\Workspace\TREX\aihub74_raw"
$sizes = @{ '11'=15; '04'=31; '06_07_08'=30; '09_10'=18 }
$prev = @{}
$Host.UI.RawUI.WindowTitle = "TREX 음식 데이터 진행 상황"

while ($true) {
  Clear-Host
  Write-Host "  TREX 음식 인식 데이터 준비" -ForegroundColor Cyan
  Write-Host "  $(Get-Date -Format 'MM-dd HH:mm:ss')   (이 창을 닫아도 작업은 계속됩니다)" -ForegroundColor DarkGray
  Write-Host ("  " + ("-" * 66)) -ForegroundColor DarkGray

  foreach ($name in '11','04','06_07_08','09_10') {
    $dir = Join-Path $root "images_$name"
    $exp = $sizes[$name]
    $line = "  {0,-12}" -f $name

    if (Test-Path (Join-Path $dir '.done')) {
      Write-Host ($line + "완료") -ForegroundColor Green
      continue
    }
    $tar = Join-Path $dir 'download.tar'
    if (Test-Path $tar) {
      $mb = (Get-Item $tar).Length / 1MB
      $pct = [math]::Min(100, [int]($mb / ($exp * 1024) * 100))
      $spd = ''
      if ($prev.ContainsKey($name)) { $d = $mb - $prev[$name]; if ($d -gt 0) { $spd = "  {0:N1} MB/s" -f ($d / 10) } }
      $prev[$name] = $mb
      $bar = ("#" * [int]($pct / 4)).PadRight(25, '.')
      Write-Host ($line + ("[{0}] {1,3}%  {2:N0}/{3:N0} MB{4}" -f $bar, $pct, $mb, ($exp*1024), $spd)) -ForegroundColor Yellow
    }
    elseif (Test-Path $dir) {
      $log = Get-Content (Join-Path $dir 'dl.log') -Tail 1
      Write-Host ($line + "대기/처리중  $log") -ForegroundColor DarkYellow
    }
    else { Write-Host ($line + "대기") -ForegroundColor DarkGray }
  }

  Write-Host ("  " + ("-" * 66)) -ForegroundColor DarkGray
  $tr = (Get-ChildItem "$root\dataset_640\images\train" -ErrorAction SilentlyContinue).Count
  $va = (Get-ChildItem "$root\dataset_640\images\val" -ErrorAction SilentlyContinue).Count
  Write-Host ("  변환된 데이터셋: train {0:N0}장 / val {1:N0}장" -f $tr, $va) -ForegroundColor White
  Write-Host ("  디스크 여유: {0:N0} GB" -f ((Get-PSDrive C).Free / 1GB)) -ForegroundColor White
  Write-Host ""
  Write-Host "  최근 로그:" -ForegroundColor DarkGray
  Get-Content "$root\pipeline.log" -Tail 4 | Where-Object { $_ -notmatch '장 변환|회전 검사' } | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }

  Start-Sleep -Seconds 10
}
