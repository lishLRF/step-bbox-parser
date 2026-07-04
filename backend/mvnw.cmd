@REM Maven wrapper for Windows. Replace with the official mvnw.cmd from
@REM https://maven.apache.org/wrapper/ in production.
@echo off
setlocal
where mvn >nul 2>&1
if %errorlevel%==0 (
  mvn %*
  exit /b %errorlevel%
)
if defined MAVEN_HOME (
  "%MAVEN_HOME%\bin\mvn" %*
  exit /b %errorlevel%
)
echo Maven not found. Install Maven or set MAVEN_HOME. 1>&2
exit /b 127
