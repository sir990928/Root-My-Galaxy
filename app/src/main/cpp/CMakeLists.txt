cmake_minimum_required(VERSION 3.22.1)
project(s25u_native LANGUAGES C)

add_library(s25u_native SHARED native_probe.c)

set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s -g0 -Os -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections,--strip-all")

target_compile_options(s25u_native PRIVATE
    -Wall -Wextra -Werror
    $<$<CONFIG:Release>:-Os -g0>
    $<$<CONFIG:Debug>:-O2 -g>
)

target_link_libraries(s25u_native PRIVATE log)
