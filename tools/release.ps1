#Requires -Version 5.1
<#
.SYNOPSIS
    lizl-java-data-analyzer 一键 Release：前置校验 -> 构建打包 -> 推送 -> 打标签 -> 建 Release -> 回查。

.DESCRIPTION
    严格模式：任一步骤失败立即中止，输出失败步骤、执行命令、退出码、原始输出、
    已完成范围、未执行范围，以及在失败前已产生的不可逆远端变更与对应回滚命令。

    发布的既有范式：
      - 版本号唯一来源：**本地标签 vX.Y.Z 指向提交**中 pom.xml 的项目 <version>
        （从标签解析版本，而非工作区——因此支持发布任意历史版本）
      - 标签：由用户在本机手工创建（轻量标签 vX.Y.Z，非 annotated）。
        脚本只读取、校验并在确认后推送；远端已存在且指向一致时跳过推送
      - 构建：git worktree 独立检出标签提交后 mvn clean verify -P release，
        主工作区不受影响（不含签名，jar 不发 Maven Central）
      - 附件：主 jar，标准命名 lizl-java-data-analyzer-X.Y.Z.jar（无 v 前缀），双平台均上传
      - GitHub / Gitee Release：name 为 vX.Y.Z，body 为标签指向提交的完整提交信息

.PARAMETER DryRun
    只执行到"校验产物"为止：不做发布确认，不产生任何推送或远端写操作。
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# curl 输出为 UTF-8，需与宿主控制台一致，否则 JSON 中的中文会乱码
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$OutputEncoding = [Text.Encoding]::UTF8

# ---------------------------------------------------------------- 常量与状态

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$ConfigPath = Join-Path $RepoRoot 'release.local.json'
$PomPath    = Join-Path $RepoRoot 'pom.xml'
$PublishDir = Join-Path $RepoRoot 'publish'

# 若从 Git Bash 环境链路启动，MSYS 会把 "C:\..." 参数改写成 POSIX 路径，致 curl -F 找不到文件；
# 此变量关闭该转换（在非 MSYS 环境无副作用）
$env:MSYS_NO_PATHCONV = '1'
# 附件走相对路径上传，统一以仓库根为基准
Set-Location -LiteralPath $RepoRoot

$GitHubRemote = 'github'
$GiteeRemote  = 'gitee'
$Branch       = 'main'

# JDK8 已知安装位置优先（本项目 target 1.8，环境变量 JAVA_HOME 可能指向其他大版本）
$JdkCandidates = @(
    'C:\Users\snown\.jdks\corretto-1.8.0_442',
    $(if ($env:JAVA_HOME -match '1\.8') { $env:JAVA_HOME } else { '' })
)
$MavenCandidates = @(
    (Join-Path $env:USERPROFILE 'DSpace\Programs\apache-maven-3.9.9\bin\mvn.cmd'),
    'mvn'
)

$TotalSteps = 26
$script:StepIndex     = 0
$script:CurrentStep   = ''
$script:Completed     = New-Object System.Collections.ArrayList
$script:RemoteChanges = New-Object System.Collections.ArrayList
$script:Secrets       = New-Object System.Collections.ArrayList

$script:LastCommand = ''
$script:LastOutput  = ''
$script:LastExit    = 0

# ---------------------------------------------------------------- 输出与断言

<#
    令牌脱敏：命令行与原始输出在打印前统一抹掉令牌，
    避免失败报告把 Authorization 头或 access_token 写入控制台、截图或日志。
#>
function Protect-Text {
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }

    $result = $Text
    foreach ($secret in $script:Secrets) {
        if (-not [string]::IsNullOrEmpty($secret) -and $secret.Length -ge 8) {
            $result = $result.Replace($secret, '***REDACTED***')
        }
    }
    $result = [regex]::Replace($result, '(?i)(Bearer\s+)[^\s"'']+', '$1***REDACTED***')
    $result = [regex]::Replace($result, '(?i)(access_token=)[^&\s"'']+', '$1***REDACTED***')
    return $result
}

function Write-Head {
    param([string]$Text)
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkGray
    Write-Host (' ' + $Text) -ForegroundColor White
    Write-Host ('=' * 72) -ForegroundColor DarkGray
}

function Step {
    param([string]$Description)
    $script:StepIndex++
    $script:CurrentStep = $Description
    Write-Host ''
    Write-Host ('[{0}/{1}] {2}' -f $script:StepIndex, $TotalSteps, $Description) -ForegroundColor Cyan
}

function Complete-Step {
    param([string]$Detail = '')
    $null = $script:Completed.Add([pscustomobject]@{
        Index  = $script:StepIndex
        Text   = $script:CurrentStep
        Detail = $Detail
    })
    if ([string]::IsNullOrWhiteSpace($Detail)) {
        Write-Host '       OK' -ForegroundColor DarkGreen
    }
    else {
        Write-Host ('       OK  {0}' -f $Detail) -ForegroundColor DarkGreen
    }
}

