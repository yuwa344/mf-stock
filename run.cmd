@echo off
rem MF-Stock: multi-factor stock analyzer (ASCII only, run via Git Bash: ./run.cmd)
setlocal
set "JAVA_HOME=C:\Users\Huang\.workbuddy\binaries\java\jdk-21.0.12.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "DIR=%~dp0"

if "%~1"=="build" goto build
if "%~1"=="run" goto run
goto build

:build
echo [MF-Stock] compiling ...
cd /d "%DIR%"
if not exist classes mkdir classes
javac -encoding UTF-8 -d classes src\*.java
if errorlevel 1 (
  echo [MF-Stock] BUILD FAILED
  exit /b 1
)
echo [MF-Stock] build ok
if "%~1"=="build" exit /b 0

:run
cd /d "%DIR%"
echo [MF-Stock] starting server on port 8080 ...
java -Xms128m -Xmx1024m -cp classes StockServer
