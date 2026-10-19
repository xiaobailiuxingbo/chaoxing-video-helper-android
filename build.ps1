param([string]$SdkRoot=$env:ANDROID_HOME, [string]$SigningKey='')
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($SdkRoot)) { $SdkRoot=$env:ANDROID_SDK_ROOT }
if([string]::IsNullOrWhiteSpace($SdkRoot)) { throw 'Pass -SdkRoot or set ANDROID_HOME to your Android SDK directory.' }
$SdkRoot=(Resolve-Path -LiteralPath $SdkRoot).Path
$buildTools=Join-Path $SdkRoot 'build-tools\35.0.0'
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$javac=(Get-Command javac -ErrorAction Stop).Source
$javaBin=Split-Path $javac -Parent
$java=Join-Path $javaBin 'java.exe'
$jar=Join-Path $javaBin 'jar.exe'
$keytool=Join-Path $javaBin 'keytool.exe'
$run=Join-Path $PSScriptRoot ('build\run-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
$classes=Join-Path $run 'classes'
$dex=Join-Path $run 'dex'
$tests=Join-Path $run 'tests'
New-Item -ItemType Directory -Path $classes,$dex,$tests -Force | Out-Null
function Check-Exit([string]$Stage) { if($LASTEXITCODE -ne 0){throw "$Stage failed with exit $LASTEXITCODE"} }
$sourceRoot=Join-Path $PSScriptRoot 'src\local\mr\videohelper'
$files=Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' | Select-Object -ExpandProperty FullName
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes @files
Check-Exit 'javac Android'
& $javac -encoding UTF-8 -d $tests (Join-Path $sourceRoot 'AutomationEngine.java') (Join-Path $sourceRoot 'VideoQuiz.java') (Join-Path $sourceRoot 'CourseContext.java') (Join-Path $sourceRoot 'QuizKey.java') (Join-Path $sourceRoot 'QuizFeedback.java') (Join-Path $PSScriptRoot 'test\EngineChecks.java')
Check-Exit 'javac checks'
& $java -cp $tests EngineChecks (Join-Path $PSScriptRoot 'test\fixtures')
Check-Exit 'UI decision checks'
$compiledRes=Join-Path $run 'resources.zip'
& (Join-Path $buildTools 'aapt2.exe') compile --dir (Join-Path $PSScriptRoot 'res') -o $compiledRes
Check-Exit 'aapt2 compile'
$baseApk=Join-Path $run 'base.apk'
& (Join-Path $buildTools 'aapt2.exe') link -o $baseApk -I $androidJar --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') --min-sdk-version 26 --target-sdk-version 35 --version-code 5 --version-name '1.2.0' $compiledRes
Check-Exit 'aapt2 link'
$classesJar=Join-Path $run 'classes.jar'
& $jar --create --file $classesJar -C $classes .
Check-Exit 'jar classes'
& $java -cp (Join-Path $buildTools 'lib\d8.jar') com.android.tools.r8.D8 --lib $androidJar --min-api 26 --output $dex $classesJar
Check-Exit 'd8'
& $jar --update --file $baseApk -C $dex classes.dex
Check-Exit 'add dex'
$aligned=Join-Path $run 'aligned.apk'
& (Join-Path $buildTools 'zipalign.exe') -f 4 $baseApk $aligned
Check-Exit 'zipalign'
$keystore=Join-Path $PSScriptRoot 'build\debug.keystore'
if(-not [string]::IsNullOrWhiteSpace($SigningKey)) { $keystore=(Resolve-Path -LiteralPath $SigningKey).Path }
if(!(Test-Path -LiteralPath $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Local Video Helper,O=Local,C=CN'
    Check-Exit 'debug key'
}
$apk=Join-Path $PSScriptRoot 'video-helper.apk'
& $java -jar (Join-Path $buildTools 'lib\apksigner.jar') sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
Check-Exit 'APK sign'
& $java -jar (Join-Path $buildTools 'lib\apksigner.jar') verify --verbose $apk
Check-Exit 'APK signature verification'
& (Join-Path $buildTools 'aapt.exe') dump badging $apk
Check-Exit 'APK metadata'
Write-Output "APK ready: $apk"
