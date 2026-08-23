#!/bin/bash

# ============================================================
# SnapVideoTools Desktop - Mac 打包脚本
# 生成 .app 应用程序和 .dmg 安装包
# ============================================================

set -e

# 配置变量
APP_NAME="SnapVideoTools"
APP_VERSION="${APP_VERSION:-}"
MAIN_CLASS="com.kalman03.svt.desktop.SnapVideoApplication"
JAVA_VERSION="21"
VENDOR="Kalman03"
DESCRIPTION="Video Downloader Desktop Client"
COPYRIGHT="Copyright © 2026 ${VENDOR}"
MAC_PACKAGE_IDENTIFIER="com.kalman03.snapvideotools"
MAC_SIGNING_IDENTITY="${MAC_SIGNING_IDENTITY:-}"
MAC_SIGNING_KEYCHAIN="${MAC_SIGNING_KEYCHAIN:-}"

# 目录配置
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="${PROJECT_DIR}/target"
PACKAGE_DIR="${TARGET_DIR}/package"
INSTALLER_DIR="${TARGET_DIR}/installer"
LIBS_DIR="${PACKAGE_DIR}/libs"
FFMPEG_RESOURCES_DIR="${PROJECT_DIR}/src/main/resources/ffmpeg"

# FFmpeg 下载配置
FFMPEG_VERSION="7.0.2"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 下载并准备 FFmpeg
prepare_ffmpeg() {
    log_info "准备 FFmpeg..."

    local arch=$(uname -m)
    local platform_dir
    local ffmpeg_url

    if [ "$arch" = "arm64" ] || [ "$arch" = "aarch64" ]; then
        platform_dir="mac-aarch64"
        ffmpeg_url="https://www.osxexperts.net/ffmpeg${FFMPEG_VERSION}arm.zip"
    else
        platform_dir="mac-x64"
        ffmpeg_url="https://www.osxexperts.net/ffmpeg${FFMPEG_VERSION}intel.zip"
    fi

    local ffmpeg_dir="${FFMPEG_RESOURCES_DIR}/${platform_dir}"
    local ffmpeg_path="${ffmpeg_dir}/ffmpeg"
    local ffprobe_path="${ffmpeg_dir}/ffprobe"

    # 检查是否已存在
    if [ -f "${ffmpeg_path}" ] && [ -f "${ffprobe_path}" ]; then
        log_info "FFmpeg 已存在于 ${ffmpeg_dir}"
        return 0
    fi

    mkdir -p "${ffmpeg_dir}"

    log_info "下载 FFmpeg for ${platform_dir}..."
    log_warn "如果自动下载失败，请手动下载 FFmpeg："
    log_warn "  1. 访问 https://evermeet.cx/ffmpeg/ 或 https://www.osxexperts.net/"
    log_warn "  2. 下载 ffmpeg 和 ffprobe"
    log_warn "  3. 将文件放到 ${ffmpeg_dir}/"

    # 尝试从 evermeet.cx 下载（更可靠的源）
    local temp_dir=$(mktemp -d)

    # 下载 ffmpeg
    if curl -L -o "${temp_dir}/ffmpeg.zip" "https://evermeet.cx/ffmpeg/getrelease/ffmpeg/zip" 2>/dev/null; then
        unzip -o "${temp_dir}/ffmpeg.zip" -d "${temp_dir}" 2>/dev/null
        if [ -f "${temp_dir}/ffmpeg" ]; then
            mv "${temp_dir}/ffmpeg" "${ffmpeg_path}"
            chmod +x "${ffmpeg_path}"
            log_info "FFmpeg 下载成功"
        fi
    fi

    # 下载 ffprobe
    if curl -L -o "${temp_dir}/ffprobe.zip" "https://evermeet.cx/ffmpeg/getrelease/ffprobe/zip" 2>/dev/null; then
        unzip -o "${temp_dir}/ffprobe.zip" -d "${temp_dir}" 2>/dev/null
        if [ -f "${temp_dir}/ffprobe" ]; then
            mv "${temp_dir}/ffprobe" "${ffprobe_path}"
            chmod +x "${ffprobe_path}"
            log_info "FFprobe 下载成功"
        fi
    fi

    rm -rf "${temp_dir}"

    # 验证
    if [ -f "${ffmpeg_path}" ] && [ -f "${ffprobe_path}" ]; then
        log_info "FFmpeg 准备完成"
        "${ffmpeg_path}" -version | head -1
    else
        log_warn "FFmpeg 下载失败，请手动下载并放置到 ${ffmpeg_dir}/"
        log_warn "应用仍可构建，但运行时需要系统安装 FFmpeg"
    fi
}

