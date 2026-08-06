<#
.SYNOPSIS
  Nexcompute 一键构建脚本（Windows PowerShell）

.DESCRIPTION
  - Management（管理端）：本地构建后端 JAR -> 构建前后端 Docker 镜像 -> docker compose 启动全栈 -> 健康检查
  - Agent（受控端）   ：(可选) 重新生成图标 -> 重建 .syso -> CGO 构建 nexcompute-agent.exe

  镜像命名约定：统一推送到私有仓库 10.13.66.18:5002/nexcompute/<service>:<version>。
  构建开始前会询问：镜像版本号、使用哪个 docker-compose（DEV 自带 PG+Redis / PROD 连外部 DB）、受控端版本号、是否隐藏受控端控制台。
  构建完成后会询问是否推送镜像至私有仓库。
  compose 文件经 image: 引用仓库镜像，版本由 VERSION 环境变量控制（默认 latest）。

.EXAMPLE
  .\build.ps1                              # 构建并部署管理端 + 构建受控端（交互询问版本/编排/受控端版本）
  .\build.ps1 -Target Management           # 仅构建并部署管理端
  .\build.ps1 -Target Agent                # 仅构建受控端
  .\build.ps1 -Target Agent -RebuildIcon   # 改了 logo 后，先重新生成图标再构建
  .\build.ps1 -Target Agent -HideConsole   # 构建受控端并隐藏控制台（-H windowsgui，日志写文件）
  .\build.ps1 -Version v1.2.0              # 指定镜像版本号（跳过该步交互询问）
  .\build.ps1 -ComposeFile docker-compose.prod.yml -AgentVersion v0.2.0  # 指定编排文件与受控端版本（跳过交互）
#>
param(
    [ValidateSet('All', 'Management', 'Agent')]
    [string]$Target = 'All',
    [switch]$RebuildIcon,
    [string]$Version,
    [string]$ComposeFile,
    [string]$AgentVersion,
    [switch]$HideConsole
)

$ErrorActionPreference = 'Stop'
$root = if ($PSScriptRoot) { $PSScriptRoot } else { $PWD.Path }
Set-Location $root

# ---------------- 输出辅助 ----------------
function Write-Section($t) { Write-Host "`n========== $t ==========" -ForegroundColor Cyan }
function Write-Ok($t)      { Write-Host "  [OK] $t" -ForegroundColor Green }
function Write-Info($t)    { Write-Host "  .. $t" -ForegroundColor DarkGray }
function Write-Warn($t)    { Write-Host "  [!] $t" -ForegroundColor Yellow }
function Write-Err($t)     { Write-Host "  [X] $t" -ForegroundColor Red }
function Assert-Exit($step) {
    if ($LASTEXITCODE -ne 0) { throw "$step 失败 (exit $LASTEXITCODE)" }
}
function Test-Cmd($n) { [bool](Get-Command $n -ErrorAction SilentlyContinue) }

