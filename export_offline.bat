@echo off
echo ========================================================
echo BUILD AND EXPORT DOCKER IMAGE FOR OFFLINE ENVIRONMENT
echo ========================================================
echo.

echo 1. Building the Docker image...
docker build -t csorgchart:latest .
if %errorlevel% neq 0 (
    echo [ERROR] Failed to build docker image!
    pause
    exit /b %errorlevel%
)
echo.

echo 2. Pulling PostgreSQL image...
docker pull postgres:15-alpine
if %errorlevel% neq 0 (
    echo [ERROR] Failed to pull postgres image!
    pause
    exit /b %errorlevel%
)
echo.

echo 3. Saving the Docker images to .tar archives...
echo This might take a minute, please wait...
docker save -o csorgchart_offline.tar csorgchart:latest
if %errorlevel% neq 0 (
    echo [ERROR] Failed to save csorgchart image!
    pause
    exit /b %errorlevel%
)

docker save -o postgres_offline.tar postgres:15-alpine
if %errorlevel% neq 0 (
    echo [ERROR] Failed to save postgres image!
    pause
    exit /b %errorlevel%
)
echo.

echo ========================================================
echo [SUCCESS] The images were successfully saved to:
echo csorgchart_offline.tar and postgres_offline.tar
echo.
echo Now you need to copy the following files to your DEV server:
echo 1) csorgchart_offline.tar
echo 2) postgres_offline.tar
echo 3) docker-compose.yml
echo 4) data\ (folder with result.xlsx)
echo 5) photos\ (folder with photos)
echo 6) deploy_offline.bat
echo ========================================================
pause
