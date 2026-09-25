@echo off
REM run.bat — 启动脚本，不需要配置环境变量

REM 设置 JAVA_HOME（临时，只在这个脚本里生效）
set JAVA_HOME=%~dp0jdk17\jdk-17.0.14+7
set PATH=%JAVA_HOME%\bin;%PATH%

REM 用 Gradle Wrapper 编译并运行
echo Compiling and running...
call gradlew.bat bootRun

pause