# ---------------- 管理端：构建 + 部署 ----------------
function Deploy-Management {
    param([string]$Version = 'latest', [string]$ComposeFile = 'docker-compose.yml')

    # 私有镜像仓库（已在 Docker Desktop daemon.json 的 insecure-registries 中配置）
    $Registry = '10.13.66.18:5002'
    Write-Section '管理端：构建并部署'

    # 前置检查
    if (-not (Test-Cmd docker)) { throw '未找到 docker，请先安装 Docker Desktop 并启动' }
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '未找到 docker compose 插件（需 Docker Desktop 或 Compose v2）' }
    if (-not (Test-Path 'management-backend\gradlew.bat')) { throw '未找到 management-backend\gradlew.bat' }

    # 1. 构建后端 JAR（本地构建，避开 Docker 内网络问题）
    Write-Section '[1/5] 构建后端 JAR'
    Push-Location 'management-backend'
    try {
        Write-Info 'gradlew bootJar -x test'
        & .\gradlew.bat bootJar -x test
        Assert-Exit '后端 JAR 构建'

        $jar = Get-ChildItem 'build\libs\*.jar' -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notlike '*-plain.jar' -and $_.Name -notlike '*-sources.jar' } |
               Select-Object -First 1
        if (-not $jar) { throw '未找到构建产物 JAR（build/libs/*.jar）' }
        Copy-Item $jar.FullName 'app.jar' -Force
        Write-Ok "JAR 构建完成：$($jar.Name) -> app.jar ($([math]::Round($jar.Length/1MB,1)) MB)"
    }
    finally { Pop-Location }

    # 镜像引用：私有仓库 nexcompute 命名空间
    $frontendImage = "$Registry/nexcompute/frontend:$Version"
    $backendImage  = "$Registry/nexcompute/backend:$Version"

    # 2. 构建前端 Docker 镜像（多阶段：容器内 npm build -> nginx）
    Write-Section '[2/5] 构建前端 Docker 镜像'
    Write-Info "docker build $frontendImage（容器内执行 npm install + npm run build，首次较慢）"
    & docker build -t $frontendImage -f 'management-frontend/Dockerfile' 'management-frontend/'
    Assert-Exit '前端镜像构建'
    Write-Ok "前端镜像构建完成：$frontendImage"

    # 3. 构建后端 Docker 镜像（用预编译 app.jar，Dockerfile.prebuilt）
    Write-Section '[3/5] 构建后端 Docker 镜像'
    & docker build -t $backendImage --build-arg "MANAGEMENT_VERSION=$Version" -f 'management-backend/Dockerfile.prebuilt' 'management-backend/'
    Assert-Exit '后端镜像构建'
    Write-Ok "后端镜像构建完成：$backendImage"

    # 同步打 :latest 标签，便于未指定 VERSION 时 docker compose 回退使用
    if ($Version -ne 'latest') {
        & docker tag $frontendImage "$Registry/nexcompute/frontend:latest"
        & docker tag $backendImage  "$Registry/nexcompute/backend:latest"
    }

    # 4. 询问是否同步（推送）到私有仓库
    Write-Section '[4/5] 同步镜像到私有仓库'
    Write-Host "  目标仓库：$Registry" -ForegroundColor DarkGray
    $sync = Read-Host "是否推送镜像到 $Registry/nexcompute ？(y/N)"
    if ($sync -match '^[Yy]') {
        $pushTargets = @($frontendImage, $backendImage)
        if ($Version -ne 'latest') {
            $pushTargets += @(
                "$Registry/nexcompute/frontend:latest",
                "$Registry/nexcompute/backend:latest"
            )
        }
        foreach ($img in $pushTargets) {
            # containerd image store 对 insecure registry 的 HTTPS->HTTP 回退偶发失败（报 EOF），
            # 重试即可成功，故对每个镜像最多重试 10 次。
            $pushed = $false
            for ($attempt = 1; $attempt -le 10; $attempt++) {
                Write-Info "docker push $img（第 $attempt/10 次）"
                & docker push $img
                if ($LASTEXITCODE -eq 0) { $pushed = $true; break }
                Write-Warn "推送失败（exit $LASTEXITCODE），2s 后重试（第 $attempt/10 次）"
                Start-Sleep -Seconds 3
            }
            if (-not $pushed) { throw "推送 $img 连续 10 次失败（最后 exit $LASTEXITCODE）" }
            Write-Ok "已推送：$img"
        }
    } else {
        Write-Info '已跳过推送（本地镜像仍可直接用于 docker compose）'
    }

    # 5. 启动全栈
    Write-Section '[5/5] 启动 Docker Compose'
    if (-not (Test-Path $ComposeFile)) {
        throw "未找到 $ComposeFile。docker-compose.prod.yml 为真实文件已 gitignore；若不存在请先 cp docker-compose.prod.example.yml docker-compose.prod.yml 并填值。"
    }
    Write-Info "使用编排文件：$ComposeFile"
    $env:VERSION = $Version
    & docker compose -f $ComposeFile up -d
    Assert-Exit 'docker compose up'
    Write-Ok '容器已启动，等待后端就绪...'

    # 健康检查：dev 后端直接暴露 8080；prod 后端未对外暴露，经前端 nginx(:80) 反代 /api
    $pingUrl = if ($ComposeFile -eq 'docker-compose.prod.yml') { 'http://localhost/api/ping' } else { 'http://localhost:8080/api/ping' }
    $ready = $false
    Start-Sleep -Seconds 8
    for ($i = 1; $i -le 24; $i++) {
        try {
            $resp = Invoke-WebRequest -Uri $pingUrl -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Write-Info "等待后端就绪... ($i/24)"
        Start-Sleep -Seconds 5
    }

    Write-Section '管理端部署完成'
    if ($ready) { Write-Ok "后端就绪：$pingUrl" }
    else { Write-Warn "后端未在超时内就绪，可能仍在启动（Spring Boot 首次启动较慢），稍后重试 $pingUrl" }
    Write-Host ''
    & docker compose -f $ComposeFile ps
    Write-Host ''
    if ($ComposeFile -eq 'docker-compose.prod.yml') {
        Write-Host '  前端      : http://localhost' -ForegroundColor White
        Write-Host '  后端 API  : http://localhost/api（nginx 反代）/ http://localhost:8080/api（直连）' -ForegroundColor White
        Write-Host '  受控端    : ServerURL = http://<服务器IP>:8080（受控端连此地址）' -ForegroundColor White
    } else {
        Write-Host '  前端      : http://localhost' -ForegroundColor White
        Write-Host '  后端 API  : http://localhost:8080/api' -ForegroundColor White
        Write-Host '  受控端    : ServerURL = http://<本机IP>:8080（受控端连此地址）' -ForegroundColor White
    }
    Write-Host '  管理员    : admin / admin123' -ForegroundColor White
}

