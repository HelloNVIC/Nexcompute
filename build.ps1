<#
.SYNOPSIS
  Nexcompute 一键构建脚本（Windows PowerShell）

.DESCRIPTION
  - Management（管理端）：本地构建后端 JAR -> 构建前后端 Docker 镜像 -> docker compose 启动全栈 -> 健康检查
  - Agent（受控端）   ：(可选) 重新生成图标 -> 重建 .syso -> CGO 构建 nexcompute-agent.exe

  镜像命名约定：统一推送到私有仓库 10.13.66.18:5002/nexcompute/<service>:<version>。
  构建开始前会询问版本号；构建完成后会询问是否推送至私有仓库。
  docker-compose.yml 通过 image: 引用仓库镜像，版本由 VERSION 环境变量控制（默认 latest）。

.EXAMPLE
  .\build.ps1                              # 构建并部署管理端 + 构建受控端
  .\build.ps1 -Target Management           # 仅构建并部署管理端
  .\build.ps1 -Target Agent                # 仅构建受控端
  .\build.ps1 -Target Agent -RebuildIcon   # 改了 logo 后，先重新生成图标再构建
  .\build.ps1 -Version v1.2.0              # 指定镜像版本号（跳过交互询问）
#>
param(
    [ValidateSet('All', 'Management', 'Agent')]
    [string]$Target = 'All',
    [switch]$RebuildIcon,
    [string]$Version
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
    param([string]$Version = 'latest')

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
    & docker build -t $backendImage -f 'management-backend/Dockerfile.prebuilt' 'management-backend/'
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
    $env:VERSION = $Version
    & docker compose up -d
    Assert-Exit 'docker compose up'
    Write-Ok '容器已启动，等待后端就绪...'

    # 健康检查：GET http://localhost:8080/api/ping
    $pingUrl = 'http://localhost:8080/api/ping'
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
    & docker compose ps
    Write-Host ''
    Write-Host '  前端      : http://localhost' -ForegroundColor White
    Write-Host '  后端 API  : http://localhost:8080/api' -ForegroundColor White
    Write-Host '  管理员    : admin / admin123' -ForegroundColor White
}

# ---------------- 受控端：构建 ----------------
function Build-Agent {
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

        # D1：经 ldflags 注入版本号至 internal/version.Version
        # 优先 git tag（HEAD 恰指向 tag），无 tag 用 Makefile VERSION，再回退 0.1.0
        $agentVersion = $null
        try {
            $tag = git describe --tags --exact-match 2>$null
            if ($LASTEXITCODE -eq 0 -and $tag) { $agentVersion = $tag.Trim() }
        } catch { }
        if (-not $agentVersion) {
            $verLine = Get-Content 'Makefile' -ErrorAction SilentlyContinue |
                Where-Object { $_ -match '^\s*VERSION\s*:=\s*(.+)$' } |
                Select-Object -First 1
            if ($verLine -and $Matches[1]) { $agentVersion = $Matches[1].Trim() }
        }
        if (-not $agentVersion) { $agentVersion = '0.1.0' }
        $ldflags = "-X github.com/nexcompute/controlled-agent/internal/version.Version=$agentVersion"
        Write-Info "agentVersion=$agentVersion（来源：$(if ($tag) { 'git tag' } else { 'Makefile' })）"
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

    # 管理端涉及 Docker 镜像，构建开始前确认版本号
    if ($Target -in 'All','Management') {
        if (-not $Version) {
            $Version = Read-Host '请输入本次构建版本号（如 v1.0.0；直接回车=latest）'
            if (-not $Version) { $Version = 'latest' }
        }
        Write-Host "版本：$Version" -ForegroundColor DarkGray
    }

    switch ($Target) {
        'Management' { Deploy-Management -Version $Version }
        'Agent'     { Build-Agent }
        'All'       { Deploy-Management -Version $Version; Write-Host ''; Build-Agent }
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
