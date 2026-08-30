cmake_minimum_required(VERSION 3.22.1)
project("xbridge" C)

add_library(xbridge SHARED xbridge.c)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(xbridge
        ${log-lib}
        ${android-lib}
        dl)
