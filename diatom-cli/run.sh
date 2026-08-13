#!/bin/bash
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
CUSTOM_JAR="$(dirname "$0")/diatom-custom/target/diatom-custom.jar"
if [ -f "$CUSTOM_JAR" ]; then
    java -Dfile.encoding=UTF-8 -jar "$CUSTOM_JAR" "$@"
else
    java -Dfile.encoding=UTF-8 -jar "$(dirname "$0")/diatom-cli.jar" "$@"
fi
