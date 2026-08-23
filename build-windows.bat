@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM SnapVideoTools Desktop - Windows 打包脚本
REM 生成 .exe 安装程序
REM ============================================================

REM 配置变量
set APP_NAME=SnapVideoTools
set APP_VERSION=
set MAIN_CLASS=com.kalman03.svt.desktop.SnapVideoApplication
set JAVA_VERSION=21
set VENDOR=Kalman03
set DESCRIPTION=Video Downloader Desktop Client
set COPYRIGHT=Copyright (c) 2024 %VENDOR%

REM 目录配置
set PROJECT_DIR=%~dp0
set PROJECT_DIR=%PROJECT_DIR:~0,-1%
set TARGET_DIR=%PROJECT_DIR%\target
set PACKAGE_DIR=%TARGET_DIR%\package
set LIBS_DIR=%PACKAGE_DIR%\libs
set INSTALLER_DIR=%TARGET_DIR%\installer

REM 颜色不支持，使用文本标记
set INFO=[INFO]
set WARN=[WARN]
set ERROR=[ERROR]

echo.
echo ============================================================
echo   SnapVideoTools Desktop - Windows 打包脚本
echo ============================================================
echo.

REM 解析命令行参数
if "%1"=="--help" goto :show_help
if "%1"=="-h" goto :show_help
if "%1"=="--clean" goto :clean_only
if "%1"=="--build-only" goto :build_only

set SKIP_BUILD=false
set BUILD_MSI=false

:parse_args
if "%1"=="" goto :main
if "%1"=="--version" (
    if "%2"=="" (
        echo %ERROR% --version 需要提供 X.Y.Z 格式的版本号
        exit /b 1
    )
    set APP_VERSION=%2
    shift
    shift
    goto :parse_args
)
if "%1"=="--skip-build" set SKIP_BUILD=true
if "%1"=="--msi" set BUILD_MSI=true
shift
goto :parse_args

:main
REM 主流程
if "%APP_VERSION%"=="" (
    if exist "%PROJECT_DIR%\mvnw.cmd" (
        for /f "usebackq delims=" %%V in (`"%PROJECT_DIR%\mvnw.cmd" help:evaluate -Dexpression=revision -q -DforceStdout`) do set APP_VERSION=%%V
    ) else (
        for /f "delims=" %%V in ('mvn help:evaluate -Dexpression=revision -q -DforceStdout') do set APP_VERSION=%%V
    )
)
call :check_java
if errorlevel 1 goto :error_exit

call :check_jpackage
if errorlevel 1 goto :error_exit

call :clean
if errorlevel 1 goto :error_exit

if "%SKIP_BUILD%"=="false" (
    call :build
    if errorlevel 1 goto :error_exit
)

call :prepare_dependencies
if errorlevel 1 goto :error_exit

call :verify_package
if errorlevel 1 goto :error_exit

call :create_launcher_script

call :create_package exe
if errorlevel 1 goto :error_exit

if "%BUILD_MSI%"=="true" (
    call :create_package msi
)

call :show_result
goto :eof

REM ============================================================
REM 函数定义
REM ============================================================

:check_java
echo %INFO% 检查 Java 环境...
where java >nul 2>&1
if errorlevel 1 (
    echo %ERROR% 未找到 Java，请安装 JDK %JAVA_VERSION%
    exit /b 1
)

for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VER=%%i
)
set JAVA_VER=%JAVA_VER:"=%
for /f "tokens=1 delims=." %%a in ("%JAVA_VER%") do set JAVA_MAJOR=%%a

if %JAVA_MAJOR% LSS %JAVA_VERSION% (
    echo %ERROR% Java 版本过低，需要 JDK %JAVA_VERSION%+，当前版本: %JAVA_VER%
    exit /b 1
)

echo %INFO% Java 版本检查通过: %JAVA_VER%
exit /b 0

:check_jpackage
echo %INFO% 检查 jpackage 工具...
where jpackage >nul 2>&1
if errorlevel 1 (
    echo %ERROR% 未找到 jpackage，请确保安装了完整的 JDK %JAVA_VERSION%
    exit /b 1
)
echo %INFO% jpackage 可用
exit /b 0

:clean
echo %INFO% 清理旧的构建文件...
if exist "%PACKAGE_DIR%" rmdir /s /q "%PACKAGE_DIR%"
if exist "%INSTALLER_DIR%" rmdir /s /q "%INSTALLER_DIR%"
echo %INFO% 清理完成
exit /b 0

:build
echo %INFO% 使用 Maven 构建项目...
cd /d "%PROJECT_DIR%"

REM 检查是否存在 mvnw.cmd
if exist "%PROJECT_DIR%\mvnw.cmd" (
    call mvnw.cmd clean compile jar:jar -Drevision=%APP_VERSION% -DskipTests -Pwindows
) else (
    call mvn clean compile jar:jar -Drevision=%APP_VERSION% -DskipTests -Pwindows
)

if errorlevel 1 (
    echo %ERROR% Maven 构建失败
    exit /b 1
)

echo %INFO% Maven 构建完成
exit /b 0

:prepare_dependencies
echo %INFO% 准备依赖文件...

REM 确保目录存在
mkdir "%PACKAGE_DIR%" 2>nul
mkdir "%LIBS_DIR%" 2>nul
mkdir "%INSTALLER_DIR%" 2>nul

