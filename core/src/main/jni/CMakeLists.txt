cmake_minimum_required(VERSION 3.22.1)
project(adsweep)

find_package(lsplant REQUIRED CONFIG)

add_library(adsweep SHARED adsweep_jni.cpp)

target_link_libraries(adsweep
    lsplant::lsplant
    log
)

target_compile_options(adsweep PRIVATE -Wall -Wextra)