function Skip-Step {
    param([string]$Why)
    $null = $script:Completed.Add([pscustomobject]@{
        Index  = $script:StepIndex
        Text   = $script:CurrentStep
        Detail = '已跳过：' + $Why
    })
    Write-Host ('       跳过：{0}' -f $Why) -ForegroundColor Yellow
}

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message,
        [string]$Evidence = ''
    )
    if ($Condition) { return }
    $script:LastCommand = ''
    $script:LastOutput  = $Evidence
    $script:LastExit    = -1
    throw $Message
}

# ---------------------------------------------------------------- 命令执行

function Invoke-Native {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$Label
    )

    $display = Protect-Text ($FilePath + ' ' + ($ArgumentList -join ' '))
    $script:LastCommand = $display
    Write-Host ('       > {0}' -f $display) -ForegroundColor DarkGray

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $FilePath @ArgumentList 2>&1 | ForEach-Object { [string]$_ }
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }

    $script:LastOutput = Protect-Text (($raw | Out-String).Trim())
    $script:LastExit   = $code

    if ($code -ne 0) {
        throw ('{0} 失败（退出码 {1}）' -f $Label, $code)
    }
    return $script:LastOutput
}

function Invoke-GitGitHub {
    param([string[]]$ArgumentList, [string]$Label)
    # 本机 schannel 吊销检查失效（CRYPT_E_NO_REVOCATION_CHECK），仅对本次命令关闭证书校验，
    # 不写入任何 git config。
    return Invoke-Native 'git' (@('-c', 'http.sslVerify=false') + $ArgumentList) $Label
}

# ---------------------------------------------------------------- HTTP

function Invoke-Http {
    param(
        [string]$Url,
        [string[]]$ExtraArgs = @(),
        [string]$Label,
        [int]$MaxTime = 120
    )

    $args = @('-sS', '--ssl-no-revoke', '--max-time', [string]$MaxTime,
              '-w', '\n%{http_code}') + $ExtraArgs + @($Url)

    $raw = Invoke-Native 'curl.exe' $args $Label
    $lines = $raw -split "`r?`n"

    $codeText = $lines[$lines.Length - 1].Trim()
    $status = 0
    if (-not [int]::TryParse($codeText, [ref]$status)) {
        $script:LastCommand = 'curl.exe ... ' + $Url
        $script:LastOutput  = $raw
        $script:LastExit    = -1
        throw ('{0} 返回内容无法解析出 HTTP 状态码' -f $Label)
    }

    if ($lines.Length -gt 1) {
        $body = ($lines[0..($lines.Length - 2)] -join "`n").Trim()
    }
    else {
        $body = ''
    }

    $script:LastOutput = $body
    $script:LastExit   = $status

    if ($status -lt 200 -or $status -ge 300) {
        throw ('{0} 失败（HTTP {1}）' -f $Label, $status)
    }
    return $body
}

# ---------------------------------------------------------------- 前置辅助

<#
    git merge-base --is-ancestor 用退出码表达布尔，不能用 Invoke-Native（它在非零退出码时抛异常）。
#>
function Test-GitAncestor {
    param([string]$Ancestor, [string]$Descendant)

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $null = & git merge-base --is-ancestor $Ancestor $Descendant 2>$null
        return ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

function Get-RemoteSlug {
    param([string]$Remote)

    $url = (Invoke-Native 'git' @('remote', 'get-url', $Remote) ('读取远端 {0} 地址' -f $Remote)).Trim()
    $pattern = '^(?:https?://[^/]+/|[^@\s]+@[^:]+:)(?<owner>[^/]+)/(?<repo>[^/]+?)(?:\.git)?/?$'
    $m = [regex]::Match($url, $pattern)
    if (-not $m.Success) {
        throw ('无法从远端地址解析出 owner/repo：{0}' -f $url)
    }
    return [pscustomobject]@{ Owner = $m.Groups['owner'].Value; Repo = $m.Groups['repo'].Value; Url = $url }
}

function Get-PomVersion {
    <# 取项目自身 version：project 根下的 <version>（排除 dependency 内的节点）。 #>
    $node = Select-Xml -Path $PomPath -XPath '/*[local-name()="project"]/*[local-name()="version"]' | Select-Object -First 1
    if (-not $node) {
        throw ('pom.xml 中找不到项目 <version> 节点')
    }
    return $node.Node.InnerText.Trim()
}

function Find-Tool {
    param(
        [string[]]$Candidates,
        [string[]]$ProbeArgs,
        [string]$Description
    )

    $tried = New-Object System.Collections.ArrayList
    foreach ($candidate in $Candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        if ($candidate -notmatch '[\\/]') {
            $resolved = Get-Command $candidate -ErrorAction SilentlyContinue
            if ($null -ne $resolved) { return $resolved.Source }
            $null = $tried.Add($candidate)
            continue
        }
        if (-not (Test-Path -LiteralPath $candidate)) {
            $null = $tried.Add($candidate + '（不存在）')
            continue
        }
        if ($ProbeArgs.Count -gt 0) {
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $null = & $candidate @ProbeArgs 2>$null
                if ($LASTEXITCODE -ne 0) {
                    $null = $tried.Add($candidate + '（探测命令失败）')
                    continue
                }
            }
            catch {
                $null = $tried.Add($candidate + '（无法执行）')
                continue
            }
            finally { $ErrorActionPreference = $previous }
        }
        return $candidate
    }

    $script:LastCommand = ''
    $script:LastOutput  = '已尝试：' + "`n" + ($tried -join "`n")
    $script:LastExit    = -1
    throw ('未找到 {0}' -f $Description)
}

