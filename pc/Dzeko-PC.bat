@echo off
title Dzeko PC
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~f0'; $s=[IO.File]::ReadAllText($f,[Text.Encoding]::UTF8); $m='#__'+'PS__'; $i=$s.IndexOf($m); iex $s.Substring($i+$m.Length)"
exit /b
#__PS__
# =====================================================================================
#  Dzeko PC: lets the Dzeko assistant (phone or browser) control this computer.
#  Commands arrive encrypted (AES-256-CBC) and signed (HMAC-SHA256) with your pairing
#  code, through ntfy.sh. Only a fixed list of safe actions is accepted.
# =====================================================================================
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Version = '1.0'
$Dir = Join-Path $env:APPDATA 'Dzeko'
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$CfgPath = Join-Path $Dir 'pc.json'
$Ntfy = 'https://ntfy.sh'
$Alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function Say($t, $c = 'Gray') { Write-Host $t -ForegroundColor $c }

function Normalize-Code([string]$raw) {
  $c = ($raw.ToUpper() -replace '[^A-Z0-9]', '')
  if ($c.StartsWith('DZK')) { $c = $c.Substring(3) }
  return $c
}
function Test-Code([string]$c) {
  if ($c.Length -ne 20) { return $false }
  foreach ($ch in $c.ToCharArray()) { if ($Alphabet.IndexOf($ch) -lt 0) { return $false } }
  return $true
}

# ---------------------------------------------------------------- crypto (same as the web app)
function Get-Hmac([byte[]]$key, [byte[]]$data) {
  $h = New-Object System.Security.Cryptography.HMACSHA256 (, $key)
  try { return , $h.ComputeHash($data) } finally { $h.Dispose() }
}
function Get-Utf8([string]$s) { return , [Text.Encoding]::UTF8.GetBytes($s) }
function Get-Hex([byte[]]$b) { return (($b | ForEach-Object { $_.ToString('x2') }) -join '') }
function Same-Bytes([byte[]]$a, [byte[]]$b) {
  if ($a.Length -ne $b.Length) { return $false }
  $d = 0; for ($i = 0; $i -lt $a.Length; $i++) { $d = $d -bor ($a[$i] -bxor $b[$i]) }
  return ($d -eq 0)
}
function Init-Keys([string]$code) {
  $sec = Get-Utf8 $code
  $script:EncKey = Get-Hmac $sec (Get-Utf8 'enc')
  $script:MacKey = Get-Hmac $sec (Get-Utf8 'mac')
  $sha = [Security.Cryptography.SHA256]::Create()
  $hash = $sha.ComputeHash((Get-Utf8 ($code + '|topic')))
  $script:Topic = 'dzk' + (Get-Hex $hash).Substring(0, 20)
}
function Seal($obj) {
  $aes = [Security.Cryptography.Aes]::Create()
  $aes.Key = $script:EncKey; $aes.Mode = 'CBC'; $aes.Padding = 'PKCS7'; $aes.GenerateIV()
  $pt = Get-Utf8 ($obj | ConvertTo-Json -Compress -Depth 5)
  $ct = $aes.CreateEncryptor().TransformFinalBlock($pt, 0, $pt.Length)
  $ivct = [byte[]]($aes.IV + $ct)
  $tag = Get-Hmac $script:MacKey $ivct
  $aes.Dispose()
  return [Convert]::ToBase64String([byte[]]($ivct + $tag))
}
function Open-Sealed([string]$b64) {
  try { $all = [Convert]::FromBase64String($b64.Trim()) } catch { return $null }
  if ($all.Length -lt 64) { return $null }
  $n = $all.Length
  $ivct = [byte[]]$all[0..($n - 33)]
  $tag = [byte[]]$all[($n - 32)..($n - 1)]
  if (-not (Same-Bytes (Get-Hmac $script:MacKey $ivct) $tag)) { return $null }
  $aes = [Security.Cryptography.Aes]::Create()
  $aes.Key = $script:EncKey; $aes.Mode = 'CBC'; $aes.Padding = 'PKCS7'
  $aes.IV = [byte[]]$ivct[0..15]
  $ct = [byte[]]$ivct[16..($ivct.Length - 1)]
  try { $pt = $aes.CreateDecryptor().TransformFinalBlock($ct, 0, $ct.Length) } catch { return $null } finally { $aes.Dispose() }
  return ([Text.Encoding]::UTF8.GetString($pt) | ConvertFrom-Json)
}

