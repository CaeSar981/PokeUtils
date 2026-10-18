#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  PokeUtils 构建脚本
#
#  本机没有安装 maven，因此直接用 javac + jar 出包，等价于 `mvn package` 的结果：
#    · stub-api 只作编译期 classpath，绝不进最终 jar（等价于 provided 作用域）
#    · 插件本体 + resources/*.yml 打成 dist/PokeUtils-remake-1.0.0-SNAPSHOT.jar
#
#  与 maven 的差异（重要）：本项目的存储层依赖 SQLite JDBC 驱动。maven 会由
#  maven-shade-plugin 自动 shade + relocate，这里改为手工做同样三件事 ——
#  build 顺序也因此调整为「先准备驱动，再编译插件」：
#    1. 把 libs/sqlite-jdbc-*.jar 解开，剔除无关平台的 native 库；
#    2. 把包名 org/sqlite 重定位成 pokeshaded（见 tools/relocate.py 里的说明，
#       这一步不是可选项：CatServer 自带 sqlite-jdbc-3.21，不重定位就会被
#       父加载器抢走，运行期直接 NoSuchMethodError）；
#    3. 与本插件 class 一起打成一个 jar。
#
#  用法:
#    ./build.sh          仅构建
#    ./build.sh deploy   构建并同步到 ../TestServer/plugins/
#
#  可选环境变量:
#    SQLITE_JAR=<path>           指定其它版本的 sqlite-jdbc
#    SQLITE_ALL_PLATFORMS=1      打包全部平台的 native 库（默认只留 Windows/Linux
#                                的 x86 与 x86_64，产物约 2.3MB；全平台约 13MB）
#    PYTHON=<path>               重定位脚本用的解释器
# ---------------------------------------------------------------------------
set -e

# ---------------------------------------------------------------------------
#  平台差异：路径写法与 classpath 分隔符
#
#  在 Git Bash（Windows）里，java/javac 是原生 Windows 程序，必须喂 `C:/x/y`
#  这种盘符路径，且 classpath 用 ';' 分隔；在 Linux/macOS 上恰好相反。
#  GitHub Actions 跑在 ubuntu 上，所以这里必须两种都支持，不能直接调 cygpath。
#  下面的 W() 就是唯一的路径转换入口 —— 新增路径参数时请一并走它。
# ---------------------------------------------------------------------------
if command -v cygpath >/dev/null 2>&1; then
    W() { cygpath -w "$1"; }
    CP_SEP=';'
else
    W() { printf '%s\n' "$1"; }
    CP_SEP=':'
fi

HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK_HOME:-/c/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot}"
JAVAC="$JDK/bin/javac"
JAR="$JDK/bin/jar"
SPIGOT="${SPIGOT_API_JAR:-$HOME/.m2/repository/org/spigotmc/spigot-api/1.12.2-R0.1-SNAPSHOT/spigot-api-1.12.2-R0.1-SNAPSHOT.jar}"
STUB_SRC="$HERE/stub-api/src/main/java"
PLUGIN_SRC="$HERE/plugin/src/main/java"
RES="$HERE/plugin/src/main/resources"
OUT="$HERE/build"
DIST="$HERE/dist/PokeUtils-remake-1.0.0-SNAPSHOT.jar"
LIB_DIR="$HERE/libs"
SQLITE_JAR="${SQLITE_JAR:-$(ls "$LIB_DIR"/sqlite-jdbc-*.jar 2>/dev/null | head -1)}"

# 重定位后的顶层包名。必须与 org/sqlite、org.sqlite 等长（都是 10 个字符），
# 这样 tools/relocate.py 才能用纯字节替换完成，无需重写 class 常量池。
RELOCATED="pokeshaded"

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
    if [ -x "$HOME/.workbuddy/binaries/python/versions/3.13.12/python.exe" ]; then
        PYTHON="$HOME/.workbuddy/binaries/python/versions/3.13.12/python.exe"
    else
        PYTHON="$(command -v python3 || command -v python || true)"
    fi
