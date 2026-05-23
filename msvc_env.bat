@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" > nul 2>&1
echo MSVC_INCLUDE=%INCLUDE%
echo MSVC_LIB=%LIB%
