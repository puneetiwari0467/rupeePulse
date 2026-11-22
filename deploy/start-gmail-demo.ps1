param(
    [Parameter(Mandatory)][string]$ClientJson,
    [Parameter(Mandatory)][string]$DemoEmail,
    [ValidateRange(1024,65535)][int]$Port = 8094
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $project 'target\azure\rupeepulse-1.1.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the Gmail branch with npm run build and mvn -Pazure-release verify first.' }
$file = (Resolve-Path -LiteralPath $ClientJson).Path
if ($file.StartsWith("$project\", [StringComparison]::OrdinalIgnoreCase)) { throw 'Keep the OAuth client download outside the repository.' }
try { $web = (Get-Content -LiteralPath $file -Raw | ConvertFrom-Json -ErrorAction Stop).web }
catch { throw 'Choose a valid downloaded Google Web application OAuth client JSON.' }
$callback = "http://127.0.0.1:$Port/api/gmail/callback"
if (-not $web.client_id -or -not $web.client_secret -or $web.redirect_uris -notcontains $callback) {
    throw "Register $callback on the Web client, then download its JSON again."
}
if ($DemoEmail -notmatch '^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$') { throw 'Use the primary address of your own demo test mailbox.' }
$settings = @{
    GMAIL_CLIENT_ID = $web.client_id; GMAIL_CLIENT_SECRET = $web.client_secret
    GMAIL_REDIRECT_URI = $callback; GMAIL_DEMO_EMAIL = $DemoEmail.Trim()
}
$previous = @{}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name,'Process')
        [Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')
    }
    Write-Host "Local Gmail trial at http://127.0.0.1:$Port/ — Tools > Data sources. Ctrl+C stops it."
    & $java -jar $jar "--server.port=$Port" '--server.address=127.0.0.1' '--AZURE_OPENAI_API_KEY=' '--AZURE_OPENAI_ENDPOINT='
    if ($LASTEXITCODE -ne 0) { throw "Local server exited with code $LASTEXITCODE. Select Java 21 and a free port." }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name,$previous[$name],'Process') }
}
