@echo off
echo ========================================================
echo DEPLOYING CS ORGCHART IN OFFLINE ENVIRONMENT
echo ========================================================
echo.

echo 1. Loading the Docker images from tar archives...
docker load -i csorgchart_offline.tar
if %errorlevel% neq 0 (
    echo [ERROR] Failed to load csorgchart docker image from tar file!
    echo Ensure that csorgchart_offline.tar is in the same folder.
    pause
    exit /b %errorlevel%
)

docker load -i postgres_offline.tar
if %errorlevel% neq 0 (
    echo [ERROR] Failed to load postgres docker image from tar file!
    echo Ensure that postgres_offline.tar is in the same folder.
    pause
    exit /b %errorlevel%
)
echo.

echo 2. Starting the containers with Docker Compose...
docker-compose up -d
if %errorlevel% neq 0 (
    echo [ERROR] Failed to start docker-compose!
    pause
    exit /b %errorlevel%
)
echo.

echo ========================================================
echo [SUCCESS] Application has been started!
echo You can access it at http://localhost:8082
echo To check logs, run: docker logs csorgchart
echo ========================================================
pause
