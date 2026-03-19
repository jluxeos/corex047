#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    wget -O "$WRAPPER_JAR" "https://github.com/gradle/gradle/raw/v8.0.0/gradle/wrapper/gradle-wrapper.jar"
fi
GRADLE_USER_HOME="$APP_HOME/.gradle_home"
mkdir -p "$GRADLE_USER_HOME"
exec java -DGRADLE_USER_HOME="$GRADLE_USER_HOME" -Dgradle.user.home="$GRADLE_USER_HOME" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
