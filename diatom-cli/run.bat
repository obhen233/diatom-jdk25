@echo off
chcp 65001 >nul
set "CUSTOM_JAR=%~dp0diatom-custom\target\diatom-custom.jar"
if exist "%CUSTOM_JAR%" (
    java -Dfile.encoding=UTF-8 -jar "%CUSTOM_JAR%" %*
) else (
    java -Dfile.encoding=UTF-8 -jar "%~dp0diatom-cli.jar" %*
)
