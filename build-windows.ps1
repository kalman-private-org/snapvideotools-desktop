# ============================================================
# SnapVideoTools Desktop - Windows PowerShell 打包脚本
# 生成 .exe 安装程序和便携版
# ============================================================

param(
    [switch]$Help,
    [switch]$Clean,
    [switch]$BuildOnly,
    [switch]$SkipBuild,
    [switch]$Msi,
    [switch]$Portable,
    [string]$Version = ""
)

# 配置变量
$AppName = "SnapVideoTools"
$AppVersion = $Version
$MainClass = "com.kalman03.svt.desktop.SnapVideoApplication"
$JavaVersion = 21
$Vendor = "Kalman03"
$Description = "Video Downloader Desktop Client"
$Copyright = "Copyright (c) 2024 $Vendor"

# 目录配置
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TargetDir = Join-Path $ProjectDir "target"
$PackageDir = Join-Path $TargetDir "package"
$LibsDir = Join-Path $PackageDir "libs"
$InstallerDir = Join-Path $TargetDir "installer"
$FFmpegResourcesDir = Join-Path $ProjectDir "src\main\resources\ffmpeg"

# FFmpeg 下载配置
$FFmpegDownloadUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"

# 颜色输出函数
function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] " -ForegroundColor Green -NoNewline
    Write-Host $Message
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] " -ForegroundColor Yellow -NoNewline
    Write-Host $Message
}

function Write-Err {
    param([string]$Message)
    Write-Host "[ERROR] " -ForegroundColor Red -NoNewline
    Write-Host $Message
}

# 下载并准备 FFmpeg
function Initialize-FFmpeg {
    Write-Info "准备 FFmpeg..."

    $ffmpegDir = Join-Path $FFmpegResourcesDir "win"
    $ffmpegPath = Join-Path $ffmpegDir "ffmpeg.exe"
    $ffprobePath = Join-Path $ffmpegDir "ffprobe.exe"

    # 检查是否已存在
    if ((Test-Path $ffmpegPath) -and (Test-Path $ffprobePath)) {
        Write-Info "FFmpeg 已存在于 $ffmpegDir"
        return $true
    }

    # 创建目录
    New-Item -ItemType Directory -Path $ffmpegDir -Force | Out-Null

    Write-Info "下载 FFmpeg for Windows..."
    Write-Warn "如果自动下载失败，请手动下载 FFmpeg："
    Write-Warn "  1. 访问 https://www.gyan.dev/ffmpeg/builds/"
    Write-Warn "  2. 下载 ffmpeg-release-essentials.zip"
    Write-Warn "  3. 解压并将 ffmpeg.exe 和 ffprobe.exe 放到 $ffmpegDir\"

    try {
        $tempDir = Join-Path $env:TEMP "ffmpeg_download"
        $zipPath = Join-Path $tempDir "ffmpeg.zip"

        # 创建临时目录
        New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

        # 下载 FFmpeg
        Write-Info "正在下载 FFmpeg（文件较大，请耐心等待）..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $FFmpegDownloadUrl -OutFile $zipPath -UseBasicParsing

        # 解压
        Write-Info "解压 FFmpeg..."
        Expand-Archive -Path $zipPath -DestinationPath $tempDir -Force

        # 查找 ffmpeg.exe 和 ffprobe.exe
        $ffmpegExe = Get-ChildItem -Path $tempDir -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
        $ffprobeExe = Get-ChildItem -Path $tempDir -Recurse -Filter "ffprobe.exe" | Select-Object -First 1

        if ($ffmpegExe -and $ffprobeExe) {
            Copy-Item -Path $ffmpegExe.FullName -Destination $ffmpegPath -Force
            Copy-Item -Path $ffprobeExe.FullName -Destination $ffprobePath -Force
            Write-Info "FFmpeg 下载成功"

            # 验证
            $version = & $ffmpegPath -version 2>&1 | Select-Object -First 1
            Write-Info "FFmpeg 版本: $version"
        } else {
            Write-Warn "未能在下载的文件中找到 ffmpeg.exe"
            return $false
        }

        # 清理临时文件
        Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue

        return $true
    }
    catch {
        Write-Warn "FFmpeg 下载失败: $_"
        Write-Warn "请手动下载并放置到 $ffmpegDir\"
        Write-Warn "应用仍可构建，但运行时需要系统安装 FFmpeg"
        return $false
    }
}