fi

[ -x "$JAVAC" ] || { echo "找不到 javac: $JAVAC"; exit 1; }
[ -f "$SPIGOT" ] || { echo "找不到 spigot-api: $SPIGOT"; exit 1; }
[ -f "$SQLITE_JAR" ] || { echo "找不到 sqlite-jdbc 驱动: $SQLITE_JAR （放一个到 libs/ 目录）"; exit 1; }
[ -n "$PYTHON" ] || { echo "找不到 python（重定位脚本需要它，可用 PYTHON=<path> 指定）"; exit 1; }

echo "==> 清理"
rm -rf "$OUT"
mkdir -p "$OUT/stub" "$OUT/classes" "$OUT/sqlite"

echo "==> 编译编译期桩 (stub-api)"
( cd "$STUB_SRC" && find . -name '*.java' ) > "$OUT/stub-sources.txt"
( cd "$STUB_SRC" && "$JAVAC" -J-Duser.language=en -J-Duser.country=US -nowarn -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -cp "$(W "$SPIGOT")" \
    -d "$(W "$OUT/stub")" "@$(W "$OUT/stub-sources.txt")" )
echo "    $(find "$OUT/stub" -name '*.class' | wc -l) 个 class"

echo "==> 准备 SQLite 驱动（展开 / 裁剪 / 重定位）"
# 整包解开再裁。注意：没有用 `unzip -x` 排除 —— 实测 Git Bash 自带的 Info-ZIP 会把
# archive 之后的 `-x` 连同模式一起当成「待解压文件列表」，于是静默地全量解开，很难察觉。
unzip -qo "$SQLITE_JAR" -d "$OUT/sqlite"

# ① 构建元数据与多版本实现直接删。versions/9 是给 Java 9+ 的 multi-release 实现
#    （Java 8 不认，留着只是徒增体积）；maven / native-image 是打包元数据。
rm -rf "$OUT/sqlite/META-INF/versions" "$OUT/sqlite/META-INF/maven" \
       "$OUT/sqlite/META-INF/native-image" "$OUT/sqlite/META-INF/MANIFEST.MF"

# ② native 库白名单。默认只留真正会跑服务端的四个组合，否则光 .dll/.so 就 12MB。
PLATFORMS="Windows/x86_64 Windows/x86 Linux/x86_64 Linux/x86"
if [ "$SQLITE_ALL_PLATFORMS" = "1" ]; then
    PLATFORMS="$(find "$OUT/sqlite/org/sqlite/native" -mindepth 2 -maxdepth 2 -type d \
        | sed "s#.*/native/##" | tr '\n' ' ')"
