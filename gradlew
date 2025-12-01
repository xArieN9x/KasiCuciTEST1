#!/usr/bin/env sh

# Gradle wrapper minimal script
DIR="$( cd "$( dirname "$0" )" >/dev/null 2>&1 && pwd )"
"$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