# 显示帮助
function Show-Help {
    Write-Host ""
    Write-Host "SnapVideoTools Desktop - Windows PowerShell 打包脚本"
    Write-Host ""
    Write-Host "用法: .\build-windows.ps1 [选项]"
    Write-Host ""
    Write-Host "选项:"
    Write-Host "  -Help         显示帮助信息"
    Write-Host "  -Clean        仅清理构建文件"
    Write-Host "  -BuildOnly    仅构建，不打包"
    Write-Host "  -SkipBuild    跳过 Maven 构建（使用已有的文件）"
    Write-Host "  -Msi          同时创建 MSI 安装包"
    Write-Host "  -Portable     创建便携版（无需安装）"
    Write-Host "  -Version      指定版本号（默认: 1.0.0）"
    Write-Host ""
    Write-Host "示例:"
    Write-Host "  .\build-windows.ps1                    # 完整构建并创建 EXE"
    Write-Host "  .\build-windows.ps1 -Msi               # 同时创建 EXE 和 MSI"
    Write-Host "  .\build-windows.ps1 -Portable          # 创建便携版"
    Write-Host "  .\build-windows.ps1 -Version 2.0.0     # 指定版本号"
    Write-Host ""
    Write-Host "注意:"
    Write-Host "  - 需要安装 JDK $JavaVersion 或更高版本"
    Write-Host "  - 创建 EXE/MSI 需要安装 WiX Toolset 3.0+"
    Write-Host ""
}

# 检查 Java 版本
function Test-Java {
    Write-Info "检查 Java 环境..."

    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-Err "未找到 Java，请安装 JDK $JavaVersion"
        return $false
    }

    $javaVersionOutput = & java -version 2>&1 | Select-Object -First 1
    if ($javaVersionOutput -match '"(\d+)') {
        $majorVersion = [int]$Matches[1]
        if ($majorVersion -lt $JavaVersion) {
            Write-Err "Java 版本过低，需要 JDK $JavaVersion+，当前版本: $majorVersion"
            return $false
        }
    }

    Write-Info "Java 版本检查通过: $javaVersionOutput"
    return $true
}

# 检查 jpackage
function Test-JPackage {
    Write-Info "检查 jpackage 工具..."

    $jpackageCmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if (-not $jpackageCmd) {
        Write-Err "未找到 jpackage，请确保安装了完整的 JDK $JavaVersion"
        return $false
    }

    Write-Info "jpackage 可用"
    return $true
}

# 校验 jpackage 接受的发布版本，避免不同平台产生不一致的文件名。
function Test-Version {
    if ($AppVersion -notmatch '^\d+\.\d+\.\d+$') {
        Write-Err "版本号必须符合 X.Y.Z，例如 1.2.3；当前值: $AppVersion"
        return $false
    }
    return $true
}

# 清理构建文件
function Clear-Build {
    Write-Info "清理旧的构建文件..."

    if (Test-Path $PackageDir) {
        Remove-Item -Path $PackageDir -Recurse -Force
    }
    if (Test-Path $InstallerDir) {
        Remove-Item -Path $InstallerDir -Recurse -Force
    }

    Write-Info "清理完成"
}

# Maven 构建
function Invoke-Build {
    Write-Info "使用 Maven 构建项目..."

    Set-Location $ProjectDir

    $mvnCmd = if (Test-Path (Join-Path $ProjectDir "mvnw.cmd")) {
        ".\mvnw.cmd"
    } else {
        "mvn"
    }

    # 使用 jar 打包（不是 Spring Boot 的 fat jar）
    & $mvnCmd clean compile jar:jar "-Drevision=$AppVersion" -DskipTests -Pwindows

    if ($LASTEXITCODE -ne 0) {
        Write-Err "Maven 构建失败"
        return $false
    }

    Write-Info "Maven 构建完成"
    return $true
}

