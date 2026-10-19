param(
    [string]$SdkRoot=$env:ANDROID_HOME,
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Device
)
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot=$env:ANDROID_SDK_ROOT }
if([string]::IsNullOrWhiteSpace($SdkRoot)) { throw 'Pass -SdkRoot or set ANDROID_HOME to your Android SDK directory.' }
$SdkRoot=(Resolve-Path -LiteralPath $SdkRoot).Path
$run=Join-Path $PSScriptRoot ('build\json-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
$classes=Join-Path $run 'classes'
$dex=Join-Path $run 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
function Check-Exit([string]$Stage){if($LASTEXITCODE -ne 0){throw "$Stage failed"}}
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$source=Join-Path $PSScriptRoot 'src\local\mr\videohelper'
& javac -encoding UTF-8 -source 8 -target 8 -cp $androidJar -d $classes (Join-Path $source 'AutomationEngine.java') (Join-Path $source 'VideoQuiz.java') (Join-Path $source 'DeepSeekClient.java') (Join-Path $source 'QuizKey.java') (Join-Path $source 'QuizCache.java') (Join-Path $PSScriptRoot 'test\JsonChecks.java')
Check-Exit 'javac'
$inputJar=Join-Path $run 'checks.jar'
& jar --create --file $inputJar -C $classes .
Check-Exit 'jar'
& java -cp (Join-Path $SdkRoot 'build-tools\35.0.0\lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output $dex $inputJar
Check-Exit 'd8'
$deviceJar=Join-Path $run 'android-checks.jar'
& jar --create --file $deviceJar -C $dex classes.dex
Check-Exit 'dex jar'
& $Adb -s $Device push $deviceJar /data/local/tmp/video-helper-json-checks.jar
Check-Exit 'push'
& $Adb -s $Device shell dalvikvm -cp /data/local/tmp/video-helper-json-checks.jar JsonChecks
Check-Exit 'Android JSON checks'
