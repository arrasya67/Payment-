#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -x /data/.jdk/bin/javac ]]; then
  export JAVA_HOME=/data/.jdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi
rm -rf build
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -d build/classes @build/sources.txt
cp -R src/main/resources/* build/classes/
cat > build/manifest.mf <<'EOF'
Main-Class: com.ruangpay.RuangPayApplication
EOF
jar cfm build/ruangpay.jar build/manifest.mf -C build/classes .
echo "Built build/ruangpay.jar"
