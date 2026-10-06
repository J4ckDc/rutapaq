#!/usr/bin/env bash
# Compila y empaqueta rutapaq.jar solo con el JDK 17+ (sin Maven).
set -euo pipefail
rm -rf out && mkdir -p out
javac --release 17 -encoding UTF-8 -d out $(find src/main/java -name "*.java")
cp -r src/main/resources/* out/
jar cfe rutapaq.jar pe.pucp.paqrap.app.App -C out .
echo "Generado rutapaq.jar"
