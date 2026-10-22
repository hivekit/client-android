param(
    [string]$ServerRepository = (Join-Path $PSScriptRoot '../../core-server'),
    [int]$Port = 18990,
    [switch]$AndroidDevice
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskTools = Join-Path $taskRoot '.build-tools'
New-Item -ItemType Directory -Force -Path $taskTools | Out-Null
$taskBinary = Join-Path $taskTools 'hivekit-test-server.exe'
$taskConfig = Join-Path $taskTools 'test-server.yml'
# This deliberately uses an independent, in-memory server and a test-only signing key.
@"
httpHost: 127.0.0.1
httpPort: $Port
httpPath: /api/v1/
websocketPath: /ws
restPathPrefix: /v2/
serverRole: all
healthCheckPath: /health
serverStatusPath: /status
adminDashboardEnabled: false
tcpEnabled: false
httpRequestTimeout: 5s
wsHandshakeTimeout: 5s
outgoingMessageBufferSize: 128
outgoingMessageBufferInterval: 10ms
subscriptionBatchInterval: 25ms
minCheckInterval: 30s
maxRecurseDepth: 5
maxRequestBodySize: 10485760
presenceRefreshRate: 100ms
logLevel: error
authType: jwt
authCookieName: android_test
authTokenSecret: android-integration-only
authTokenClockTolerance: 10s
authTokenMaxLifeTime: 120h
tenantIdClaimKey: tnd
persistenceType: none
persistenceMessageBufferSize: 128
timeSeriesPersistenceType: none
timeSeriesPersistenceMaxTimeBetweenWrites: 5s
saveFrequency: -1
metricsInterval: 0
reportStorageType: local-server-local-db
reportFileDirectory: ./reports
userFileUploadType: local
userFileUploadDirectory: ./uploads
"@ | Set-Content -LiteralPath $taskConfig -Encoding utf8
Push-Location (Join-Path $ServerRepository 'src')
try {
    & go build -o $taskBinary hivekit-server.go
    if ($LASTEXITCODE -ne 0) { throw 'Hivekit server build failed' }
} finally { Pop-Location }
$taskProcess = Start-Process -FilePath $taskBinary -ArgumentList @('-c', $taskConfig) -WorkingDirectory $taskTools -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskTools 'server.stdout.log') -RedirectStandardError (Join-Path $taskTools 'server.stderr.log')
$taskOldHttp = $env:HIVEKIT_TEST_HTTP_URL
$taskOldWs = $env:HIVEKIT_TEST_WS_URL
$taskOldSecret = $env:HIVEKIT_TEST_SECRET
try {
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 100; $taskAttempt++) {
        if ($taskProcess.HasExited) { throw "Test server exited; see $taskTools/server.stderr.log" }
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:$Port/status" -TimeoutSec 1 | Out-Null
            $taskReady = $true
            break
        } catch { Start-Sleep -Milliseconds 100 }
    }
    if (!$taskReady) { throw 'Test server did not become ready' }
    $env:HIVEKIT_TEST_HTTP_URL = "http://127.0.0.1:$Port/api/v1/"
    $env:HIVEKIT_TEST_WS_URL = "ws://127.0.0.1:$Port/ws"
    $env:HIVEKIT_TEST_SECRET = 'android-integration-only'
    Push-Location $taskRoot
    try {
        & ./gradlew.bat :sdk:test --rerun-tasks
        if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
        if ($AndroidDevice) {
            & ./gradlew.bat -PincludeAndroidSample=true "-Pandroid.testInstrumentationRunnerArguments.hivekitWs=ws://10.0.2.2:$Port/ws" :sample-android:connectedDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { throw 'Android device tests failed' }
        }
    } finally { Pop-Location }
} finally {
    if (!$taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id }
    $env:HIVEKIT_TEST_HTTP_URL = $taskOldHttp
    $env:HIVEKIT_TEST_WS_URL = $taskOldWs
    $env:HIVEKIT_TEST_SECRET = $taskOldSecret
}