# ---------------------------------------------------------------- talking to ntfy
function Send-Result([string]$id, [bool]$ok, [string]$msg, $data = $null) {
  $o = [ordered]@{ id = $id; ok = $ok; msg = $msg; ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); pc = $env:COMPUTERNAME }
  if ($data) { $o.data = $data }
  $body = Seal $o
  try { Invoke-RestMethod -Method Post -Uri "$Ntfy/$($script:Topic)r" -Body $body -ContentType 'text/plain; charset=utf-8' -TimeoutSec 15 | Out-Null } catch { Say "  (não consegui enviar a resposta: $($_.Exception.Message))" 'DarkYellow' }
}

# ---------------------------------------------------------------- helpers for actions
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class DzkKeys {
  [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public static void Press(byte vk) { keybd_event(vk, 0, 0, UIntPtr.Zero); keybd_event(vk, 0, 2, UIntPtr.Zero); }
}
'@
function Strip-Accents([string]$s) {
  if (-not $s) { return '' }
  $n = $s.Normalize([Text.NormalizationForm]::FormD)
  return (($n -replace '\p{Mn}', '').ToLower() -replace '[^a-z0-9 ]', ' ' -replace '\s+', ' ').Trim()
}
$KnownApps = @{
  'spotify' = 'spotify:'; 'whatsapp' = 'whatsapp:'; 'zap' = 'whatsapp:'; 'configuracoes' = 'ms-settings:'; 'ajustes' = 'ms-settings:'
  'calculadora' = 'calc.exe'; 'bloco de notas' = 'notepad.exe'; 'notepad' = 'notepad.exe'; 'explorador' = 'explorer.exe'; 'arquivos' = 'explorer.exe'
  'gerenciador de tarefas' = 'taskmgr.exe'; 'paint' = 'mspaint.exe'; 'prompt' = 'cmd.exe'; 'terminal' = 'wt.exe'
  'chrome' = 'chrome.exe'; 'google chrome' = 'chrome.exe'; 'edge' = 'msedge.exe'; 'navegador' = 'https://www.google.com'
  'word' = 'winword.exe'; 'excel' = 'excel.exe'; 'powerpoint' = 'powerpnt.exe'; 'outlook' = 'outlook.exe'
  'youtube' = 'https://www.youtube.com'; 'gmail' = 'https://mail.google.com'; 'netflix' = 'https://www.netflix.com'; 'chatgpt' = 'https://chatgpt.com'
  'loja' = 'ms-windows-store:'; 'microsoft store' = 'ms-windows-store:'; 'camera' = 'microsoft.windows.camera:'; 'relogio' = 'ms-clock:'
}
$BlockedApps = 'banco|bank|nubank|itau|bradesco|santander|caixa|picpay|mercado pago|pagbank|binance|paypal|carteira|wallet'
function Open-App([string]$name) {
  $k = Strip-Accents $name
  if ($k -match $BlockedApps) { return @($false, 'Por segurança, apps de banco e pagamento não são abertos pelo Dzeko.') }
  if ($KnownApps.ContainsKey($k)) {
    try { Start-Process $KnownApps[$k]; return @($true, "Abri $name.") } catch {}
  }
  try {
    $apps = Get-StartApps
    $hit = $apps | Where-Object { (Strip-Accents $_.Name) -eq $k } | Select-Object -First 1
    if (-not $hit) { $hit = $apps | Where-Object { (Strip-Accents $_.Name) -like "$k*" } | Select-Object -First 1 }
    if (-not $hit) { $hit = $apps | Where-Object { (Strip-Accents $_.Name) -like "*$k*" } | Select-Object -First 1 }
    if ($hit) { Start-Process 'explorer.exe' "shell:AppsFolder\$($hit.AppID)"; return @($true, "Abri $($hit.Name).") }
  } catch {}
  try { Start-Process $name; return @($true, "Abri $name.") } catch {}
  return @($false, "Não encontrei um app chamado $name neste PC.")
}
function Open-Url([string]$u) {
  if ($u -notmatch '^https?://') { $u = 'https://' + $u }
  if ($u -match $BlockedApps) { return @($false, 'Por segurança, sites de banco não são abertos pelo Dzeko.') }
  Start-Process $u; return @($true, 'Abri no navegador.')
}
function Set-Volume([string]$v) {
  $s = Strip-Accents $v
  if ($s -match 'mud|silenc') { [DzkKeys]::Press(0xAD); return 'Som mudo (repita para desmutar).' }
  if ($s -match 'mais|aument|sobe') { 1..5 | ForEach-Object { [DzkKeys]::Press(0xAF) }; return 'Volume aumentado.' }
  if ($s -match 'menos|diminu|baix') { 1..5 | ForEach-Object { [DzkKeys]::Press(0xAE) }; return 'Volume diminuído.' }
  if ($s -match 'max') { 1..50 | ForEach-Object { [DzkKeys]::Press(0xAF) }; return 'Volume no máximo.' }
  $n = 0; if ([int]::TryParse(($s -replace '[^0-9]', ''), [ref]$n)) {
    $n = [Math]::Max(0, [Math]::Min(100, $n))
    1..50 | ForEach-Object { [DzkKeys]::Press(0xAE) }
    $steps = [int][Math]::Round($n / 2); if ($steps -gt 0) { 1..$steps | ForEach-Object { [DzkKeys]::Press(0xAF) } }
    return "Volume em $n%."
  }
  return 'Não entendi o volume.'
}
function Send-Media([string]$v) {
  $s = Strip-Accents $v
  if ($s -match 'prox|pula|avanc|next') { [DzkKeys]::Press(0xB0); return 'Próxima música.' }
  if ($s -match 'anter|volta|prev') { [DzkKeys]::Press(0xB1); return 'Música anterior.' }
  if ($s -match 'stop|parar tudo') { [DzkKeys]::Press(0xB2); return 'Música parada.' }
  [DzkKeys]::Press(0xB3); return 'Tocar/pausar.'
}
function Take-Shot {
  $dir = Join-Path ([Environment]::GetFolderPath('MyPictures')) 'Dzeko'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $b = [Windows.Forms.SystemInformation]::VirtualScreen
  $bmp = New-Object Drawing.Bitmap $b.Width, $b.Height
  $g = [Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Left, $b.Top, 0, 0, $bmp.Size)
  $file = Join-Path $dir ("print-" + (Get-Date -Format 'yyyy-MM-dd_HH-mm-ss') + '.png')
  $bmp.Save($file, [Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
  return "Print salvo em Imagens\Dzeko."
}
function Type-Text([string]$t) {
  $esc = ($t -replace '([\+\^%~\(\)\{\}\[\]])', '{$1}')
  Start-Sleep -Milliseconds 300
  [Windows.Forms.SendKeys]::SendWait($esc)
  return 'Texto digitado.'
}
function Open-Folder([string]$v) {
  $s = Strip-Accents $v
  $map = @{ 'downloads' = (Join-Path $env:USERPROFILE 'Downloads'); 'documentos' = [Environment]::GetFolderPath('MyDocuments'); 'desktop' = [Environment]::GetFolderPath('Desktop'); 'area de trabalho' = [Environment]::GetFolderPath('Desktop'); 'imagens' = [Environment]::GetFolderPath('MyPictures'); 'fotos' = [Environment]::GetFolderPath('MyPictures'); 'musicas' = [Environment]::GetFolderPath('MyMusic'); 'videos' = [Environment]::GetFolderPath('MyVideos') }
  foreach ($k in $map.Keys) { if ($s -like "*$k*") { Start-Process 'explorer.exe' $map[$k]; return "Abri a pasta $k." } }
  Start-Process 'explorer.exe'; return 'Abri o Explorador de Arquivos.'
}
$Protected = 'explorer|winlogon|csrss|svchost|lsass|services|smss|wininit|dwm|system|powershell|cmd|conhost|searchhost|startmenuexperiencehost|shellexperiencehost|sihost|taskhostw|antimalware|msmpeng|securityhealth'
function Close-App([string]$name) {
  $k = Strip-Accents $name
  $procs = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -notmatch $Protected -and ((Strip-Accents $_.ProcessName) -like "*$($k -replace ' ', '')*" -or (Strip-Accents $_.MainWindowTitle) -like "*$k*") }
  if (-not $procs) { return @($false, "Não achei $name aberto.") }
  $procs | ForEach-Object { try { $_.CloseMainWindow() | Out-Null } catch {} }
  Start-Sleep -Seconds 2
  $procs | Where-Object { -not $_.HasExited } | ForEach-Object { try { $_.Kill() } catch {} }
  return @($true, "Fechei $name.")
}
function Get-Status {
  $os = Get-CimInstance Win32_OperatingSystem
  $up = (Get-Date) - $os.LastBootUpTime
  $cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
  $ramFree = [Math]::Round($os.FreePhysicalMemory / 1MB, 1); $ramTot = [Math]::Round($os.TotalVisibleMemorySize / 1MB, 1)
  $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
  $free = [Math]::Round($disk.FreeSpace / 1GB)
  $bat = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -First 1
  $b = if ($bat) { ", bateria em $($bat.EstimatedChargeRemaining)%" } else { '' }
  return "PC $env:COMPUTERNAME ligado há $([int]$up.TotalHours) h $($up.Minutes) min, processador em $cpu%, memória livre $ramFree de $ramTot GB, $free GB livres no disco C$b."
}

# ---------------------------------------------------------------- the commands
function Run-Command($c) {
  $a = Strip-Accents ([string]$c.a); $v = [string]$c.v
  switch ($a) {
    'ping' { return @($true, "PC $env:COMPUTERNAME online.") }
    'abrir' { return Open-App $v }
    'app' { return Open-App $v }
    'site' { return Open-Url $v }
    'pesquisar' { Start-Process ('https://www.google.com/search?q=' + [uri]::EscapeDataString($v)); return @($true, 'Pesquisa aberta no navegador.') }
    'youtube' { Start-Process ('https://www.youtube.com/results?search_query=' + [uri]::EscapeDataString($v)); return @($true, 'YouTube aberto.') }
    'musica' {
      try { Start-Process ('spotify:search:' + [uri]::EscapeDataString($v)); return @($true, "Procurei $v no Spotify.") }
      catch { Start-Process ('https://open.spotify.com/search/' + [uri]::EscapeDataString($v)); return @($true, "Abri $v no Spotify Web.") }
    }
    'midia' { return @($true, (Send-Media $v)) }
    'volume' { return @($true, (Set-Volume $v)) }
    'bloquear' { Start-Process 'rundll32.exe' 'user32.dll,LockWorkStation'; return @($true, 'Tela bloqueada.') }
    'desligar' { & shutdown.exe /s /t 60 /c 'Dzeko: o PC vai desligar em 1 minuto. Para cancelar, peça ao Dzeko para cancelar o desligamento.' | Out-Null; return @($true, 'O PC desliga em 1 minuto. Dá para cancelar.') }
    'reiniciar' { & shutdown.exe /r /t 60 /c 'Dzeko: o PC vai reiniciar em 1 minuto.' | Out-Null; return @($true, 'O PC reinicia em 1 minuto. Dá para cancelar.') }
    'suspender' { Start-Process 'rundll32.exe' 'powrprof.dll,SetSuspendState 0,1,0'; return @($true, 'Suspendendo o PC.') }
    'cancelar desligamento' { & shutdown.exe /a 2>$null | Out-Null; return @($true, 'Desligamento cancelado.') }
    'cancelar_desligamento' { & shutdown.exe /a 2>$null | Out-Null; return @($true, 'Desligamento cancelado.') }
    'print' { return @($true, (Take-Shot)) }
    'digitar' { return @($true, (Type-Text $v)) }
    'copiar' { Set-Clipboard -Value $v; return @($true, 'Copiado para a área de transferência do PC.') }
    'pasta' { return @($true, (Open-Folder $v)) }
    'fechar' { return Close-App $v }
    'status' { return @($true, (Get-Status)) }
    default { return @($false, "Ação desconhecida: $a") }
  }
}

# ---------------------------------------------------------------- pairing
Clear-Host
Say ''
Say '   ================================' 'Green'
Say "        DZEKO PC  -  versao $Version" 'Green'
Say '   ================================' 'Green'
Say ''
$code = Normalize-Code ([string]$env:DZK_CODE)
if (-not (Test-Code $code) -and (Test-Path $CfgPath)) { try { $code = Normalize-Code ((Get-Content $CfgPath -Raw | ConvertFrom-Json).code) } catch {} }
while (-not (Test-Code $code)) {
  Say '   No Dzeko, vá em Ajustes > Meu PC > Conectar meu PC.' 'Gray'
  $code = Normalize-Code (Read-Host '   Digite o código que aparece lá (ex.: DZK-ABCD-EFGH-JKLM-NPQR-STUV)')
  if (-not (Test-Code $code)) { Say '   Código inválido. Confira as letras e tente de novo.' 'Red' }
}
@{ code = $code } | ConvertTo-Json | Set-Content -Path $CfgPath -Encoding UTF8
Init-Keys $code

# start with Windows (asked once)
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Dzeko PC.lnk'
$self = Join-Path $Dir 'Dzeko-PC.bat'
if (-not $env:DZK_TEST -and -not (Test-Path $startup)) {
  $ans = Read-Host '   Iniciar o Dzeko PC junto com o Windows? (S/N)'
  if ($ans -match '^[sSyY]') {
    try {
      Copy-Item -LiteralPath $f -Destination $self -Force
      $sh = New-Object -ComObject WScript.Shell; $lnk = $sh.CreateShortcut($startup)
      $lnk.TargetPath = $self; $lnk.WorkingDirectory = $Dir; $lnk.WindowStyle = 7; $lnk.Description = 'Dzeko PC'; $lnk.Save()
      Say '   Pronto: ele vai abrir minimizado sempre que o Windows ligar.' 'Green'
    } catch { Say "   Não consegui criar o atalho de inicialização: $($_.Exception.Message)" 'DarkYellow' }
  }
}

Say ''
Say "   Conectado. O PC $env:COMPUTERNAME está ouvindo o Dzeko." 'Green'
Say '   Pode minimizar esta janela. Para desligar, feche-a.' 'Gray'
Say "   (Para trocar o código, apague $CfgPath)" 'DarkGray'
Say ''
Send-Result 'hello' $true "PC $env:COMPUTERNAME conectado."

# ---------------------------------------------------------------- listen forever
$since = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$seen = New-Object 'System.Collections.Generic.HashSet[string]'
$deadline = if ($env:DZK_TEST) { (Get-Date).AddSeconds([int]$env:DZK_TEST) } else { [DateTime]::MaxValue }
while ((Get-Date) -lt $deadline) {
  try {
    $req = [Net.HttpWebRequest]::Create("$Ntfy/$($script:Topic)/json?since=$since")
    $req.Timeout = 20000; $req.ReadWriteTimeout = 90000; $req.UserAgent = "DzekoPC/$Version"
    $resp = $req.GetResponse()
    $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    while (-not $sr.EndOfStream -and (Get-Date) -lt $deadline) {
      $line = $sr.ReadLine(); if (-not $line) { continue }
      $ev = $line | ConvertFrom-Json
      if ($ev.event -ne 'message') { continue }
      $since = $ev.id
      $c = Open-Sealed ([string]$ev.message)
      if (-not $c) { Say '   (mensagem ignorada: assinatura inválida)' 'DarkYellow'; continue }
      $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
      if ([Math]::Abs($now - [int64]$c.ts) -gt 120000 -or -not $seen.Add([string]$c.id)) { continue }
      $stamp = Get-Date -Format 'HH:mm:ss'
      Say "   [$stamp] $($c.a) $($c.v)" 'Cyan'
      try { $r = Run-Command $c; Send-Result ([string]$c.id) ([bool]$r[0]) ([string]$r[1]); Say "            $($r[1])" 'Gray' }
      catch { Send-Result ([string]$c.id) $false "Erro no PC: $($_.Exception.Message)"; Say "            erro: $($_.Exception.Message)" 'Red' }
    }
    $sr.Close(); $resp.Close()
  } catch {
    Start-Sleep -Seconds 5
  }
}
