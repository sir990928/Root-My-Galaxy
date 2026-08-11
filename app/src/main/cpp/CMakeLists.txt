cmake_minimum_required(VERSION 3.22.1)
project(s25u_native LANGUAGES C)

add_library(s25u_native SHARED native_probe.c)

# 全部关闭调试信息，最小体积编译
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s -Os -g0 -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections,--strip-all")

# 警告严格校验，Release仅体积优先
target_compile_options(s25u_native PRIVATE
    -Wall -Wextra -Werror
    $<$<CONFIG:Release>:-Os -g0>
    $<$<CONFIG:Debug>:-O2 -g>
)

target_link_libraries(s25u_native PRIVATE log)
