# Copyright (c) 2026 TianzeL
# SPDX-License-Identifier: Apache-2.0

# 离线测试脚本纯函数：不调用 Go/NDK，不下载、修改缓存、操作手机或运行实际构建。
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskScript = Join-Path $PSScriptRoot '../build-aar.ps1'
$taskTokens = $null
$taskParseErrors = $null
$taskAst = [Management.Automation.Language.Parser]::ParseFile(
    [IO.Path]::GetFullPath($taskScript), [ref]$taskTokens, [ref]$taskParseErrors)
if ($taskParseErrors.Count -ne 0) { throw '构建脚本语法检查失败。' }
foreach ($taskFunction in $taskAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.FunctionDefinitionAst]
}, $false)) {
    Invoke-Expression $taskFunction.Extent.Text
}
$taskPassed = 0
function Assert-Test([bool]$Condition, [string]$Description) {
    if (-not $Condition) { throw "FAIL: $Description" }
    $script:taskPassed++
}
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('atticusvpn-build-test-' + [guid]::NewGuid())
[IO.Directory]::CreateDirectory($taskTemp) | Out-Null
try {
    $taskMobile = Join-Path $taskTemp 'mobile'
    $taskCode = Join-Path $taskMobile 'cmd/gomobile'
    [IO.Directory]::CreateDirectory($taskCode) | Out-Null
    # 最小兼容夹具仅验证固定补丁的定位及效果，不是 vendored 上游实现。
    $taskAndroidFixture = @'
package main
import (
	"path/filepath"
	"strings"
)
func fixture() {
	cmd.Env = append(cmd.Env, "GOOS=android")
	cmd.Env = append(cmd.Env, "CGO_ENABLED=1")
	_ = []string{
		"-d", dst,
		"-source", javacTargetVer,
	}
}
'@.Replace('\t', "`t")
    $taskModuleFixture = @'
package main
func fixture() {
		bs, err := f.Format()
}
'@.Replace('\t', "`t")
    [IO.File]::WriteAllText((Join-Path $taskCode 'bind_androidapp.go'), $taskAndroidFixture,
        [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $taskCode 'bind.go'), $taskModuleFixture,
        [Text.UTF8Encoding]::new($false))
    Patch-MobileForPrivacy -MobileRoot $taskMobile
    $taskPatched = [IO.File]::ReadAllText((Join-Path $taskCode 'bind_androidapp.go'))
    $taskModulePatched = [IO.File]::ReadAllText((Join-Path $taskCode 'bind.go'))
    Assert-Test ($taskPatched.Contains('append(os.Environ()')) 'child environment preserved'
    Assert-Test ($taskPatched.Contains('"-encoding", "UTF-8"')) 'javac UTF-8'
    Assert-Test ($taskModulePatched.Contains('filepath.Rel(dir, replacement.New.Path)')) 'local replacements relative'
    Assert-Test ($taskModulePatched.Contains('f.AddReplace(')) 'module syntax updated using AddReplace'
    Assert-Test ($taskModulePatched.Contains('AtticusVPN modification')) 'temporary upstream modification marked'
    $taskRepeatRejected = $false
    try { Patch-MobileForPrivacy -MobileRoot $taskMobile } catch { $taskRepeatRejected = $true }
    Assert-Test $taskRepeatRejected 'pinned patch precondition fails if applied twice'

    $taskPrefix = Join-Path $taskTemp 'input with spaces'
    $taskFlags = Get-PrivacyFlags -Prefixes @($taskPrefix, $taskPrefix)
    Assert-Test ($taskFlags.Contains('"-ffile-prefix-map=')) 'file-prefix flags quoted'
    Assert-Test ($taskFlags.Contains('"-fdebug-prefix-map=')) 'debug-prefix flags quoted'
    Assert-Test (([regex]::Matches($taskFlags, '-ffile-prefix-map=').Count) -eq 1) 'duplicate prefix removed'
    $taskBinary = Join-Path $taskTemp 'fixture.bin'
    [IO.File]::WriteAllText($taskBinary, 'safe relative ../native-engine public module path',
        [Text.UTF8Encoding]::new($false))
    Assert-NoBuildPaths -Path $taskBinary -Prefixes @($taskPrefix)
    Assert-Test $true 'relative module path accepted'
    [IO.File]::WriteAllText($taskBinary, $taskPrefix.Replace('\', '/'),
        [Text.UTF8Encoding]::new($false))
    $taskLeakRejected = $false
    try { Assert-NoBuildPaths -Path $taskBinary -Prefixes @($taskPrefix) } catch { $taskLeakRejected = $true }
    Assert-Test $taskLeakRejected 'UTF-8 build-prefix leak rejected'
    [IO.File]::WriteAllText($taskBinary, $taskPrefix, [Text.Encoding]::Unicode)
    $taskWideRejected = $false
    try { Assert-NoBuildPaths -Path $taskBinary -Prefixes @($taskPrefix) } catch { $taskWideRejected = $true }
    Assert-Test $taskWideRejected 'UTF-16 build-prefix leak rejected'

    $taskOldGopath = Join-Path $taskTemp 'old-gopath'
    $taskNewGopath = Join-Path $taskTemp 'isolated-gopath'
    $taskCheckpoint = Join-Path $taskOldGopath 'pkg/sumdb/sum.golang.org/latest'
    [IO.Directory]::CreateDirectory((Split-Path -Parent $taskCheckpoint)) | Out-Null
    [IO.File]::WriteAllText($taskCheckpoint, 'offline checkpoint fixture, not a signed tree')
    Copy-SumDbCheckpoints -GoPaths @((Join-Path $taskTemp 'missing-gopath'), $taskOldGopath) `
        -DestinationGoPath $taskNewGopath
    $taskCopiedCheckpoint = Join-Path $taskNewGopath 'pkg/sumdb/sum.golang.org/latest'
    Assert-Test (Test-Path -LiteralPath $taskCopiedCheckpoint -PathType Leaf) 'checkpoint copied into isolated GOPATH'
    Assert-Test ([IO.File]::ReadAllText($taskCopiedCheckpoint) -eq [IO.File]::ReadAllText($taskCheckpoint)) `
        'checkpoint bytes preserved for Go signature validation'
    Assert-Test (Test-Path -LiteralPath $taskCheckpoint -PathType Leaf) 'original checkpoint preserved'
    Write-Host "Build-script tests passed: $taskPassed"
}
finally {
    $taskAbsolute = [IO.Path]::GetFullPath($taskTemp)
    $taskExpectedRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
        [IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $taskAbsolute.StartsWith($taskExpectedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($taskAbsolute)).StartsWith('atticusvpn-build-test-')) {
        throw '拒绝清理不安全的测试临时目录。'
    }
    Remove-Item -LiteralPath $taskAbsolute -Recurse -Force
}
