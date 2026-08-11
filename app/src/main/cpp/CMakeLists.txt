cmake_minimum_required(VERSION 3.22.1)
project(s25u_native LANGUAGES C)

add_library(s25u_native SHARED native_probe.c)

# Release 编译：精简符号、最小体积、剔除未使用函数/全局变量
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s -Os -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections")

# 条件优化等级：Release最小体积Os，Debug保留O2方便调试；保留警告强校验
target_compile_options(s25u_native PRIVATE
    -Wall -Wextra -Werror
    $<$<CONFIG:Release>:-Os>
    $<$<CONFIG:Debug>:-O2>
)

target_link_libraries(s25u_native PRIVATE log)
