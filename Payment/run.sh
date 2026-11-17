#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -x /data/.jdk/bin/java ]]; then
  export JAVA_HOME=/data/.jdk
  export PATH="$JAVA_HOME/bin:$PATH"
fi
./build.sh
exec java -jar build/ruangpay.jar
