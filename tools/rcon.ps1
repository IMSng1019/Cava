<#
Cava P0-E: minimal Source-RCON client (no stdin pipes needed -> works under the file sandbox).
usage: pwsh -File tools/rcon.ps1 -Command "spark tps"
       pwsh -File tools/rcon.ps1 -Command "list" -Raw
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string]$Command,
  [string]$RconHost = '127.0.0.1',
  [int]$Port = 25576,   # testbed default; 25575 belongs to other agents' servers on this host
  [string]$Password = 'cava',
  [int]$TimeoutMs = 20000,
  # after the first response packet, keep reading until the server has been silent for
  # this long: spark replies from a worker thread and can send several packets
  [int]$IdleMs = 2500,
  [switch]$NoLog
)
$ErrorActionPreference = 'Stop'

function Read-Exact([System.IO.Stream]$s, [int]$n) {
  $buf = New-Object byte[] $n
  $got = 0
  while ($got -lt $n) {
    $r = $s.Read($buf, $got, $n - $got)
    if ($r -le 0) { throw 'rcon: connection closed by server' }
    $got += $r
  }
  ,$buf
}

function Send-Packet([System.IO.Stream]$s, [int]$id, [int]$type, [string]$body) {
  $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($body)
  $len = 4 + 4 + $bodyBytes.Length + 2
  $ms = New-Object System.IO.MemoryStream
  $bw = New-Object System.IO.BinaryWriter($ms)
  $bw.Write([int]$len); $bw.Write([int]$id); $bw.Write([int]$type)
  $bw.Write($bodyBytes); $bw.Write([byte]0); $bw.Write([byte]0); $bw.Flush()
  $arr = $ms.ToArray()
  $s.Write($arr, 0, $arr.Length); $s.Flush()
}

function Read-Packet([System.IO.Stream]$s) {
  $lenBuf = Read-Exact $s 4
  $len = [System.BitConverter]::ToInt32($lenBuf, 0)
  $body = Read-Exact $s $len
  $id = [System.BitConverter]::ToInt32($body, 0)
  $type = [System.BitConverter]::ToInt32($body, 4)
  $text = [System.Text.Encoding]::ASCII.GetString($body, 8, $len - 10)
  [pscustomobject]@{ Id = $id; Type = $type; Body = $text }
}

$client = New-Object System.Net.Sockets.TcpClient
$client.ReceiveTimeout = $TimeoutMs
$client.SendTimeout = $TimeoutMs
$client.Connect($RconHost, $Port)
$stream = $client.GetStream()
try {
  Send-Packet $stream 1 3 $Password
  $auth = Read-Packet $stream
  if ($auth.Id -eq -1) { throw 'rcon: authentication failed' }
  Send-Packet $stream 2 2 $Command
  $sb = New-Object System.Text.StringBuilder
  $client.ReceiveTimeout = $IdleMs
  while ($true) {
    try { $p = Read-Packet $stream; [void]$sb.Append($p.Body) } catch { break }
  }
  $text = $sb.ToString().TrimEnd()
  if (-not $NoLog) {
    Write-Host ("[rcon] > {0}" -f $Command)
    if ($text) { Write-Host $text }
  }
  $text
} finally {
  $stream.Close(); $client.Close()
}