# 准备依赖
function Initialize-Dependencies {
    Write-Info "准备依赖文件..."

    # 确保目录存在
    New-Item -ItemType Directory -Path $PackageDir -Force | Out-Null
    New-Item -ItemType Directory -Path $LibsDir -Force | Out-Null
    New-Item -ItemType Directory -Path $InstallerDir -Force | Out-Null

    Set-Location $ProjectDir

    # target/classes 已包含 Maven 过滤后的完整资源，禁止再用源码资源覆盖版本号。
    $classesDir = Join-Path $TargetDir "classes"
    if (Test-Path $classesDir) {
        Write-Info "创建应用 JAR..."
        Set-Location $classesDir
        & jar cf "$LibsDir\app.jar" .
        Set-Location $ProjectDir
    }

    # 使用 Maven 复制依赖
    Write-Info "复制依赖库..."
    $mvnCmd = if (Test-Path (Join-Path $ProjectDir "mvnw.cmd")) {
        ".\mvnw.cmd"
    } else {
        "mvn"
    }

    & $mvnCmd dependency:copy-dependencies "-Drevision=$AppVersion" "-DoutputDirectory=$LibsDir" -DincludeScope=runtime -Pwindows

    if ($LASTEXITCODE -ne 0) {
        Write-Err "复制依赖失败"
        return $false
    }

    Write-Info "依赖文件准备完成"
    return $true
}

# 验证打包结果
function Test-Package {
    Write-Info "验证打包结果..."

    $appJar = Join-Path $LibsDir "app.jar"
    $jarContent = & jar tf $appJar 2>&1
    if ($jarContent -match "SnapVideoApplication.class") {
        Write-Info "主类验证通过"
        return $true
    } else {
        Write-Err "主类未找到，请检查构建过程"
        return $false
    }
}

# 创建启动脚本
function New-LauncherScript {
    Write-Info "创建启动脚本..."

    $scriptContent = @"
@echo off
set SCRIPT_DIR=%~dp0
java -cp "%SCRIPT_DIR%libs\*" ^
    --add-opens=java.base/java.lang=ALL-UNNAMED ^
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^
    --add-opens=java.base/java.io=ALL-UNNAMED ^
    $MainClass %*
"@

    $scriptPath = Join-Path $PackageDir "run.bat"
    Set-Content -Path $scriptPath -Value $scriptContent -Encoding UTF8
}

# 创建安装包
function New-Package {
    param(
        [string]$Type = "exe"
    )

    Write-Info "使用 jpackage 创建 $Type 安装包..."

    # 检查图标
    $iconPath = Join-Path $ProjectDir "src\main\resources\icon.ico"
    $iconOption = @()
    if (Test-Path $iconPath) {
        $iconOption = @("--icon", $iconPath)
        Write-Info "使用自定义图标"
    } else {
        Write-Warn "未找到图标文件 icon.ico，将使用默认图标"
    }

    # jpackage 参数
    $jpackageArgs = @(
        "--type", $Type
        "--name", $AppName
        "--app-version", $AppVersion
        "--vendor", $Vendor
        "--description", $Description
        "--copyright", $Copyright
        "--input", $LibsDir
        "--main-jar", "app.jar"
        "--main-class", $MainClass
        "--dest", $InstallerDir
        "--java-options", "-Xmx512m"
        "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.io=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.util=ALL-UNNAMED"
    )

    # Windows 特定选项
    if ($Type -eq "exe" -or $Type -eq "msi") {
        $jpackageArgs += @(
            "--win-dir-chooser"
            "--win-menu"
            "--win-menu-group", $AppName
            "--win-shortcut"
        )
        if ($Type -eq "exe") {
            $jpackageArgs += "--win-shortcut-prompt"
        }
    }

    # 添加图标
    $jpackageArgs += $iconOption

    # 执行 jpackage
    & jpackage @jpackageArgs

    if ($LASTEXITCODE -ne 0) {
        Write-Err "创建 $Type 安装包失败"
        return $false
    }

    $packageFile = Get-ChildItem -Path $InstallerDir -Filter "*.$Type" -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $packageFile) {
        Write-Err "jpackage 未生成 $Type 安装包"
        return $false
    }

    Write-Info "$Type 安装包创建完成"
    return $true
}