cd /d "%PROJECT_DIR%"

REM target/classes 已包含 Maven 过滤后的完整资源，禁止再用源码资源覆盖版本号。
echo %INFO% 创建应用 JAR...
cd /d "%TARGET_DIR%\classes"
jar cf "%LIBS_DIR%\app.jar" .
cd /d "%PROJECT_DIR%"

REM 使用 Maven 复制依赖
echo %INFO% 复制依赖库...
if exist "%PROJECT_DIR%\mvnw.cmd" (
    call mvnw.cmd dependency:copy-dependencies -Drevision=%APP_VERSION% -DoutputDirectory="%LIBS_DIR%" -DincludeScope=runtime -Pwindows
) else (
    call mvn dependency:copy-dependencies -Drevision=%APP_VERSION% -DoutputDirectory="%LIBS_DIR%" -DincludeScope=runtime -Pwindows
)

if errorlevel 1 (
    echo %ERROR% 复制依赖失败
    exit /b 1
)

echo %INFO% 依赖文件准备完成
exit /b 0

:verify_package
echo %INFO% 验证打包结果...
jar tf "%LIBS_DIR%\app.jar" | findstr /i "SnapVideoApplication.class" >nul
if errorlevel 1 (
    echo %ERROR% 主类未找到，请检查构建过程
    exit /b 1
)
echo %INFO% 主类验证通过
exit /b 0

:create_launcher_script
echo %INFO% 创建启动脚本...
(
echo @echo off
echo set SCRIPT_DIR=%%~dp0
echo java -cp "%%SCRIPT_DIR%%libs\*" ^
echo     --add-opens=java.base/java.lang=ALL-UNNAMED ^
echo     --add-opens=java.base/java.lang.reflect=ALL-UNNAMED ^
echo     --add-opens=java.base/java.io=ALL-UNNAMED ^
echo     %MAIN_CLASS% %%*
) > "%PACKAGE_DIR%\run.bat"
exit /b 0

:create_package
set PKG_TYPE=%1
echo %INFO% 使用 jpackage 创建 %PKG_TYPE% 安装包...

REM 检查图标文件
set ICON_OPTION=
if exist "%PROJECT_DIR%\src\main\resources\icon.ico" (
    set ICON_OPTION=--icon "%PROJECT_DIR%\src\main\resources\icon.ico"
    echo %INFO% 使用自定义图标
) else (
    echo %WARN% 未找到图标文件 icon.ico，将使用默认图标
)

jpackage ^
    --type %PKG_TYPE% ^
    --name "%APP_NAME%" ^
    --app-version "%APP_VERSION%" ^
    --vendor "%VENDOR%" ^
    --description "%DESCRIPTION%" ^
    --copyright "%COPYRIGHT%" ^
    --input "%LIBS_DIR%" ^
    --main-jar "app.jar" ^
    --main-class "%MAIN_CLASS%" ^
    --dest "%INSTALLER_DIR%" ^
    --java-options "-Xmx512m" ^
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" ^
    --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED" ^
    --java-options "--add-opens=java.base/java.io=ALL-UNNAMED" ^
    --java-options "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" ^
    --java-options "--add-opens=java.base/java.util=ALL-UNNAMED" ^
    --win-dir-chooser ^
    --win-menu ^
    --win-menu-group "%APP_NAME%" ^
    --win-shortcut ^
    --win-shortcut-prompt ^
    %ICON_OPTION%

if errorlevel 1 (
    echo %ERROR% 创建 %PKG_TYPE% 安装包失败
    exit /b 1
)

echo %INFO% %PKG_TYPE% 安装包创建完成
exit /b 0

:show_result
echo.
echo ============================================================
echo %INFO% 打包完成！
echo ============================================================
echo.
echo 输出目录: %INSTALLER_DIR%
echo.
dir "%INSTALLER_DIR%"
echo.
echo %INFO% 您可以将安装程序分发给用户
echo.
exit /b 0

:show_help
echo.
echo 用法: %~nx0 [选项]
echo.
echo 选项:
echo   --help, -h     显示帮助信息
echo   --clean        仅清理构建文件
echo   --build-only   仅构建，不打包
echo   --skip-build   跳过 Maven 构建（使用已有的文件）
echo   --msi          同时创建 MSI 安装包
echo.
echo 示例:
echo   %~nx0              完整构建并创建 EXE 安装程序
echo   %~nx0 --msi        同时创建 EXE 和 MSI 安装包
echo.
echo 注意:
echo   - 需要安装 JDK %JAVA_VERSION% 或更高版本
echo   - 创建 EXE/MSI 需要安装 WiX Toolset 3.0+
echo.
goto :eof

:clean_only
call :clean
echo %INFO% 清理完成
goto :eof

:build_only
call :check_java
if errorlevel 1 goto :error_exit
call :clean
call :build
if errorlevel 1 goto :error_exit
call :prepare_dependencies
if errorlevel 1 goto :error_exit
call :verify_package
if errorlevel 1 goto :error_exit
call :create_launcher_script
echo %INFO% 构建完成，可以使用 %PACKAGE_DIR%\run.bat 测试运行
goto :eof

:error_exit
echo.
echo %ERROR% 打包过程中发生错误，请检查上述错误信息
exit /b 1
