@echo off
rem Gera o APK assinado em dist\. Precisa do JDK 17: usa o da Microsoft, se estiver instalado, ou o JAVA_HOME.
if not exist "%~dp0keystore.properties" (
    echo Falta keystore.properties: sem ele o APK sai sem assinatura e nao instala. Veja "Como compilar" no README.
    exit /b 1
)
for /d %%d in ("%ProgramFiles%\Microsoft\jdk-17*") do set "JAVA_HOME=%%d"
call "%~dp0gradlew.bat" :core:test :app:assembleRelease || exit /b 1
if not exist "%~dp0dist" mkdir "%~dp0dist"
copy /y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0dist\ScanLimpo.apk" >nul || exit /b 1
echo.
echo APK pronto: %~dp0dist\ScanLimpo.apk