fi
(
    cd "$OUT/sqlite/org/sqlite/native"
    for OS in *; do
        for ARCH in "$OS"/*; do
            [ -d "$ARCH" ] || continue
            KEEP=0
            for P in $PLATFORMS; do
                # $ARCH 已经带 OS 前缀（如 Windows/x86_64），不要再拼一次
                [ "$P" = "$ARCH" ] && KEEP=1
            done
            [ "$KEEP" = "1" ] || rm -rf "$ARCH"
        done
        [ -n "$(ls -A "$OS" 2>/dev/null)" ] || rmdir "$OS"
    done
)

# ③ 包名重定位。必须做：CatServer 的 libraries/ 里自带 sqlite-jdbc-3.21.0.1.jar，
#    而插件 ClassLoader 会先委托父加载器；父能解析 org.sqlite.SQLiteConfig，
#    我们 shade 进来的 3.42 就永远不会被用到 —— 运行期直接抛
#    NoSuchMethodError: org.sqlite.SQLiteConfig.setBusyTimeout(I)V（3.24+ 才有）。
"$PYTHON" "$HERE/tools/relocate.py" "$(W "$OUT/sqlite")" "org/sqlite" "$RELOCATED"
echo "    class $(find "$OUT/sqlite/$RELOCATED" -name '*.class' | wc -l) 个, native $(find "$OUT/sqlite/$RELOCATED/native" -type f | wc -l) 个"
echo "    保留平台: $PLATFORMS"

# 编译期 classpath 用重定位后的版本 —— 插件源码 import 的就是 $RELOCATED.*
SQLITE_CP="$OUT/sqlite-relocated.jar"
rm -f "$SQLITE_CP"
"$JAR" cf "$(W "$SQLITE_CP")" -C "$(W "$OUT/sqlite")" .

echo "==> 编译插件本体"
( cd "$PLUGIN_SRC" && find . -name '*.java' ) > "$OUT/plugin-sources.txt"
( cd "$PLUGIN_SRC" && "$JAVAC" -J-Duser.language=en -J-Duser.country=US -nowarn -encoding UTF-8 \
    -source 1.8 -target 1.8 \
    -cp "$(W "$OUT/stub")${CP_SEP}$(W "$SPIGOT")${CP_SEP}$(W "$SQLITE_CP")" \
    -d "$(W "$OUT/classes")" "@$(W "$OUT/plugin-sources.txt")" )
echo "    $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"

echo "==> 复制资源"
cp "$RES/plugin.yml" "$OUT/classes/"
cp "$RES/config.yml" "$OUT/classes/"

echo "==> 打包"
mkdir -p "$(dirname "$DIST")"
rm -f "$DIST"
"$JAR" cf "$(W "$DIST")" \
    -C "$(W "$OUT/classes")" . \
    -C "$(W "$OUT/sqlite")" .
echo "    产物: $DIST ($(stat -c%s "$DIST") 字节)"

# 依赖桩不得混进产物
"$JAR" tf "$DIST" > "$OUT/entries.txt"
LEAK="$(grep -cE '^(com/mc9y/pokestar|com/mc9y/pokemonapi|com/mc9y/blank038api|com/mc9y/msgtoggle|com/pixelmonmod|net/minecraft|net/milkbowl)/' "$OUT/entries.txt" || true)"
echo "    第三方桩泄漏检查: $LEAK 个条目（应为 0）"
[ "$LEAK" = "0" ] || { echo "!! 桩类混进了产物"; exit 1; }

# SQLite 驱动反过来必须「在」产物里 —— 漏了它插件会在服务端启动时抛 ClassNotFoundException
SQLITE_CLASS="$(grep -c "^$RELOCATED/.*\.class$" "$OUT/entries.txt" || true)"
SQLITE_NATIVE="$(grep -c "^$RELOCATED/native/.*\.\(dll\|so\|jnilib\)$" "$OUT/entries.txt" || true)"
echo "    SQLite 驱动: $SQLITE_CLASS 个 class + $SQLITE_NATIVE 个 native（都应为 > 0）"
[ "$SQLITE_CLASS" -gt 0 ] || { echo "!! SQLite 驱动 class 没打进去"; exit 1; }
[ "$SQLITE_NATIVE" -gt 0 ] || { echo "!! SQLite native 库没打进去，装载时必定 UnsatisfiedLinkError"; exit 1; }

# 重定位必须彻底：产物里不应再有任何 org/sqlite 条目
STALE="$(grep -c '^org/sqlite' "$OUT/entries.txt" || true)"
echo "    重定位残留检查: $STALE 个 org/sqlite 条目（应为 0）"
[ "$STALE" = "0" ] || { echo "!! 重定位不彻底，运行期会被父加载器的旧版驱动顶掉"; exit 1; }

if [ "$1" = "deploy" ]; then
    TARGET="$HERE/../TestServer/plugins/PokeUtils-remake-1.0.0-SNAPSHOT.jar"
    cp "$DIST" "$TARGET"
    echo "==> 已部署到 $TARGET"
fi