# 创建便携版
function New-PortablePackage {
    Write-Info "创建便携版..."

    $jpackageArgs = @(
        "--type", "app-image"
        "--name", $AppName
        "--app-version", $AppVersion
        "--vendor", $Vendor
        "--description", $Description
        "--copyright", $Copyright
        "--input", $LibsDir
        "--main-jar", "app.jar"
        "--main-class", $MainClass
        "--dest", $InstallerDir
        "--java-options", "-Xmx512m"
        "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.io=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
        "--java-options", "--add-opens=java.base/java.util=ALL-UNNAMED"
    )

    # 检查图标
    $iconPath = Join-Path $ProjectDir "src\main\resources\icon.ico"
    if (Test-Path $iconPath) {
        $jpackageArgs += @("--icon", $iconPath)
    }

    & jpackage @jpackageArgs

    if ($LASTEXITCODE -ne 0) {
        Write-Err "创建便携版失败"
        return $false
    }

    # 压缩为 ZIP
    $portableDir = Join-Path $InstallerDir $AppName
    $zipPath = Join-Path $InstallerDir "$AppName-$AppVersion-portable.zip"

    if (Test-Path $portableDir) {
        Write-Info "压缩便携版为 ZIP..."
        Compress-Archive -Path $portableDir -DestinationPath $zipPath -Force
        Write-Info "便携版 ZIP 创建完成: $zipPath"
    }

    return $true
}

# 显示结果
function Show-Result {
    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Info "打包完成！"
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "输出目录: $InstallerDir"
    Write-Host ""
    Get-ChildItem -Path $InstallerDir | Format-Table Name, Length, LastWriteTime
    Write-Host ""
    Write-Info "您可以将安装程序分发给用户"
}

# 主流程
function Main {
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host "  SnapVideoTools Desktop - Windows PowerShell 打包脚本"
    Write-Host "============================================================" -ForegroundColor Cyan
    Write-Host ""

    if ($Help) {
        Show-Help
        return
    }

    if ($Clean) {
        Clear-Build
        return
    }

    if ([string]::IsNullOrWhiteSpace($script:AppVersion)) {
        $mvnCmd = if (Test-Path (Join-Path $ProjectDir "mvnw.cmd")) { ".\mvnw.cmd" } else { "mvn" }
        $script:AppVersion = (& $mvnCmd help:evaluate -Dexpression=revision -q -DforceStdout).Trim()
    }

    if (-not (Test-Version)) {
        exit 1
    }

    if (-not (Test-Java)) {
        exit 1
    }

    if (-not (Test-JPackage)) {
        exit 1
    }

    Clear-Build

    # 准备 FFmpeg（下载或检查）
    Initialize-FFmpeg | Out-Null

    if ($BuildOnly) {
        if (Invoke-Build) {
            if (Initialize-Dependencies) {
                if (Test-Package) {
                    New-LauncherScript
                    Write-Info "构建完成，可以使用 $PackageDir\run.bat 测试运行"
                }
            }
        }
        return
    }

    if (-not $SkipBuild) {
        if (-not (Invoke-Build)) {
            exit 1
        }
    }

    if (-not (Initialize-Dependencies)) {
        exit 1
    }

    if (-not (Test-Package)) {
        exit 1
    }

    New-LauncherScript

    if ($Portable) {
        if (-not (New-PortablePackage)) {
            exit 1
        }
    } else {
        # 创建 EXE
        if (-not (New-Package -Type "exe")) {
            exit 1
        }

        # 如果指定了 MSI，也创建 MSI
        if ($Msi) {
            New-Package -Type "msi" | Out-Null
        }
    }

    Show-Result
}

# 运行主流程
Main
