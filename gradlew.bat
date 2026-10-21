@echo off
setlocal
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo Gradle no esta instalado en este PC. Usa Android Studio o GitHub Actions para compilar.
exit /b 1
