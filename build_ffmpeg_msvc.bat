@echo off
REM 设置 MSVC 环境（使 cl.exe 和 link.exe 可用）
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"

REM 进入 FFmpeg 构建目录
cd /d "E:\beatoraja-Android\libgdx-oboe\library"

REM 设置 NDK 路径
set "NDK_DIR=E:\Android-SDK\ndk\29.0.13599879"

REM 用 bash 运行 build script，指定 cl.exe 作为 host 编译器
bash -c "cd 'E:/beatoraja-Android/libgdx-oboe/library' && NDK_DIR='E:/Android-SDK/ndk/29.0.13599879' HOSTCC=cl ./build_ffmpeg.sh --ffmpeg-only"
