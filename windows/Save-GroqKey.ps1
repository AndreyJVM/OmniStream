$ErrorActionPreference = 'Stop'
$folder = Join-Path $env:LOCALAPPDATA 'Omnistream'
[void][IO.Directory]::CreateDirectory($folder)
if ($env:GROQ_API_KEY) {
    $secret = ConvertTo-SecureString $env:GROQ_API_KEY -AsPlainText -Force
} else {
    $secret = Read-Host 'Groq credential to store for this Windows account' -AsSecureString
}
try {
    if (!$secret.Length) { throw 'Supply a Groq credential.' }
    $protected = ConvertFrom-SecureString $secret
    [IO.File]::WriteAllText((Join-Path $folder 'groq.credential'), $protected)
    Write-Host 'Groq credential saved for this Windows account.'
} finally {
    $secret.Dispose()
}
