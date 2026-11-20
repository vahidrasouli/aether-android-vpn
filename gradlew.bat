@rem Minimal Windows Gradle Wrapper launcher (Gradle 8.9)
@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  where gradle >nul 2>nul
  if %ERRORLEVEL% equ 0 (
    gradle %*
    exit /b %ERRORLEVEL%
  )
  echo ERROR: %WRAPPER_JAR% is missing and gradle is not in PATH.
  echo Bootstrap once with: gradle wrapper --gradle-version 8.9
  exit /b 1
)

java %GRADLE_OPTS% -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
