# Copyright (c) 2026 TianzeL
# SPDX-License-Identifier: Apache-2.0

[CmdletBinding()]
param(
    [string]$GoExe,
    [string]$JavaHome,
    [string]$AndroidSdk,
    [string]$AndroidNdk,
    [string]$Output,
    [switch]$KeepWork,
    [switch]$SumDbDirect
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PinnedGoVersion = 'go1.27.3'
$PinnedMobileVersion = 'v0.0.0-20260821190718-4776eadac327'
$PinnedMobileSum = 'h1:D/wiQ6AoTYjDtSD0HMPhU8O40NUP8EF0UmDhIYCnG4I='
$PinnedMobileGoModSum = 'h1:D9q8rgXu13Q3uuM+Vuy6F/DG1WF/giTPLtqQ9on5B1M='
$PinnedNdkRevision = '29.0.14206865'
$PinnedCompileSdk = '36'
$MinimumAndroidApi = '24'
$JavaPackage = 'dev.lan.atticusvpn'
$JavaClass = 'dev.lan.atticusvpn.atticusengine.Atticusengine'

$NativeRoot = [IO.Path]::GetFullPath((Split-Path -Parent $MyInvocation.MyCommand.Path))
$TaskWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$ExecutableSuffix = if ($TaskWindows) { '.exe' } else { '' }
$WorkRoot = $null

function Resolve-Executable {
    param(
        [string]$Candidate,
        [string]$Fallback,
        [string]$Name
    )

    foreach ($value in @($Candidate, $Fallback)) {
        if ([string]::IsNullOrWhiteSpace($value)) {
            continue
        }
        if (Test-Path -LiteralPath $value -PathType Leaf) {
            return [IO.Path]::GetFullPath($value)
        }
        $command = Get-Command $value -CommandType Application -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            return $command.Source
        }
    }
    throw "找不到 $Name；请通过参数提供其完整路径。"
}

function Resolve-Directory {
    param(
        [string]$Candidate,
        [string]$EnvironmentName,
        [string]$Fallback,
        [string]$Name
    )

    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'Process')
    foreach ($value in @($Candidate, $environmentValue, $Fallback)) {
        if (-not [string]::IsNullOrWhiteSpace($value) -and
            (Test-Path -LiteralPath $value -PathType Container)) {
            return [IO.Path]::GetFullPath($value)
        }
    }
    throw "找不到 $Name；请通过参数提供其完整路径。"
}

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $NativeRoot
    )

    Push-Location -LiteralPath $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "命令失败（退出码 $LASTEXITCODE）：$FilePath $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-Captured {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $NativeRoot
    )

    # Native tools can write progress to stderr while stdout contains JSON.
    # Keep both streams separate, including on Windows PowerShell 5.1.
    $captureErrorFile = [IO.Path]::GetTempFileName()
    Push-Location -LiteralPath $WorkingDirectory
    try {
        $captured = & $FilePath @Arguments 2> $captureErrorFile
        $captureErrorText = [IO.File]::ReadAllText($captureErrorFile)
        if ($LASTEXITCODE -ne 0) {
            throw "命令失败（退出码 $LASTEXITCODE）：$FilePath $($Arguments -join ' ')`n$($captured -join [Environment]::NewLine)`n$captureErrorText"
        }
        if (-not [string]::IsNullOrWhiteSpace($captureErrorText)) {
            Write-Verbose $captureErrorText
        }
        return ($captured -join [Environment]::NewLine)
    }
    finally {
        Pop-Location
        Remove-Item -LiteralPath $captureErrorFile -ErrorAction SilentlyContinue
    }
}

function Assert-ChildPath {
    param(
        [string]$Parent,
        [string]$Child
    )

    $parentFull = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar
    $childFull = [IO.Path]::GetFullPath($Child)
    if (-not $childFull.StartsWith($parentFull, [StringComparison]::OrdinalIgnoreCase)) {
        throw "拒绝操作不在预期目录中的路径：$childFull"
    }
}