# ---------------- 受控端版本号解析 ----------------
# 优先 git tag（HEAD 恰指向 tag），无 tag 用 Makefile VERSION，再回退 0.1.0
function Resolve-AgentVersion {
    $v = $null; $source = '回退'
    try {
        $tag = git describe --tags --exact-match 2>$null
        if ($LASTEXITCODE -eq 0 -and $tag) { $v = $tag.Trim(); $source = 'git tag' }
    } catch { }
    if (-not $v) {
        $mk = Join-Path $root 'controlled-agent\Makefile'
        if (Test-Path $mk) {
            $verLine = Get-Content $mk -ErrorAction SilentlyContinue |
                Where-Object { $_ -match '^\s*VERSION\s*:=\s*(.+)$' } |
                Select-Object -First 1
            # 在本作用域重新 -match 以正确填充 $Matches（Where-Object 子作用域内的 match 不外泄）
            if ($verLine -and $verLine -match '^\s*VERSION\s*:=\s*(.+)$') {
                $v = $Matches[1].Trim(); $source = 'Makefile'
            }
        }
    }
    if (-not $v) { $v = '0.1.0' }
    return [pscustomobject]@{ Version = $v; Source = $source }
}

# ---------------- 受控端：构建 ----------------
function Build-Agent {
    param([string]$AgentVersion, [switch]$HideConsole)
    Write-Section '受控端：构建 nexcompute-agent.exe'
    if (-not (Test-Cmd go)) { throw '未找到 go，请安装 Go 1.23+ 并加入 PATH' }

    # CGO 需要 C 编译器（gcc）。若当前 PATH 找不到 gcc，尝试常见 mingw 路径自动补 PATH。
    if (-not (Test-Cmd gcc)) {
        $candidates = @(
            'C:\Program Files\mingw-w64\mingw64\bin',
            'C:\mingw64\bin',
            'C:\msys64\mingw64\bin',
            'C:\TDM-GCC-64\bin'
        )
        # WinGet 安装的 MinGW（WinLibs / mingw-w64 等包名不一，按 gcc.exe 定位）
        $wingetRoot = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages"
        if (Test-Path $wingetRoot) {
            Get-ChildItem -Path $wingetRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $gcc = Get-ChildItem -Path $_.FullName -Recurse -Filter 'gcc.exe' -ErrorAction SilentlyContinue |
                    Select-Object -First 1
                if ($gcc) { $candidates = @($gcc.DirectoryName) + $candidates }
            }
        }
        $found = $false
        foreach ($p in $candidates) {
            if ($p -and (Test-Path (Join-Path $p 'gcc.exe'))) {
                $env:PATH = "$p;$env:PATH"
                Write-Ok "已自动加入 PATH：$p（gcc）"
                $found = $true
                break
            }
        }
        if (-not $found) {
            throw '未找到 gcc。Fyne 依赖 CGO，需安装 MinGW-w64（如 winget install BrechtSanders.WinLibs.POSIX.UCRT）并将其 bin 加入 PATH'
        }
    }

    # 停止本机正在运行的受控端进程（否则 exe 被占用，go build 覆盖会失败）
    Write-Section '停止本机受控端进程'
    $procs = Get-Process -Name 'nexcompute-agent' -ErrorAction SilentlyContinue
    if ($procs) {
        Write-Info "发现 $($procs.Count) 个运行中的 nexcompute-agent.exe，正在停止..."
        $procs | Stop-Process -Force -ErrorAction SilentlyContinue
        # 轮询确认进程已退出（最多约 3 秒）
        for ($i = 1; $i -le 10; $i++) {
            Start-Sleep -Milliseconds 300
            if (-not (Get-Process -Name 'nexcompute-agent' -ErrorAction SilentlyContinue)) { break }
        }
        if (Get-Process -Name 'nexcompute-agent' -ErrorAction SilentlyContinue) {
            Write-Warn '部分进程未能停止，构建可能因 exe 被占用而失败'
        } else {
            Write-Ok '已停止本机受控端进程'
        }
    } else {
        Write-Info '未发现运行中的受控端进程'
    }

    Push-Location 'controlled-agent'
    try {
        # (可选) 重新生成图标 + 重建 .syso
        if ($RebuildIcon) {
            Write-Section '重新生成图标（go run ./tools/genicon）'
            & go run './tools/genicon'
            Assert-Exit '图标生成（genicon）'
            Write-Ok 'icon.png / icon.ico 已生成'

            if (Test-Cmd windres) {
                Write-Info 'windres 重建 nexcompute-agent.syso（图标 + manifest）'
                Push-Location 'cmd/nexcompute-agent'
                try {
                    & windres -O coff -i nexcompute-agent.rc -o nexcompute-agent.syso
                    Assert-Exit 'windres 重建 .syso'
                }
                finally { Pop-Location }
                Write-Ok '.syso 已重建（图标 + requireAdministrator manifest）'
            } else {
                Write-Warn '未找到 windres（MinGW），跳过 .syso 重建，使用已提交的 .syso'
            }
        }

        # CGO 构建（Fyne 依赖）
        Write-Section 'CGO 构建 nexcompute-agent.exe'
        $env:CGO_ENABLED = '1'

        # D1：经 ldflags 注入版本号至 internal/version.Version（版本号由主流程询问 / -AgentVersion 传入）
        $ldflags = "-X github.com/nexcompute/controlled-agent/internal/version.Version=$AgentVersion"
        # 隐藏控制台：-H windowsgui 切 Windows GUI 子系统，运行时不弹黑窗（日志仍写文件，致命错仍弹对话框）
        if ($HideConsole) {
            $ldflags = "-H windowsgui $ldflags"
            Write-Info "隐藏控制台（-H windowsgui）"
        }
        Write-Info "agentVersion=$AgentVersion"
        Write-Info "CGO_ENABLED=1 go build -ldflags `"$ldflags`" ./cmd/nexcompute-agent"
        & go build -ldflags $ldflags -o 'nexcompute-agent.exe' './cmd/nexcompute-agent'
        Assert-Exit '受控端构建'

        $exe = Get-Item 'nexcompute-agent.exe'
        Write-Section '受控端构建完成'
        Write-Ok "产物：controlled-agent\nexcompute-agent.exe ($([math]::Round($exe.Length/1MB,1)) MB)"
        Write-Info '图标已嵌入：exe 资源管理器/任务栏图标 + Fyne 窗口图标 + 系统托盘图标'
        Write-Info '部署：将 nexcompute-agent.exe 拷贝到目标 Windows+GPU 主机并以管理员身份运行'
    }
    finally {
        Pop-Location
        Remove-Item Env:CGO_ENABLED -ErrorAction SilentlyContinue
    }
}

# ---------------- 主流程 ----------------
try {
    Write-Host 'Nexcompute 一键构建（PowerShell）' -ForegroundColor Cyan
    Write-Host "目标：$Target" -ForegroundColor DarkGray
    if ($RebuildIcon) { Write-Host '选项：-RebuildIcon（重新生成受控端图标）' -ForegroundColor DarkGray }

    # 管理端涉及 Docker 镜像，构建开始前确认版本号 + docker-compose 编排文件
    if ($Target -in 'All','Management') {
        if (-not $Version) {
            $Version = Read-Host '请输入本次构建版本号（如 v1.0.0；直接回车=latest）'
            if (-not $Version) { $Version = 'latest' }
        }
        Write-Host "版本：$Version" -ForegroundColor DarkGray

        if (-not $ComposeFile) {
            Write-Host '可用 docker-compose：'
            Write-Host '  1) docker-compose.yml      （DEV：自带 PG+Redis，本地开发/演示）'
            Write-Host '  2) docker-compose.prod.yml （PROD：连外部 PG/Redis）'
            $c = Read-Host '使用哪个 docker-compose？(1/2，默认 1)'
            if ($c -eq '2') { $ComposeFile = 'docker-compose.prod.yml' } else { $ComposeFile = 'docker-compose.yml' }
        }
        Write-Host "编排文件：$ComposeFile" -ForegroundColor DarkGray
    }

    # 受控端版本号（默认 git tag / Makefile 检测值，回车确认或输入新值）+ 是否隐藏控制台
    if ($Target -in 'All','Agent') {
        if (-not $AgentVersion) {
            $detected = Resolve-AgentVersion
            $av = Read-Host "请输入受控端版本号（直接回车=$($detected.Version) [$($detected.Source)]）"
            if ($av) { $AgentVersion = $av } else { $AgentVersion = $detected.Version }
        }
        Write-Host "受控端版本：$AgentVersion" -ForegroundColor DarkGray

        # 控制台是否隐藏（-H windowsgui；日志仍写文件，不影响排障）
        $hideConsole = [bool]$HideConsole
        if (-not $PSBoundParameters.ContainsKey('HideConsole')) {
            $h = Read-Host '是否隐藏受控端控制台（运行时不显示黑窗口；日志仍写文件）? (y/N)'
            if ($h -match '^[Yy]') { $hideConsole = $true }
        }
        Write-Host "隐藏控制台：$(if ($hideConsole) { '是' } else { '否' })" -ForegroundColor DarkGray
    }

    switch ($Target) {
        'Management' { Deploy-Management -Version $Version -ComposeFile $ComposeFile }
        'Agent'     { Build-Agent -AgentVersion $AgentVersion -HideConsole:$hideConsole }
        'All'       { Deploy-Management -Version $Version -ComposeFile $ComposeFile; Write-Host ''; Build-Agent -AgentVersion $AgentVersion -HideConsole:$hideConsole }
    }

    Write-Host ''
    Write-Host '全部完成。' -ForegroundColor Green
    exit 0
}
catch {
    Write-Err $_.Exception.Message
    Write-Host ''
    exit 1
}