# 检查 Java 版本
check_java() {
    log_info "检查 Java 环境..."

    if ! command -v java &> /dev/null; then
        log_error "未找到 Java，请安装 JDK ${JAVA_VERSION}"
        exit 1
    fi

    JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt "$JAVA_VERSION" ]; then
        log_error "Java 版本过低，需要 JDK ${JAVA_VERSION}+，当前版本: ${JAVA_VER}"
        exit 1
    fi

    log_info "Java 版本检查通过: $(java -version 2>&1 | head -n 1)"
}

# 检查 jpackage 是否可用
check_jpackage() {
    log_info "检查 jpackage 工具..."

    if ! command -v jpackage &> /dev/null; then
        log_error "未找到 jpackage，请确保安装了完整的 JDK ${JAVA_VERSION}"
        exit 1
    fi

    log_info "jpackage 可用"
}

# 校验 jpackage 接受的发布版本，统一限定为三段数字版本号。
validate_version() {
    if [[ ! "${APP_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        log_error "版本号必须符合 X.Y.Z，例如 1.2.3；当前值: ${APP_VERSION}"
        exit 1
    fi
}

# 获取 Maven 命令
get_mvn_cmd() {
    if [ -f "${PROJECT_DIR}/mvnw" ]; then
        echo "${PROJECT_DIR}/mvnw"
    else
        echo "mvn"
    fi
}

# 清理旧的构建文件
clean() {
    log_info "清理旧的构建文件..."
    rm -rf "${PACKAGE_DIR}"
    rm -rf "${INSTALLER_DIR}"
}

# 获取 Mac 架构对应的 Maven profile
get_mac_profile() {
    local arch=$(uname -m)
    if [ "$arch" = "arm64" ] || [ "$arch" = "aarch64" ]; then
        echo "mac-aarch64"
    else
        echo "mac"
    fi
}

# 使用 Maven 构建项目（不使用 Spring Boot repackage）
build() {
    log_info "使用 Maven 构建项目..."
    cd "${PROJECT_DIR}"

    MVN_CMD=$(get_mvn_cmd)
    MAC_PROFILE=$(get_mac_profile)

    log_info "检测到架构: $(uname -m)，使用 profile: ${MAC_PROFILE}"

    # 使用 jar 打包（不是 Spring Boot 的 fat jar）
    ${MVN_CMD} clean compile jar:jar -Drevision="${APP_VERSION}" -DskipTests -P${MAC_PROFILE}

    log_info "Maven 构建完成"
}

# 准备依赖文件
prepare_dependencies() {
    log_info "准备依赖文件..."

    # 确保目录存在
    mkdir -p "${PACKAGE_DIR}"
    mkdir -p "${INSTALLER_DIR}"
    mkdir -p "${LIBS_DIR}"

    cd "${PROJECT_DIR}"
    MVN_CMD=$(get_mvn_cmd)

    # 复制编译后的类文件 jar
    CLASSES_JAR="${TARGET_DIR}/classes"
    if [ -d "${CLASSES_JAR}" ]; then
        # target/classes 已包含 Maven 过滤后的完整资源，禁止再用源码资源覆盖版本号。
        log_info "创建应用 JAR..."
        cd "${CLASSES_JAR}"
        jar cf "${LIBS_DIR}/app.jar" .
        cd "${PROJECT_DIR}"
    fi

    # 复制所有运行时依赖
    log_info "复制依赖库..."
    MAC_PROFILE=$(get_mac_profile)
    ${MVN_CMD} dependency:copy-dependencies -Drevision="${APP_VERSION}" \
        -DoutputDirectory="${LIBS_DIR}" \
        -DincludeScope=runtime \
        -P${MAC_PROFILE}

    # 统计依赖数量
    JAR_COUNT=$(ls -1 "${LIBS_DIR}"/*.jar 2>/dev/null | wc -l)
    log_info "依赖文件准备完成，共 ${JAR_COUNT} 个 JAR 文件"
}

# 创建启动脚本（用于调试）
create_launcher_script() {
    log_info "创建启动脚本..."

    # 构建 classpath
    CLASSPATH=""
    for jar in "${LIBS_DIR}"/*.jar; do
        if [ -f "$jar" ]; then
            if [ -z "$CLASSPATH" ]; then
                CLASSPATH="$(basename $jar)"
            else
                CLASSPATH="${CLASSPATH}:$(basename $jar)"
            fi
        fi
    done

    cat > "${PACKAGE_DIR}/run.sh" << EOF
#!/bin/bash
SCRIPT_DIR="\$(cd "\$(dirname "\$0")" && pwd)"
java -cp "\${SCRIPT_DIR}/libs/*" \\
    --add-opens=java.base/java.lang=ALL-UNNAMED \\
    --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \\
    --add-opens=java.base/java.io=ALL-UNNAMED \\
    ${MAIN_CLASS} "\$@"
EOF
    chmod +x "${PACKAGE_DIR}/run.sh"
}

# Apple 公证会检查 JAR 等嵌套容器中的 Mach-O 文件，必须在 jpackage 前逐个签名。
sign_embedded_macos_binaries() {
    if [ -z "${MAC_SIGNING_IDENTITY}" ]; then
        return
    fi

    log_info "签名依赖 JAR 内的 macOS 原生二进制..."
    local temp_dir
    local signed_count=0
    temp_dir=$(mktemp -d)

    while IFS= read -r jar_file; do
        local extract_dir="${temp_dir}/contents"
        local signed_paths=()
        rm -rf "${extract_dir}"
        mkdir -p "${extract_dir}"
        (
            cd "${extract_dir}"
            jar xf "${jar_file}"
        )

        while IFS= read -r candidate; do
            if file -b "${candidate}" | grep -q 'Mach-O'; then
                local relative_path="${candidate#${extract_dir}/}"
                codesign --force --timestamp --options runtime \
                    --keychain "${MAC_SIGNING_KEYCHAIN}" \
                    --sign "${MAC_SIGNING_IDENTITY}" \
                    "${candidate}"
                codesign --verify --strict --verbose=2 "${candidate}"
                signed_paths+=("${relative_path}")
                signed_count=$((signed_count + 1))
                log_info "已签名嵌套原生文件: $(basename "${jar_file}")!/${relative_path}"
            fi
        done < <(find "${extract_dir}" -type f)

        if [ "${#signed_paths[@]}" -gt 0 ]; then
            if find "${extract_dir}/META-INF" -maxdepth 1 -type f \
                \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \) \
                -print -quit 2>/dev/null | grep -q .; then
                log_error "JAR 带有签名元数据，无法安全回写原生文件: ${jar_file}"
                rm -rf "${temp_dir}"
                exit 1
            fi
            (
                cd "${extract_dir}"
                jar uf "${jar_file}" "${signed_paths[@]}"
            )
        fi
    done < <(find "${LIBS_DIR}" -maxdepth 1 -type f -name '*.jar' | sort)

    rm -rf "${temp_dir}"
    if [ "${signed_count}" -eq 0 ]; then
        log_error "未在应用依赖中找到可签名的 macOS 原生二进制"
        exit 1
    fi
    log_info "嵌套原生二进制签名完成，共 ${signed_count} 个文件"
}

# 使用 jpackage 创建安装包
create_package() {
    local pkg_type="$1"
    log_info "使用 jpackage 创建 ${pkg_type} 安装包..."

    # jpackage 参数
    JPACKAGE_ARGS=(
        --type "${pkg_type}"
        --name "${APP_NAME}"
        --app-version "${APP_VERSION}"
        --vendor "${VENDOR}"
        --description "${DESCRIPTION}"
        --copyright "${COPYRIGHT}"
        --input "${LIBS_DIR}"
        --main-jar "app.jar"
        --main-class "${MAIN_CLASS}"
        --dest "${INSTALLER_DIR}"
        --java-options "-Xmx512m"
        --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED"
        --java-options "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        --java-options "--add-opens=java.base/java.io=ALL-UNNAMED"
        --java-options "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
        --java-options "--add-opens=java.base/java.util=ALL-UNNAMED"
        --mac-package-name "${APP_NAME}"
        --mac-package-identifier "${MAC_PACKAGE_IDENTIFIER}"
    )

    # CI 注入签名身份后，由 jpackage 对应用包及其原生组件统一签名。
    if [ -n "${MAC_SIGNING_IDENTITY}" ]; then
        if ! command -v security >/dev/null 2>&1; then
            log_error "未找到 macOS security 工具，无法验证签名证书"
            exit 1
        fi
        if [ -n "${MAC_SIGNING_KEYCHAIN}" ]; then
            if ! security find-identity -v -p codesigning "${MAC_SIGNING_KEYCHAIN}" | grep -Fq "${MAC_SIGNING_IDENTITY}"; then
                log_error "指定钥匙串中未找到签名身份: ${MAC_SIGNING_IDENTITY}"
                exit 1
            fi
            JPACKAGE_ARGS+=(--mac-signing-keychain "${MAC_SIGNING_KEYCHAIN}")
        elif ! security find-identity -v -p codesigning | grep -Fq "${MAC_SIGNING_IDENTITY}"; then
            log_error "系统钥匙串中未找到签名身份: ${MAC_SIGNING_IDENTITY}"
            exit 1
        fi
        JPACKAGE_ARGS+=(
            --mac-sign
            --mac-signing-key-user-name "${MAC_SIGNING_IDENTITY}"
            --mac-package-signing-prefix "${MAC_PACKAGE_IDENTIFIER}."
        )
        log_info "已启用 macOS Developer ID 签名"
    else
        log_warn "未配置 MAC_SIGNING_IDENTITY，将生成未签名安装包"
    fi

    # 添加图标（如果存在）
    if [ -f "${PROJECT_DIR}/src/main/resources/icon.icns" ]; then
        JPACKAGE_ARGS+=(--icon "${PROJECT_DIR}/src/main/resources/icon.icns")
    fi

    sign_embedded_macos_binaries

    # 执行 jpackage
    jpackage "${JPACKAGE_ARGS[@]}"

    if [ "${pkg_type}" = "dmg" ]; then
        local package_file
        package_file=$(find "${INSTALLER_DIR}" -maxdepth 1 -type f -name '*.dmg' -print -quit)
        if [ -z "${package_file}" ]; then
            log_error "jpackage 未生成 DMG 文件"
            exit 1
        fi
    elif [ ! -d "${INSTALLER_DIR}/${APP_NAME}.app" ]; then
        log_error "jpackage 未生成 macOS 应用目录"
        exit 1
    fi

    log_info "${pkg_type} 安装包创建完成"
}

# 验证打包结果
verify_package() {
    log_info "验证打包结果..."

    # 检查 app.jar 是否包含主类
    if jar tf "${LIBS_DIR}/app.jar" | grep -q "SnapVideoApplication.class"; then
        log_info "主类验证通过"
    else
        log_error "主类未找到，请检查构建过程"
        exit 1
    fi
}

# 显示结果
show_result() {
    log_info "=========================================="
    log_info "打包完成！"
    log_info "=========================================="
    log_info "输出目录: ${INSTALLER_DIR}"
    echo ""
    ls -la "${INSTALLER_DIR}"
    echo ""
    log_info "您可以将 .dmg 文件分发给用户安装"
}

# 显示帮助
show_help() {
    echo "用法: $0 [选项]"
    echo ""
    echo "选项:"
    echo "  --help, -h     显示帮助信息"
    echo "  --clean        仅清理构建文件"
    echo "  --build-only   仅构建，不打包"
    echo "  --app-only     仅创建 .app，不创建 .dmg"
    echo "  --skip-build   跳过 Maven 构建（使用已有的文件）"
    echo "  --debug        创建后从命令行测试运行"
    echo "  --version VER  指定 X.Y.Z 格式的应用版本"
    echo ""
    echo "示例:"
    echo "  $0              # 完整构建并创建 .dmg"
    echo "  $0 --app-only   # 仅创建 .app 应用程序"
    echo "  $0 --debug      # 构建并测试运行"
    echo "  $0 --version 1.2.3"
}

# 主流程
main() {
    local skip_build=false
    local app_only=false
    local debug_mode=false
    local clean_only=false
    local build_only=false

    # 解析参数
    while [[ $# -gt 0 ]]; do
        case $1 in
            --help|-h)
                show_help
                exit 0
                ;;
            --clean)
                clean_only=true
                shift
                ;;
            --build-only)
                build_only=true
                shift
                ;;
            --app-only)
                app_only=true
                shift
                ;;
            --skip-build)
                skip_build=true
                shift
                ;;
            --debug)
                debug_mode=true
                shift
                ;;
            --version)
                if [ $# -lt 2 ]; then
                    log_error "--version 需要提供 X.Y.Z 格式的版本号"
                    exit 1
                fi
                APP_VERSION="$2"
                shift 2
                ;;
            *)
                log_error "未知选项: $1"
                show_help
                exit 1
                ;;
        esac
    done

    if [ -z "${APP_VERSION}" ]; then
        APP_VERSION=$(cd "${PROJECT_DIR}" && $(get_mvn_cmd) help:evaluate \
            -Dexpression=revision -q -DforceStdout)
    fi
    validate_version

    if [ "$clean_only" = true ]; then
        clean
        log_info "清理完成"
        exit 0
    fi

    # 执行构建流程
    check_java
    check_jpackage
    clean

    if [ "$build_only" = true ]; then
        build
        prepare_dependencies
        verify_package
        create_launcher_script
        log_info "构建完成，可以使用 ${PACKAGE_DIR}/run.sh 测试运行"
        exit 0
    fi

    # 准备 FFmpeg（下载或检查）
    prepare_ffmpeg

    if [ "$skip_build" = false ]; then
        build
    fi

    prepare_dependencies
    verify_package
    create_launcher_script

    # 调试模式：先测试运行
    if [ "$debug_mode" = true ]; then
        log_info "测试运行应用..."
        "${PACKAGE_DIR}/run.sh" &
        APP_PID=$!
        sleep 5
        if ps -p $APP_PID > /dev/null 2>&1; then
            log_info "应用启动成功，PID: ${APP_PID}"
            log_info "按 Enter 继续打包，或 Ctrl+C 取消..."
            read
            kill $APP_PID 2>/dev/null || true
        else
            log_error "应用启动失败，请检查日志"
            exit 1
        fi
    fi

    if [ "$app_only" = true ]; then
        create_package "app-image"
    else
        create_package "dmg"
    fi

    show_result
}

# 运行主流程
main "$@"