function Replace-ExactlyOnce {
    param(
        [string]$Text,
        [string]$Old,
        [string]$New,
        [string]$Description
    )

    $first = $Text.IndexOf($Old, [StringComparison]::Ordinal)
    if ($first -lt 0 -or $first -ne $Text.LastIndexOf($Old, [StringComparison]::Ordinal)) {
        throw "x/mobile 临时补丁前置条件不满足：$Description"
    }
    return $Text.Substring(0, $first) + $New + $Text.Substring($first + $Old.Length)
}

function Patch-MobileForPrivacy {
    param([string]$MobileRoot)

    # 修改的第三方文件：bind_androidapp.go / bind.go。保留上游版权和 BSD 声明。
    # 只修改经过模块校验的临时副本；不修改下载缓存。适配子进程环境、UTF-8 及路径隐私。
    $sourcePath = Join-Path $MobileRoot 'cmd/gomobile/bind_androidapp.go'
    $text = [IO.File]::ReadAllText($sourcePath).Replace("`r`n", "`n")

    $oldImports = "`t`"path/filepath`"`n`t`"strings`""
    $newImports = "`t`"path/filepath`"`n`t`"runtime`"`n`t`"strings`""
    $text = Replace-ExactlyOnce -Text $text -Old $oldImports -New $newImports `
        -Description 'runtime import'

    $oldEnvironment = "`tcmd.Env = append(cmd.Env, `"GOOS=android`")`n" +
        "`tcmd.Env = append(cmd.Env, `"CGO_ENABLED=1`")"
    $newEnvironment = @'
	goBin := os.Getenv("ATTICUS_GO_BIN")
	if goBin == "" {
		goBin = filepath.Join(runtime.GOROOT(), "bin")
	}
	goPath := goBin + string(os.PathListSeparator) + os.Getenv("PATH")
	cmd.Env = append(cmd.Env, "PATH="+goPath, "GOOS=android", "CGO_ENABLED=1")
'@
    $text = Replace-ExactlyOnce -Text $text -Old $oldEnvironment -New $newEnvironment `
        -Description 'gobind child environment'

    $oldJavac = "`t`t`"-d`", dst,`n`t`t`"-source`", javacTargetVer,"
    $newJavac = "`t`t`"-d`", dst,`n`t`t`"-encoding`", `"UTF-8`",`n`t`t`"-source`", javacTargetVer,"
    $text = Replace-ExactlyOnce -Text $text -Old $oldJavac -New $newJavac `
        -Description 'javac UTF-8 encoding'

    [IO.File]::WriteAllText($sourcePath, $text, [Text.UTF8Encoding]::new($false))

    # -trimpath 不会删除 Go build-info 中的本地 replace 路径；必须从生成的 go.mod 修正。
    $modulePath = Join-Path $MobileRoot 'cmd/gomobile/bind.go'
    $moduleText = [IO.File]::ReadAllText($modulePath).Replace("`r`n", "`n")
    $oldFormat = "`t`tbs, err := f.Format()"
    $newFormat = @'
		// AtticusVPN modification: avoid publishing host paths in Go build-info.
		// Local replacements keep the same target, relative to generated go.mod.
		for _, replacement := range f.Replace {
			if !filepath.IsAbs(replacement.New.Path) {
				continue
			}
			relative, err := filepath.Rel(dir, replacement.New.Path)
			if err != nil {
				return fmt.Errorf("private-path replacement: %w", err)
			}
			relative = filepath.ToSlash(relative)
			if !strings.HasPrefix(relative, "./") && !strings.HasPrefix(relative, "../") {
				relative = "./" + relative
			}
			if err := f.AddReplace(replacement.Old.Path, replacement.Old.Version, relative, replacement.New.Version); err != nil {
				return err
			}
		}
		bs, err := f.Format()
'@
    $moduleText = Replace-ExactlyOnce -Text $moduleText -Old $oldFormat -New $newFormat `
        -Description 'relative local module replacements'
    [IO.File]::WriteAllText($modulePath, $moduleText, [Text.UTF8Encoding]::new($false))
    Write-Host '使用临时 x/mobile 修改版：子进程环境、UTF-8、相对模块替换路径。上游缓存未改写。'
}

function Assert-NoBuildPaths {
    param([string]$Path, [string[]]$Prefixes)

    # 逐个解压条目检查 ASCII/UTF-8 与 UTF-16，不在压缩后的 ZIP 上做字符串检查。
    $bytes = [IO.File]::ReadAllBytes($Path)
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $wideText = [Text.Encoding]::Unicode.GetString($bytes)
    foreach ($prefix in $Prefixes) {
        if ([string]::IsNullOrWhiteSpace($prefix)) { continue }
        $forms = @($prefix, $prefix.Replace('\', '/'), $prefix.Replace('/', '\'),
            $prefix.Replace('\', '\\')) | Select-Object -Unique
        foreach ($form in $forms) {
            if ($text.IndexOf($form, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $wideText.IndexOf($form, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw 'AAR 包含构建机绝对路径；拒绝输出。'
            }
        }
    }
    if ($text -match '(?i)[A-Z]:[\\/]Users[\\/]|/(?:home|Users)/[^/\s]+/' -or
        $wideText -match '(?i)[A-Z]:[\\/]Users[\\/]|/(?:home|Users)/[^/\s]+/') {
        throw 'AAR 包含用户目录形式的绝对路径；拒绝输出。'
    }
}

function Protect-RawAar {
    param([string]$AarPath, [string]$Strip, [string]$ValidationRoot)

    $extractRoot = Join-Path $ValidationRoot 'raw-aar'
    [IO.Compression.ZipFile]::ExtractToDirectory($AarPath, $extractRoot)
    foreach ($library in Get-ChildItem -LiteralPath (Join-Path $extractRoot 'jni') -Filter '*.so' -File -Recurse) {
        Invoke-Checked -FilePath $Strip -Arguments @('--strip-debug', $library.FullName)
    }
    $stripped = Join-Path $ValidationRoot 'atticusengine.stripped.aar'
    [IO.Compression.ZipFile]::CreateFromDirectory($extractRoot, $stripped)
    return $stripped
}

function Get-PrivacyFlags {
    param([string[]]$Prefixes)

    $flags = [Collections.Generic.List[string]]::new()
    $index = 0
    foreach ($prefix in $Prefixes | Sort-Object -Unique) {
        if ([string]::IsNullOrWhiteSpace($prefix)) { continue }
        # Go 的 CGO 参数解析支持双引号；规范斜杠避免 Windows 反斜杠被当作转义。
        $canonical = $prefix.Replace('\', '/')
        if ($canonical.Contains('"')) { throw '构建目录不能包含双引号。' }
        $flags.Add('"-ffile-prefix-map=' + $canonical + '=./build-input-' + $index + '"')
        $flags.Add('"-fdebug-prefix-map=' + $canonical + '=./build-input-' + $index + '"')
        $index++
    }
    return $flags -join ' '
}

function Copy-SumDbCheckpoints {
    param([string[]]$GoPaths, [string]$DestinationGoPath)
    # 保留隔离前已有的签名树检查点；Go 仍会验证签名/一致性，不关闭 GOSUMDB。
    $destinationRoot = Join-Path $DestinationGoPath 'pkg/sumdb'
    foreach ($goPath in $GoPaths) {
        $sourceRoot = Join-Path $goPath 'pkg/sumdb'
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { continue }
        foreach ($checkpoint in Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter 'latest') {
            $relative = $checkpoint.FullName.Substring([IO.Path]::GetFullPath($sourceRoot).Length + 1)
            $target = Join-Path $destinationRoot $relative
            Assert-ChildPath -Parent $destinationRoot -Child $target
            [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
            Copy-Item -LiteralPath $checkpoint.FullName -Destination $target
        }
    }
}

function Assert-Elf {
    param(
        [string]$Path,
        [int]$ExpectedClass,
        [int]$ExpectedMachine,
        [string]$Abi
    )

    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 20 -or $bytes[0] -ne 0x7f -or $bytes[1] -ne 0x45 -or
        $bytes[2] -ne 0x4c -or $bytes[3] -ne 0x46) {
        throw "$Abi 的 libgojni.so 不是 ELF 文件。"
    }
    $machine = [BitConverter]::ToUInt16($bytes, 18)
    if ($bytes[4] -ne $ExpectedClass -or $machine -ne $ExpectedMachine) {
        throw "$Abi 的 ELF 标头不匹配：class=$($bytes[4]) machine=$machine"
    }
}

function Test-Aar {
    param(
        [string]$AarPath,
        [string]$Javap,
        [string]$ReadElf,
        [string]$ValidationRoot,
        [string[]]$PrivatePrefixes
    )

    $extractRoot = Join-Path $ValidationRoot 'aar'
    [IO.Compression.ZipFile]::ExtractToDirectory($AarPath, $extractRoot)
    $expected = [ordered]@{
        'arm64-v8a' = @(2, 183)
        'armeabi-v7a' = @(1, 40)
        'x86' = @(1, 3)
        'x86_64' = @(2, 62)
    }
    $actualAbiFiles = Get-ChildItem -LiteralPath (Join-Path $extractRoot 'jni') -File -Recurse |
        ForEach-Object { $_.FullName.Substring($extractRoot.Length + 1).Replace('\', '/') } |
        Sort-Object
    $expectedAbiFiles = $expected.Keys | ForEach-Object { "jni/$_/libgojni.so" } | Sort-Object
    if (($actualAbiFiles -join "`n") -ne ($expectedAbiFiles -join "`n")) {
        throw "AAR ABI 条目不匹配。实际：$($actualAbiFiles -join ', ')"
    }
    foreach ($abi in $expected.Keys) {
        $elf = Join-Path $extractRoot "jni/$abi/libgojni.so"
        Assert-Elf -Path $elf -ExpectedClass $expected[$abi][0] `
            -ExpectedMachine $expected[$abi][1] -Abi $abi
        $notes = Invoke-Captured -FilePath $ReadElf -Arguments @('--notes', $elf) `
            -WorkingDirectory $ValidationRoot
        if ($notes.IndexOf('.note.android.ident', [StringComparison]::Ordinal) -lt 0 -or
            $notes -notmatch '(?m)description data:\s+18 00 00 00(?:\s|$)') {
            throw "$abi 的 .note.android.ident 不表示 Android API 24。"
        }
    }

    $manifestText = [IO.File]::ReadAllText((Join-Path $extractRoot 'AndroidManifest.xml'))
    if ($manifestText -notmatch '<uses-sdk\s+android:minSdkVersion="24"\s*/>') {
        throw 'AndroidManifest.xml 没有固定 minSdkVersion=24。'
    }

    $classesRoot = Join-Path $ValidationRoot 'classes'
    [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $extractRoot 'classes.jar'), $classesRoot)
    foreach ($file in Get-ChildItem -LiteralPath $extractRoot, $classesRoot -File -Recurse) {
        Assert-NoBuildPaths -Path $file.FullName -Prefixes $PrivatePrefixes
    }
    $api = Invoke-Captured -FilePath $Javap `
        -Arguments @('-classpath', $classesRoot, '-public', $JavaClass) `
        -WorkingDirectory $ValidationRoot
    $requiredApi = @(
        'public static void touch();',
        'public static native void selfTest() throws java.lang.Exception;',
        'public static native void setNetworkHandle(long);',
        'public static native long socketFD4();',
        'public static native long socketFD6();',
        'public static native void start(java.lang.String, java.lang.String, long) throws java.lang.Exception;',
        'public static native java.lang.String status();',
        'public static native void stop();',
        'public static native java.lang.String version();'
    )
    foreach ($signature in $requiredApi) {
        if ($api.IndexOf($signature, [StringComparison]::Ordinal) -lt 0) {
            throw "AAR 缺少预期 Java API：$signature"
        }
    }
    return $api
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$GoExe = Resolve-Executable -Candidate $GoExe -Fallback ('go' + $ExecutableSuffix) -Name 'Go 1.27.3'
$JavaHome = Resolve-Directory -Candidate $JavaHome -EnvironmentName 'JAVA_HOME' `
    -Fallback '' -Name 'JDK 17'
$defaultSdk = [Environment]::GetEnvironmentVariable('ANDROID_SDK_ROOT', 'Process')
$AndroidSdk = Resolve-Directory -Candidate $AndroidSdk -EnvironmentName 'ANDROID_HOME' `
    -Fallback $defaultSdk -Name 'Android SDK'
$defaultNdk = Join-Path $AndroidSdk "ndk/$PinnedNdkRevision"
$AndroidNdk = Resolve-Directory -Candidate $AndroidNdk -EnvironmentName 'ANDROID_NDK_HOME' `
    -Fallback $defaultNdk -Name 'Android NDK r29'
if ([string]::IsNullOrWhiteSpace($Output)) {
    $Output = Join-Path $NativeRoot '../app/libs/atticusengine.aar'
}
$Output = [IO.Path]::GetFullPath($Output)
if ([IO.Path]::GetExtension($Output) -ne '.aar') {
    throw '输出文件必须使用 .aar 扩展名。'
}

$environmentNames = @(
    'ANDROID_HOME', 'ANDROID_NDK_HOME', 'ANDROID_SDK_ROOT', 'ATTICUS_GO_BIN',
    'GOPROXY', 'GOSUMDB', 'GOTOOLCHAIN', 'JAVA_HOME', 'NDK_HOME', 'PATH',
    'GOPATH', 'GOMODCACHE', 'TEMP', 'TMP', 'TMPDIR',
    'CGO_CFLAGS', 'CGO_CPPFLAGS', 'CGO_CXXFLAGS'
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

try {
    $goBin = Split-Path -Parent $GoExe
    $toolPath = $savedEnvironment['PATH']
    $env:PATH = (Join-Path $JavaHome 'bin') + [IO.Path]::PathSeparator + $goBin +
        [IO.Path]::PathSeparator + $toolPath
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $env:ANDROID_NDK_HOME = $AndroidNdk
    $env:NDK_HOME = $AndroidNdk
    $env:ATTICUS_GO_BIN = $goBin
    $env:GOPROXY = 'https://proxy.golang.org,direct'
    $env:GOSUMDB = if ($SumDbDirect) { 'sum.golang.org https://sum.golang.org' } else { 'sum.golang.org' }
    $env:GOTOOLCHAIN = 'local'

    $goVersion = Invoke-Captured -FilePath $GoExe -Arguments @('version')
    if ($goVersion.IndexOf(" $PinnedGoVersion ", [StringComparison]::Ordinal) -lt 0) {
        throw "Go 版本不匹配，需要 $PinnedGoVersion；实际：$goVersion"
    }
    $javac = Join-Path $JavaHome ('bin/javac' + $ExecutableSuffix)
    $javap = Join-Path $JavaHome ('bin/javap' + $ExecutableSuffix)
    foreach ($javaTool in @($javac, $javap)) {
        if (-not (Test-Path -LiteralPath $javaTool -PathType Leaf)) {
            throw "缺少 JDK 工具：$javaTool"
        }
    }
    $javacVersion = Invoke-Captured -FilePath $javac -Arguments @('-version')
    if ($javacVersion -notmatch '^javac 17(?:\.|$)') {
        throw "JDK 版本不匹配，需要 17；实际：$javacVersion"
    }
    $androidJar = Join-Path $AndroidSdk "platforms/android-$PinnedCompileSdk/android.jar"
    if (-not (Test-Path -LiteralPath $androidJar -PathType Leaf)) {
        throw "缺少 Android SDK Platform $PinnedCompileSdk：$androidJar"
    }
    $ndkProperties = Join-Path $AndroidNdk 'source.properties'
    $ndkText = [IO.File]::ReadAllText($ndkProperties)
    if ($ndkText -notmatch "(?m)^Pkg\.Revision\s*=\s*$([regex]::Escape($PinnedNdkRevision))\s*$") {
        throw "NDK 版本不匹配，需要 $PinnedNdkRevision。"
    }
    $readElf = Get-ChildItem -LiteralPath (Join-Path $AndroidNdk 'toolchains/llvm/prebuilt') `
        -Filter ('llvm-readelf' + $ExecutableSuffix) -File -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($readElf)) {
        throw 'NDK 中缺少 llvm-readelf。'
    }
    $strip = Join-Path (Split-Path -Parent $readElf) ('llvm-strip' + $ExecutableSuffix)
    if (-not (Test-Path -LiteralPath $strip -PathType Leaf)) { throw 'NDK 中缺少 llvm-strip。' }

    $moduleCache = (Invoke-Captured -FilePath $GoExe -Arguments @('env', 'GOMODCACHE')).Trim()
    $goRoot = (Invoke-Captured -FilePath $GoExe -Arguments @('env', 'GOROOT')).Trim()
    $initialTemp = [IO.Path]::GetTempPath()
    $initialGopath = (Invoke-Captured -FilePath $GoExe -Arguments @('env', 'GOPATH')).Trim()

    Write-Host '下载并校验固定依赖（GOSUMDB 保持启用）...'
    Invoke-Checked -FilePath $GoExe -Arguments @('mod', 'download')
    Invoke-Checked -FilePath $GoExe -Arguments @('mod', 'verify')
    Invoke-Checked -FilePath $GoExe -Arguments @('test', '-count=1', './...')
    Invoke-Checked -FilePath $GoExe -Arguments @('vet', './...')

    $mobileJson = Invoke-Captured -FilePath $GoExe `
        -Arguments @('mod', 'download', '-json', "golang.org/x/mobile@$PinnedMobileVersion")
    $mobile = $mobileJson | ConvertFrom-Json
    if ($mobile.Version -ne $PinnedMobileVersion -or $mobile.Sum -ne $PinnedMobileSum -or
        $mobile.GoModSum -ne $PinnedMobileGoModSum) {
        throw 'x/mobile 版本或 sum.golang.org 校验和不匹配。'
    }

    $WorkRoot = Join-Path ([IO.Path]::GetTempPath()) ("atticusvpn-aar-" + [guid]::NewGuid())
    [IO.Directory]::CreateDirectory($WorkRoot) | Out-Null
    # 源模块和 gomobile 临时目录处于同一卷；相对 replace 不包含宿主用户名或临时 GUID。
    $nativeCopy = Join-Path $WorkRoot 'native-engine'
    [IO.Directory]::CreateDirectory($nativeCopy) | Out-Null
    foreach ($inputFile in Get-ChildItem -LiteralPath $NativeRoot -Recurse -File) {
        $relativeInput = $inputFile.FullName.Substring($NativeRoot.Length + 1)
        if ($relativeInput -match '(^|[\\/])(?:\.git|build|\.gradle)([\\/]|$)' -or
            ($inputFile.Extension -ne '.go' -and $inputFile.Name -notin @('go.mod', 'go.sum'))) { continue }
        $inputTarget = Join-Path $nativeCopy $relativeInput
        [IO.Directory]::CreateDirectory((Split-Path -Parent $inputTarget)) | Out-Null
        Copy-Item -LiteralPath $inputFile.FullName -Destination $inputTarget
    }
    $mobileCopy = Join-Path $WorkRoot 'x-mobile'
    Copy-Item -LiteralPath $mobile.Dir -Destination $mobileCopy -Recurse
    Get-ChildItem -LiteralPath $mobileCopy -File -Recurse | ForEach-Object { $_.IsReadOnly = $false }
    Patch-MobileForPrivacy -MobileRoot $mobileCopy

    $env:TEMP = $WorkRoot
    $env:TMP = $WorkRoot
    $env:TMPDIR = $WorkRoot
    $env:GOPATH = Join-Path $WorkRoot 'gopath'
    $env:GOMODCACHE = $moduleCache
    [IO.Directory]::CreateDirectory((Join-Path $env:GOPATH 'pkg/gomobile')) | Out-Null
    Copy-SumDbCheckpoints -GoPaths @($initialGopath -split [regex]::Escape([string][IO.Path]::PathSeparator)) -DestinationGoPath $env:GOPATH
    $privatePrefixes = @($NativeRoot, $nativeCopy, $mobileCopy, $WorkRoot, $initialTemp,
        $moduleCache, $goRoot, $JavaHome, $AndroidSdk, $AndroidNdk,
        [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)) +
        @($initialGopath -split [regex]::Escape([string][IO.Path]::PathSeparator))
    $privacyFlags = Get-PrivacyFlags -Prefixes $privatePrefixes
    # -trimpath 同时避免 Go build-info 再记录含绝对目录的 CGO flags。
    foreach ($flagName in @('CGO_CFLAGS', 'CGO_CPPFLAGS', 'CGO_CXXFLAGS')) {
        [Environment]::SetEnvironmentVariable($flagName, $privacyFlags, 'Process')
    }

    $toolsRoot = Join-Path $WorkRoot 'tools'
    [IO.Directory]::CreateDirectory($toolsRoot) | Out-Null
    $gomobile = Join-Path $toolsRoot ('gomobile' + $ExecutableSuffix)
    $gobind = Join-Path $toolsRoot ('gobind' + $ExecutableSuffix)
    $normalizer = Join-Path $toolsRoot ('normalizeaar' + $ExecutableSuffix)
    Invoke-Checked -FilePath $GoExe `
        -Arguments @('build', '-trimpath', '-o', $gomobile, './cmd/gomobile') `
        -WorkingDirectory $mobileCopy
    Invoke-Checked -FilePath $GoExe `
        -Arguments @('build', '-trimpath', '-o', $gobind, './cmd/gobind') `
        -WorkingDirectory $mobileCopy
    Invoke-Checked -FilePath $GoExe `
        -Arguments @('build', '-trimpath', '-ldflags=-buildid=', '-o', $normalizer, './cmd/normalizeaar') `
        -WorkingDirectory $nativeCopy
    $env:PATH = $toolsRoot + [IO.Path]::PathSeparator + $env:PATH

    $rawAar = Join-Path $WorkRoot 'atticusengine.raw.aar'
    Write-Host '构建 Android API 24、四 ABI AAR...'
    Invoke-Checked -FilePath $gomobile -Arguments @(
        'bind', '-target=android', "-androidapi=$MinimumAndroidApi",
        "-javapkg=$JavaPackage", '-trimpath', '-ldflags=-buildid=', '-o', $rawAar, '.'
    ) -WorkingDirectory $nativeCopy

    # 删除 C/Go 调试节，不剥离 Java API 或 JNI 必需符号；随后验证路径隐私和 ABI。
    $validationRoot = Join-Path $WorkRoot 'validation'
    [IO.Directory]::CreateDirectory($validationRoot) | Out-Null
    $strippedAar = Protect-RawAar -AarPath $rawAar -Strip $strip -ValidationRoot $validationRoot
    $normalizedAar = Join-Path $WorkRoot 'atticusengine.aar'
    Invoke-Checked -FilePath $normalizer -Arguments @('-input', $strippedAar, '-output', $normalizedAar)
    $api = Test-Aar -AarPath $normalizedAar -Javap $javap -ReadElf $readElf `
        -ValidationRoot $validationRoot -PrivatePrefixes $privatePrefixes

    $outputDirectory = Split-Path -Parent $Output
    [IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
    $stagedOutput = Join-Path $outputDirectory ('.atticusengine-' + [guid]::NewGuid() + '.tmp')
    [IO.File]::Copy($normalizedAar, $stagedOutput, $true)
    try {
        # 产物已经完成全部验证；覆盖只发生在最后一步，兼容 Windows PowerShell 5.1。
        [IO.File]::Copy($stagedOutput, $Output, $true)
        Remove-Item -LiteralPath $stagedOutput -Force
    }
    finally {
        if (Test-Path -LiteralPath $stagedOutput) {
            Remove-Item -LiteralPath $stagedOutput -Force
        }
    }

    $hash = (Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash
    Write-Host "AAR：$Output"
    Write-Host 'ABI：arm64-v8a, armeabi-v7a, x86, x86_64'
    Write-Host "SHA256：$hash"
    Write-Host "Java API：`n$api"
}
finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
    if ($null -ne $WorkRoot -and (Test-Path -LiteralPath $WorkRoot)) {
        if ($KeepWork) {
            Write-Host "保留临时目录：$WorkRoot"
        }
        else {
            $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
                [IO.Path]::DirectorySeparatorChar
            ) + [IO.Path]::DirectorySeparatorChar
            $workFull = [IO.Path]::GetFullPath($WorkRoot)
            if (-not $workFull.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
                -not ([IO.Path]::GetFileName($workFull)).StartsWith('atticusvpn-aar-', [StringComparison]::Ordinal)) {
                throw "拒绝清理不安全的临时目录：$workFull"
            }
            Remove-Item -LiteralPath $workFull -Recurse -Force
        }
    }
}
