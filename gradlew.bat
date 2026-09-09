@rem EliCode Gradle wrapper startup script for Windows.
@rem Pinned to Gradle 8.14.3 (see gradle/wrapper/gradle-wrapper.properties).
@if "%DEBUG%"=="" @echo off
set APP_HOME=%DIRNAME%..
if not exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
  echo gradle-wrapper.jar not found. Run: gradle wrapper --gradle-version 8.14.3
  where gradle >nul 2>nul
  if %ERRORLEVEL%==0 (
    gradle %*
    exit /b %ERRORLEVEL%
  ) else (
    exit /b 1
  )
)
java -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
