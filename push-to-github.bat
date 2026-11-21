@echo off
setlocal
cd /d "%~dp0"

where git >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Git is not installed on this PC.
  echo Download Git for Windows from: https://git-scm.com/download/win
  pause
  exit /b 1
)

if not exist ".git" (
  git init
)

for /f "delims=" %%i in ('git config user.name') do set GIT_NAME=%%i
if "%GIT_NAME%"=="" (
  git config user.name "kefinidoe"
)

for /f "delims=" %%i in ('git config user.email') do set GIT_EMAIL=%%i
if "%GIT_EMAIL%"=="" (
  git config user.email "kefinidoe@users.noreply.github.com"
)

git add -A
git commit -m "GifTap Android floating overlay" >nul 2>nul
git branch -M main

git remote get-url origin >nul 2>nul
if errorlevel 1 (
  git remote add origin https://github.com/kefinidoe/giftap-overlay.git
) else (
  git remote set-url origin https://github.com/kefinidoe/giftap-overlay.git
)

echo.
echo Pushing GifTap Android Overlay to https://github.com/kefinidoe/giftap-overlay.git ...
echo If a GitHub sign-in window opens, sign in to approve the push.
echo.
git push -u origin main --force

if errorlevel 1 (
  echo.
  echo [FAILED] Push did not complete. Check your GitHub sign-in and try again.
  pause
  exit /b 1
)

echo.
echo [SUCCESS] Everything is pushed!
echo Open https://github.com/kefinidoe/giftap-overlay/actions to watch the APK build (~2 mins).
echo When finished, your direct APK download will appear under Releases and inside GifTap!
echo.
pause