function Exit-Failure {
    param(
        [string]$Message,
        [System.Management.Automation.ErrorRecord]$Record
    )

    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor Red
    Write-Host ' 执行中断' -ForegroundColor Red
    Write-Host ('=' * 72) -ForegroundColor Red
    Write-Host ''
    Write-Host ('失败步骤：  [{0}/{1}] {2}' -f $script:StepIndex, $TotalSteps, $script:CurrentStep) -ForegroundColor Red
    Write-Host ('失败原因：  {0}' -f $Message) -ForegroundColor Red

    if (-not [string]::IsNullOrWhiteSpace($script:LastCommand)) {
        Write-Host ('执行命令：  {0}' -f $script:LastCommand) -ForegroundColor Yellow
        Write-Host ('退出码：    {0}' -f $script:LastExit) -ForegroundColor Yellow
    }

    if (-not [string]::IsNullOrWhiteSpace($script:LastOutput)) {
        Write-Host ''
        Write-Host '原始输出：' -ForegroundColor Yellow
        foreach ($line in ($script:LastOutput -split "`r?`n")) {
            Write-Host ('  | ' + $line) -ForegroundColor DarkYellow
        }
    }

    if ($Record -and $Record.Exception -and $Record.Exception.StackTrace) {
        Write-Host ''
        Write-Host '调用栈：' -ForegroundColor DarkGray
        foreach ($line in ($Record.Exception.StackTrace -split "`r?`n")) {
            Write-Host ('  ' + $line.Trim()) -ForegroundColor DarkGray
        }
    }

    Write-Host ''
    Write-Host '已完成步骤：' -ForegroundColor Yellow
    if ($script:Completed.Count -eq 0) {
        Write-Host '  （无）' -ForegroundColor DarkGray
    }
    else {
        foreach ($item in $script:Completed) {
            $text = ('  [{0}] {1}' -f $item.Index, $item.Text)
            if ($item.Detail) { $text = $text + '  —— ' + $item.Detail }
            Write-Host $text -ForegroundColor DarkGray
        }
    }

    Write-Host ''
    Write-Host '未执行步骤：' -ForegroundColor Yellow
    $from = $script:StepIndex + 1
    if ($from -gt $TotalSteps) {
        Write-Host '  （无）' -ForegroundColor DarkGray
    }
    else {
        Write-Host ('  [{0}] 至 [{1}] 全部未执行' -f $from, $TotalSteps) -ForegroundColor DarkGray
    }

    Write-Host ''
    Write-Host '不可逆远端变更：' -ForegroundColor Yellow
    if ($script:RemoteChanges.Count -eq 0) {
        Write-Host '  （无，远端未发生任何改动，修正问题后可直接重跑）' -ForegroundColor DarkGray
    }
    else {
        foreach ($item in $script:RemoteChanges) {
            Write-Host ('  - {0}' -f $item) -ForegroundColor DarkYellow
        }
        Write-Host ''
        Write-Host '回滚命令（确认无误后手工执行）：' -ForegroundColor Yellow
        foreach ($cmd in $script:RollbackHints) {
            Write-Host ('  ' + $cmd) -ForegroundColor DarkGray
        }
    }

    Write-Host ''
    exit 1
}

# ---------------------------------------------------------------- 主流程

Write-Head ('lizl-java-data-analyzer 一键 Release  ' + $(if ($DryRun) { '（DryRun：不写远端）' } else { '' }))

$config = $null
$githubSlug = $null
$giteeSlug = $null
$githubTagExists = $false
$giteeTagExists = $false
$version = ''
$tag = ''
$targetSha = ''
$targetShort = ''
$targetSubject = ''
$mvn = ''
$assetPath = ''
$githubReleaseId = ''
$script:RollbackHints = New-Object System.Collections.ArrayList

