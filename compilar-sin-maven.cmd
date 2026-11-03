@echo off
rem Compila y empaqueta rutapaq.jar solo con el JDK 17+ (sin Maven), en Windows.
rem Uso, desde la carpeta del proyecto (terminal de VS Code, CMD o PowerShell):   .\compilar-sin-maven.cmd
setlocal EnableDelayedExpansion
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
rem Lista de fuentes con barras normales y entre comillas (funciona aunque la ruta tenga espacios)
(for /r "src\main\java" %%f in (*.java) do (
  set "p=%%f"
  echo "!p:\=/!"
)) > fuentes.txt
javac --release 17 -encoding UTF-8 -d out @fuentes.txt
if errorlevel 1 goto error
del fuentes.txt
xcopy /e /i /y /q "src\main\resources" "out" >nul
jar cfe rutapaq.jar pe.pucp.paqrap.app.App -C out .
if errorlevel 1 goto error
rmdir /s /q out
echo Generado rutapaq.jar
exit /b 0
:error
echo No se pudo compilar. Revise que el JDK 17 o superior este instalado (javac -version).
exit /b 1
