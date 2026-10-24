param(
    [Parameter(Mandatory = $true)][string]$Java21Home,
    [Parameter(Mandatory = $true)][string]$Java25Home,
    [switch]$AcceptEula,
    [string]$RunName = ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
)

$ErrorActionPreference = 'Stop'
if (!$AcceptEula) {
    throw 'Read https://aka.ms/MinecraftEULA and provide -AcceptEula only if you agree to it.'
}
if ($RunName -notmatch '^[a-zA-Z0-9_-]{1,80}$') {
    throw 'RunName must contain only letters, numbers, underscores, and hyphens.'
}
$repository = Split-Path $PSScriptRoot -Parent
$runRoot = Join-Path $repository ".work/integration/$RunName"
if (Test-Path -LiteralPath $runRoot) {
    throw 'This run directory already exists. Choose a new RunName.'
}
$java21 = Join-Path $Java21Home 'bin/java.exe'
$java25 = Join-Path $Java25Home 'bin/java.exe'
foreach ($runtime in @($java21, $java25)) {
    if (!(Test-Path -LiteralPath $runtime)) {
        throw "Java executable not found: $runtime"
    }
}
$env:JAVA_HOME = $Java21Home
Push-Location $repository
try {
    & (Join-Path $repository 'mvnw.cmd') -q install
    if ($LASTEXITCODE -ne 0) { throw 'Main project build failed.' }
    & (Join-Path $repository 'mvnw.cmd') -q -f integration-tests/pom.xml package
    if ($LASTEXITCODE -ne 0) { throw 'Integration plugin build failed.' }
} finally {
    Pop-Location
}
New-Item -ItemType Directory -Path $runRoot | Out-Null
$headers = @{ 'User-Agent' = 'ChunkLoader-integration/1.0 (https://github.com/lino9999/ChunkLoader)' }
$results = @()
$port = 25600
foreach ($version in @('1.21.11', '26.1.2', '26.2', '26.3')) {
    $directory = Join-Path $runRoot $version
    $plugins = Join-Path $directory 'plugins'
    New-Item -ItemType Directory -Path $plugins -Force | Out-Null
    $builds = Invoke-RestMethod -Uri "https://fill.papermc.io/v3/projects/paper/versions/$version/builds" -Headers $headers
    $build = $builds | Where-Object channel -eq 'STABLE' | Select-Object -First 1
    if (!$build) { throw "No stable Paper build found for $version" }
    $download = $build.downloads.'server:default'
    $serverJar = Join-Path $directory 'paper.jar'
    Invoke-WebRequest -Uri $download.url -Headers $headers -OutFile $serverJar
    if ((Get-FileHash -LiteralPath $serverJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $download.checksums.sha256) {
        throw "Paper checksum verification failed for $version"
    }
    $build | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $directory 'metadata.json') -Encoding utf8
    Copy-Item -LiteralPath (Join-Path $repository 'target/ChunkLoader-1.0.0.jar') -Destination (Join-Path $plugins 'ChunkLoader.jar')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'target/ChunkLoaderIntegration.jar') -Destination (Join-Path $plugins 'ChunkLoaderIntegration.jar')
    Set-Content -LiteralPath (Join-Path $directory 'eula.txt') -Value 'eula=true' -Encoding ascii
    @('server-ip=127.0.0.1', "server-port=$port", 'online-mode=false', 'level-name=integration-world',
        'level-type=minecraft:flat', 'generate-structures=false', 'view-distance=2', 'simulation-distance=2',
        'pause-when-empty-seconds=-1', 'spawn-protection=0', 'max-players=1', 'allow-nether=false',
        'difficulty=normal', 'enable-rcon=false', 'enable-query=false') |
        Set-Content -LiteralPath (Join-Path $directory 'server.properties') -Encoding ascii
    $runtime = if ($version -eq '1.21.11') { $java21 } else { $java25 }
    foreach ($boot in @(1, 2)) {
        $process = Start-Process -FilePath $runtime -ArgumentList @('-Xms256M', '-Xmx1G', '-jar', 'paper.jar', '--nogui', '--nojline', '--noconsole') `
            -WorkingDirectory $directory -RedirectStandardOutput (Join-Path $directory "run$boot.log") `
            -RedirectStandardError (Join-Path $directory "run$boot.err.log") -WindowStyle Hidden -PassThru
        if (!$process.WaitForExit(180000)) {
            Stop-Process -Id $process.Id -Force
            throw "Paper $version boot $boot timed out. Inspect $directory"
        }
        $resultName = if ($boot -eq 1) { 'integration-stage1.json' } else { 'integration-result.json' }
        $resultPath = Join-Path $directory $resultName
        if (!(Test-Path -LiteralPath $resultPath)) { throw "Missing test result: $resultPath" }
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        if (!$result.passed) { throw "Paper $version boot $boot failed. Inspect $resultPath" }
        $pluginErrors = Select-String -LiteralPath (Join-Path $directory "run$boot.log"), (Join-Path $directory "run$boot.err.log") `
            -Pattern 'Could not pass event .* to ChunkLoader|Farm simulation failed|ChunkLoader could not start safely'
        if ($pluginErrors) { throw "Paper $version logged a ChunkLoader error. Inspect $directory" }
        $results += [PSCustomObject]@{ version = $version; build = $build.id; boot = $boot; result = $result }
        Write-Output "PASS: Paper $version build $($build.id), boot $boot"
    }
    $port++
}
$results | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runRoot 'summary.json') -Encoding utf8
Write-Output "Integration matrix passed. Results: $runRoot"