try {
    # ---- 阶段 0：前置校验 -------------------------------------------------

    Step '加载 release.local.json 并校验令牌'
    if (Test-Path -LiteralPath $ConfigPath) {
        $config = Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($key in @('githubToken', 'giteeToken')) {
            $prop = $config.PSObject.Properties[$key]
            Assert-True ($null -ne $prop -and -not [string]::IsNullOrWhiteSpace([string]$prop.Value)) `
                ('配置项 {0} 缺失或为空' -f $key)
        }
        $null = $script:Secrets.Add([string]$config.githubToken)
        $null = $script:Secrets.Add([string]$config.giteeToken)
        Complete-Step '令牌已载入（不打印内容）'
    }
    else {
        Assert-True ([bool]$DryRun) `
            ('缺少配置文件 {0}，请从 release.local.json.example 复制后填入令牌' -f (Split-Path $ConfigPath -Leaf))
        $config = [pscustomobject]@{ githubToken = ''; giteeToken = '' }
        Skip-Step 'DryRun：配置文件不存在，使用空令牌'
    }

    Step '在线校验 GitHub 令牌'
    if ($DryRun) {
        Skip-Step 'DryRun 不接触远端'
    }
    else {
        $who = Invoke-Http -Url 'https://api.github.com/user' -ExtraArgs @(
            '-H', ('Authorization: Bearer ' + $config.githubToken),
            '-H', 'Accept: application/vnd.github+json'
        ) -Label '校验 GitHub 令牌'
        $whoObj = $who | ConvertFrom-Json
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$whoObj.login)) 'GitHub 令牌校验未返回用户信息' $who
        Complete-Step ('身份 ' + $whoObj.login)
    }

    Step '在线校验 Gitee 令牌'
    if ($DryRun) {
        Skip-Step 'DryRun 不接触远端'
    }
    else {
        $who2 = Invoke-Http `
            -Url ('https://gitee.com/api/v5/user?access_token=' + [uri]::EscapeDataString($config.giteeToken)) `
            -Label '校验 Gitee 令牌'
        $who2Obj = $who2 | ConvertFrom-Json
        Assert-True (-not [string]::IsNullOrWhiteSpace([string]$who2Obj.login)) 'Gitee 令牌校验未返回用户信息' $who2
        Complete-Step ('身份 ' + $who2Obj.login)
    }

    Step '解析两个远端的 owner/repo'
    $githubSlug = Get-RemoteSlug $GitHubRemote
    $giteeSlug  = Get-RemoteSlug $GiteeRemote
    Complete-Step ('GitHub {0}/{1} · Gitee {2}/{3}' -f $githubSlug.Owner, $githubSlug.Repo, $giteeSlug.Owner, $giteeSlug.Repo)

    Step '解析本地版本标签与发布版本'
    # 版本从"标签 -> 提交 -> 该提交的 pom"解析，而非工作区：支持发布任意历史版本
    $tagList = Invoke-Native 'git' @('tag', '-l', 'v*') '列出本地版本标签'
    $candidates = @($tagList -split "`r?`n" | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | Sort-Object)
    Assert-True ($candidates.Count -ge 1) '本地不存在任何 vX.Y.Z 版本标签，请先手工创建（git tag vX.Y.Z [<提交>]）'
    $tag = if ($candidates.Count -eq 1) { $candidates[0] } else { $candidates[$candidates.Count - 1] }
    if ($candidates.Count -gt 1) {
        Write-Host ('       发现 {0} 个版本标签，取最高 {1}（发布旧版请先删除更高标签或只保留目标标签）' -f $candidates.Count, $tag) -ForegroundColor Yellow
    }

    $tagRef = 'refs/tags/' + $tag
    $targetSha = (Invoke-Native 'git' @('rev-parse', ($tagRef + '^{commit}')) ('解析标签 {0} 指向的提交' -f $tag)).Trim()
    Assert-True ($targetSha -match '^[0-9a-f]{40}$') ('标签 {0} 无法解析为提交' -f $tag) $targetSha
    $targetShort = $targetSha.Substring(0, 7)
    $targetSubject = (Invoke-Native 'git' @('log', '-1', '--format=%s', $targetSha) '读取标签指向提交的标题').Trim()
    # Release 描述 = 标签指向提交的完整提交信息（标题 + body）
    $commitBody = (Invoke-Native 'git' @('log', '-1', '--format=%B', $targetSha) '读取标签指向提交的完整信息').Trim()

    Assert-True (Test-GitAncestor $targetSha 'HEAD') `
        ('标签 {0} 指向的提交 {1} 不在当前分支历史中' -f $tag, $targetShort) $targetSubject

    $targetPom = Invoke-Native 'git' @('show', ('{0}:pom.xml' -f $targetSha)) `
        ('读取提交 {0} 的 pom.xml' -f $targetShort)
    $versionMatch = [regex]::Match($targetPom, '(?s)<project[^>]*>.*?<version>\s*([\d.]+)\s*</version>')
    Assert-True $versionMatch.Success ('标签 {0} 指向的提交中找不到项目 version' -f $tag)
    $version = $versionMatch.Groups[1].Value
    Assert-True ($version -match '^\d+\.\d+\.\d+$') ('版本号格式非法：{0}（期望 X.Y.Z）' -f $version)
    Assert-True ($tag -eq ('v' + $version)) `
        ('标签 {0} 与其指向提交的 pom 版本 {1} 不一致' -f $tag, $version) `
        ('提交 {0} 的 pom 版本：{1}' -f $targetShort, $version)

    Complete-Step ('版本 {0} · {1} → {2}  {3}' -f $version, $tag, $targetShort, $targetSubject)

    Step '检查工作区是否干净'
    $status = Invoke-Native 'git' @('status', '--porcelain') '检查工作区'
    Assert-True ([string]::IsNullOrWhiteSpace($status)) '工作区存在未提交的改动，请先提交或还原' $status
    Complete-Step '无未提交改动'

    Step ('检查当前分支为 {0}' -f $Branch)
    $current = (Invoke-Native 'git' @('rev-parse', '--abbrev-ref', 'HEAD') '读取当前分支').Trim()
    Assert-True ($current -eq $Branch) ('当前分支为 {0}，期望 {1}' -f $current, $Branch) $current
    Complete-Step $current

    Step '检查 GitHub 远端同名标签'
    $remoteTag = Invoke-GitGitHub @('ls-remote', '--tags', $GitHubRemote, ('refs/tags/' + $tag)) '查询 GitHub 远端标签'
    $githubTagExists = -not [string]::IsNullOrWhiteSpace($remoteTag)
    if ($githubTagExists) {
        Assert-True ($remoteTag -match $targetSha) `
            ('GitHub 远端标签 {0} 指向不同提交，疑似标签被改写，中止' -f $tag) $remoteTag
        Skip-Step '远端已存在且指向一致，稍后跳过推送'
    }
    else { Complete-Step '不存在，稍后推送' }

    Step '检查 Gitee 远端同名标签'
    $remoteTag2 = Invoke-Native 'git' @('ls-remote', '--tags', $GiteeRemote, ('refs/tags/' + $tag)) '查询 Gitee 远端标签'
    $giteeTagExists = -not [string]::IsNullOrWhiteSpace($remoteTag2)
    if ($giteeTagExists) {
        Assert-True ($remoteTag2 -match $targetSha) `
            ('Gitee 远端标签 {0} 指向不同提交，疑似标签被改写，中止' -f $tag) $remoteTag2
        Skip-Step '远端已存在且指向一致，稍后跳过推送'
    }
    else { Complete-Step '不存在，稍后推送' }

    Step '定位工具链（JDK8 / Maven）'
    $javaHome = ''
    foreach ($candidate in $JdkCandidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe'))) {
            $javaHome = $candidate
            break
        }
    }
    Assert-True (-not [string]::IsNullOrWhiteSpace($javaHome)) '未找到 JDK8（已知安装位置与指向 1.8 的 JAVA_HOME 均无效）'
    $mvn = Find-Tool -Candidates $MavenCandidates -ProbeArgs @('-v') -Description 'Maven (mvn.cmd)'
    Complete-Step ('JAVA_HOME={0} · mvn={1}' -f $javaHome, $mvn)

    # ---- 阶段 1：构建打包 -------------------------------------------------

    Step 'git worktree 独立检出发布提交'
    # 主工作区不动：在临时目录检出标签提交构建，保证产物与标签内容严格一致
    $worktreeDir = Join-Path ([IO.Path]::GetTempPath()) ('lizl-release-worktree-' + $version)
    if (Test-Path -LiteralPath $worktreeDir) {
        $null = Invoke-Native 'git' @('worktree', 'remove', '--force', $worktreeDir) '清理残留 worktree'
    }
    $null = Invoke-Native 'git' @('worktree', 'add', '--detach', $worktreeDir, $targetSha) '检出发布提交'
    Complete-Step $worktreeDir

    Step 'mvn clean verify（release profile，含测试，不含签名）'
    $env:JAVA_HOME = $javaHome
    try {
        $buildOut = Invoke-Native $mvn @('-B', 'clean', 'verify', '-P', 'release', '-f', (Join-Path $worktreeDir 'pom.xml')) '构建与测试'
        $testMatch = [regex]::Match($buildOut, 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)')

        Step '校验产物并复制版本化附件'
        $mainJar = Join-Path $worktreeDir ('target\lizl-java-data-analyzer-' + $version + '.jar')
        Assert-True (Test-Path -LiteralPath $mainJar) ('未找到产物 {0}' -f $mainJar)
        $jarInfo = Get-Item -LiteralPath $mainJar
        Assert-True ($jarInfo.Length -gt 10KB) ('主 jar 仅 {0:N0} 字节，明显异常（可能残留或构建失败）' -f $jarInfo.Length)

        $assetName = 'lizl-java-data-analyzer-' + $version + '.jar'
        $assetPath = Join-Path $PublishDir $assetName
        if (-not (Test-Path -LiteralPath $PublishDir)) { $null = New-Item -ItemType Directory -Path $PublishDir | Out-Null }
        Copy-Item -LiteralPath $mainJar -Destination $assetPath -Force
    }
    finally {
        # 无论构建成功与否都清理 worktree（产物已复制或失败需重跑）
        $null = Invoke-Native 'git' @('worktree', 'remove', '--force', $worktreeDir) '清理 worktree'
    }
    Complete-Step ('{0} · {1:N0} 字节 · Tests: {2}' -f $assetName, $jarInfo.Length, $(if ($testMatch.Success) { $testMatch.Value } else { 'N/A' }))

    if ($DryRun) {
        $remain = $TotalSteps - $script:StepIndex
        Write-Host ''
        Write-Host ('DryRun 已到达构建完成点，后续 {0} 个远端步骤全部跳过。' -f $remain) -ForegroundColor Yellow
        Write-Host ('产物：{0}' -f $assetPath) -ForegroundColor Yellow
        Write-Host ''
        exit 0
    }

    # ---- 阶段 2：发布确认 -------------------------------------------------

    Step '确认发布'
    Write-Host ''
    Write-Host ('  ' + ('-' * 68)) -ForegroundColor Yellow
    Write-Host ('   版本      {0}' -f $version)
    Write-Host ('   标签      {0}  →  {1}' -f $tag, $targetShort)
    Write-Host ('   提交标题  {0}' -f $targetSubject)
    Write-Host ('   附件      {0}  {1:N0} 字节' -f $assetName, $jarInfo.Length)
    Write-Host ('   GitHub    {0}/{1}' -f $githubSlug.Owner, $githubSlug.Repo)
    Write-Host ('   Gitee     {0}/{1}' -f $giteeSlug.Owner, $giteeSlug.Repo)
    Write-Host ('  ' + ('-' * 68)) -ForegroundColor Yellow
    Write-Host '   继续将推送 main 与标签，并在两个平台创建 Release，不可撤销。' -ForegroundColor Yellow
    Write-Host ''

    $answer = ''
    try { $answer = Read-Host '   输入 y 确认发布，其他任意输入中止' }
    catch {
        $script:LastCommand = ''
        $script:LastOutput  = ''
        $script:LastExit    = -1
        throw '当前会话无法读取交互输入，已中止（请双击 release.bat 运行）'
    }

    if ($answer.Trim().ToLowerInvariant() -ne 'y') {
        Write-Host ''
        Write-Host ' 已取消发布：未推送任何内容，未创建任何 Release。' -ForegroundColor Yellow
        Write-Host (' 本地标签 {0} 保留未动。' -f $tag) -ForegroundColor DarkGray
        Write-Host ''
        exit 2
    }
    Complete-Step '用户已确认'

    # ---- 阶段 3：推送 -----------------------------------------------------

    Step ('推送 {0} 到 GitHub' -f $Branch)
    $null = Invoke-GitGitHub @('push', $GitHubRemote, ('refs/heads/' + $Branch + ':refs/heads/' + $Branch)) '推送 main 到 GitHub'
    Complete-Step ''

    Step ('推送 {0} 到 Gitee' -f $Branch)
    $null = Invoke-Native 'git' @('push', $GiteeRemote, ('refs/heads/' + $Branch + ':refs/heads/' + $Branch)) '推送 main 到 Gitee'
    Complete-Step ''

    Step '推送标签到 GitHub'
    if ($githubTagExists) {
        Skip-Step '远端已存在且指向一致'
    }
    else {
        $null = Invoke-GitGitHub @('push', $GitHubRemote, ('refs/tags/' + $tag)) '推送标签到 GitHub'
        $null = $script:RemoteChanges.Add(('GitHub 远端标签 {0} 已创建' -f $tag))
        $null = $script:RollbackHints.Add(('git -c http.sslVerify=false push {0} :refs/tags/{1}' -f $GitHubRemote, $tag))
        Complete-Step ''
    }

    Step '推送标签到 Gitee'
    if ($giteeTagExists) {
        Skip-Step '远端已存在且指向一致'
    }
    else {
        $null = Invoke-Native 'git' @('push', $GiteeRemote, ('refs/tags/' + $tag)) '推送标签到 Gitee'
        $null = $script:RemoteChanges.Add(('Gitee 远端标签 {0} 已创建' -f $tag))
        $null = $script:RollbackHints.Add(('git push {0} :refs/tags/{1}' -f $GiteeRemote, $tag))
        Complete-Step ''
    }

    # ---- 阶段 4：创建 Release ---------------------------------------------

    Step '创建 GitHub Release（已存在则复用）'
    # 幂等：上次运行可能已创建过 Release（如附件上传失败后重跑）
    $existingBody = $null
    try { $existingBody = Invoke-Http -Url ('https://api.github.com/repos/{0}/{1}/releases/tags/{2}' -f $githubSlug.Owner, $githubSlug.Repo, $tag) -ExtraArgs @('-H', ('Authorization: Bearer ' + $config.githubToken), '-H', 'Accept: application/vnd.github+json') -Label '查询已有 GitHub Release' }
    catch { }
    $createBody = $null
    $createdNow = $false
    if ($existingBody -and ($existingBody | ConvertFrom-Json).id) {
        $createBody = $existingBody
        Complete-Step ('复用已有 id=' + ($createBody | ConvertFrom-Json).id)
    }
    else {
        $payload = [ordered]@{
            tag_name   = $tag
            name       = $tag
            body       = $commitBody
            draft      = $false
            prerelease = $false
        } | ConvertTo-Json -Compress

        $tempJson = [IO.Path]::Combine([IO.Path]::GetTempPath(), ('release-' + $tag + '.json'))
        [IO.File]::WriteAllText($tempJson, $payload, (New-Object Text.UTF8Encoding($false)))

        $createBody = Invoke-Http `
            -Url ('https://api.github.com/repos/{0}/{1}/releases' -f $githubSlug.Owner, $githubSlug.Repo) `
            -ExtraArgs @(
                '-X', 'POST',
                '-H', ('Authorization: Bearer ' + $config.githubToken),
                '-H', 'Accept: application/vnd.github+json',
                '-H', 'X-GitHub-Api-Version: 2022-11-28',
                '-H', 'Content-Type: application/json; charset=utf-8',
                '--data-binary', ('@' + $tempJson)
            ) `
            -Label '创建 GitHub Release'
        Remove-Item -LiteralPath $tempJson -Force -ErrorAction SilentlyContinue
        $createdNow = $true
    }

    $createObj = $createBody | ConvertFrom-Json
    $githubReleaseId = [string]$createObj.id
    Assert-True (-not [string]::IsNullOrWhiteSpace($githubReleaseId)) 'GitHub Release 响应中缺少 id' $createBody
    if ($createdNow) {
        $null = $script:RemoteChanges.Add(('GitHub Release {0} 已创建（id={1}）' -f $tag, $githubReleaseId))
        $null = $script:RollbackHints.Add(('curl.exe --ssl-no-revoke -X DELETE -H "Authorization: Bearer <githubToken>" https://api.github.com/repos/{0}/{1}/releases/{2}' -f $githubSlug.Owner, $githubSlug.Repo, $githubReleaseId))
    }
    Complete-Step ('id=' + $githubReleaseId + $(if (-not $createdNow) { '（复用）' } else { '' }))

    Step ('上传附件 {0}' -f $assetName)
    # 幂等：同名且同大小的附件已存在则跳过
    $existingAsset = $createObj.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
    if ($existingAsset -and [long]$existingAsset.size -eq $jarInfo.Length) {
        Skip-Step ('远端已存在同名附件（{0:N0} 字节）' -f $existingAsset.size)
    }
    else {
        if ($existingAsset) {
            # 同名但大小不一致（可能上次传了一半）：先删旧附件再传
            $null = Invoke-Http -Url ('https://api.github.com/repos/{0}/{1}/releases/assets/{2}' -f $githubSlug.Owner, $githubSlug.Repo, $existingAsset.id) -ExtraArgs @('-X', 'DELETE', '-H', ('Authorization: Bearer ' + $config.githubToken)) -Label '删除旧附件'
        }
        $uploadUrl = 'https://uploads.github.com/repos/{0}/{1}/releases/{2}/assets?name={3}' -f `
            $githubSlug.Owner, $githubSlug.Repo, $githubReleaseId, $assetName
        $uploadBody = Invoke-Http -Url $uploadUrl -MaxTime 1800 -ExtraArgs @(
            '-X', 'POST',
            '-H', ('Authorization: Bearer ' + $config.githubToken),
            '-H', 'Accept: application/vnd.github+json',
            '-H', 'Content-Type: application/java-archive',
            '--data-binary', ('@' + $assetPath)
        ) -Label '上传 GitHub 附件'

        $uploadObj = $uploadBody | ConvertFrom-Json
        $uploadedSize = [long]$uploadObj.size
        $null = $script:RemoteChanges.Add(('GitHub 附件 {0} 已上传（{1:N0} 字节）' -f $assetName, $uploadedSize))
        Complete-Step ('{0:N0} 字节' -f $uploadedSize)
    }

    Step '创建 Gitee Release（已存在则复用）'
    $existingGitee = $null
    try { $existingGitee = Invoke-Http -Url ('https://gitee.com/api/v5/repos/{0}/{1}/releases/tags/{2}' -f $giteeSlug.Owner, $giteeSlug.Repo, $tag) -Label '查询已有 Gitee Release' }
    catch { }
    $giteeBody = $null
    $giteeCreatedNow = $false
    if ($existingGitee -and [string]($existingGitee | ConvertFrom-Json).tag_name -eq $tag) {
        $giteeBody = $existingGitee
        Complete-Step ('复用已有 id=' + ($giteeBody | ConvertFrom-Json).id)
    }
    else {
        # 提交信息可能多行，经临时文件传给 --data-urlencode，避免命令行转义问题
        $tempBody = [IO.Path]::Combine([IO.Path]::GetTempPath(), ('release-' + $tag + '-body.txt'))
        [IO.File]::WriteAllText($tempBody, $commitBody, (New-Object Text.UTF8Encoding($false)))

        $giteeBody = Invoke-Http `
            -Url ('https://gitee.com/api/v5/repos/{0}/{1}/releases' -f $giteeSlug.Owner, $giteeSlug.Repo) `
            -ExtraArgs @(
                '-X', 'POST',
                '--data-urlencode', ('access_token=' + $config.giteeToken),
                '--data-urlencode', ('tag_name=' + $tag),
                '--data-urlencode', ('name=' + $tag),
                '--data-urlencode', ('body@' + $tempBody),
                '--data-urlencode', ('target_commitish=' + $Branch)
            ) `
            -Label '创建 Gitee Release'
        Remove-Item -LiteralPath $tempBody -Force -ErrorAction SilentlyContinue
        $giteeCreatedNow = $true
    }

    $giteeObj = $giteeBody | ConvertFrom-Json
    Assert-True ([string]$giteeObj.tag_name -eq $tag) `
        ('Gitee Release 返回的 tag_name 不是 {0}' -f $tag) $giteeBody
    $giteeReleaseId = [string]$giteeObj.id
    if ($giteeCreatedNow) {
        $null = $script:RemoteChanges.Add(('Gitee Release {0} 已创建（id={1}）' -f $tag, $giteeReleaseId))
        $null = $script:RollbackHints.Add(('curl.exe --ssl-no-revoke -X DELETE "https://gitee.com/api/v5/repos/{0}/{1}/releases/{2}?access_token=<giteeToken>"' -f $giteeSlug.Owner, $giteeSlug.Repo, $giteeReleaseId))
    }
    Complete-Step ('id=' + $giteeReleaseId + $(if (-not $giteeCreatedNow) { '（复用）' } else { '' }))

    Step ('上传 Gitee 附件 {0}' -f $assetName)
    # 幂等：Release 详情里已带同名附件则跳过（Gitee assets 可能不返回，视为需上传）
    $giteeAsset = $null
    if ($giteeObj.assets) {
        # Gitee assets 元素只有 name / browser_download_url，没有 path 等字段，只按 name 判断
        $giteeAsset = $giteeObj.assets | Where-Object { [string]$_.name -eq $assetName } | Select-Object -First 1
    }
    if ($giteeAsset) {
        Skip-Step '远端已存在同名附件'
    }
    else {
        # -F 的文件参数用相对路径（相对仓库根，即当前目录）：避免任何环境下盘符路径被改写
        $assetRel = 'publish/' + $assetName
        Assert-True (Test-Path -LiteralPath (Join-Path (Get-Location).Path $assetRel)) ('附件不存在：{0}' -f $assetRel)
        $giteeUpload = Invoke-Http `
            -Url ('https://gitee.com/api/v5/repos/{0}/{1}/releases/{2}/attach_files' -f $giteeSlug.Owner, $giteeSlug.Repo, $giteeReleaseId) `
            -MaxTime 1800 `
            -ExtraArgs @(
                '-X', 'POST',
                '-F', ('access_token=' + $config.giteeToken),
                '-F', ('file=@' + $assetRel)
            ) `
            -Label '上传 Gitee 附件'
        $null = $script:RemoteChanges.Add(('Gitee 附件 {0} 已上传' -f $assetName))
        Complete-Step $assetName
    }

    # ---- 阶段 5：回查 -----------------------------------------------------

    Step '回查 GitHub Release 与附件大小'
    $checkBody = Invoke-Http `
        -Url ('https://api.github.com/repos/{0}/{1}/releases/tags/{2}' -f $githubSlug.Owner, $githubSlug.Repo, $tag) `
        -ExtraArgs @(
            '-H', ('Authorization: Bearer ' + $config.githubToken),
            '-H', 'Accept: application/vnd.github+json'
        ) `
        -Label '回查 GitHub Release'

    $checkObj = $checkBody | ConvertFrom-Json
    $remoteAsset = $checkObj.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
    Assert-True ($null -ne $remoteAsset) ('GitHub Release 上找不到附件 {0}' -f $assetName) $checkBody
    Assert-True ([long]$remoteAsset.size -eq $jarInfo.Length) `
        ('附件大小不一致：远端 {0} / 本地 {1}' -f $remoteAsset.size, $jarInfo.Length)
    Complete-Step ('附件大小一致（{0:N0} 字节）' -f $remoteAsset.size)

    Step '回查 Gitee Release'
    $giteeCheck = Invoke-Http `
        -Url ('https://gitee.com/api/v5/repos/{0}/{1}/releases/tags/{2}' -f $giteeSlug.Owner, $giteeSlug.Repo, $tag) `
        -Label '回查 Gitee Release'

    $giteeCheckObj = $giteeCheck | ConvertFrom-Json
    Assert-True ([string]$giteeCheckObj.tag_name -eq $tag) 'Gitee Release 回查到的 tag 不匹配' $giteeCheck
    Complete-Step $tag

    Step '输出汇总'
    $githubUrl = 'https://github.com/{0}/{1}/releases/tag/{2}' -f $githubSlug.Owner, $githubSlug.Repo, $tag
    $giteeUrl  = 'https://gitee.com/{0}/{1}/releases/tag/{2}' -f $giteeSlug.Owner, $giteeSlug.Repo, $tag
    Complete-Step ''

    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor Green
    Write-Host (' Release 完成  {0}' -f $tag) -ForegroundColor Green
    Write-Host ('=' * 72) -ForegroundColor Green
    Write-Host ('版本号    {0}' -f $version)
    Write-Host ('标签      {0}  →  {1}' -f $tag, $targetShort)
    Write-Host ('附件      {0}  {1:N0} 字节' -f $assetName, $jarInfo.Length)
    Write-Host ('GitHub    {0}' -f $githubUrl)
    Write-Host ('Gitee     {0}' -f $giteeUrl)
    Write-Host ''
    exit 0
}
catch {
    Exit-Failure -Message $_.Exception.Message -Record $_
}
