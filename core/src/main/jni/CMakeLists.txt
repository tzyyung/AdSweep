cmake_minimum_required(VERSION 3.22.1)
project(adsweep)

find_package(lsplant REQUIRED CONFIG)
find_package(shadowhook REQUIRED CONFIG)

add_library(adsweep SHARED adsweep_jni.cpp)

target_link_libraries(adsweep
    lsplant::lsplant
    shadowhook::shadowhook
    log
)
