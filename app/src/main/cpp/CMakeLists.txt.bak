cmake_minimum_required(VERSION 3.22.1)
project(s25u_native LANGUAGES C)

add_library(s25u_native SHARED native_probe.c)

# 全局Release体积优化参数，剥离符号、删除未使用代码
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -s -Os -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections")

# 编译警告，保留；优化等级替换为最小体积Os
target_compile_options(s25u_native PRIVATE
    -Wall -Wextra -Werror
    $<$<CONFIG:Release>:-Os>
    $<$<CONFIG:Debug>:-O2>
)

target_link_libraries(s25u_native PRIVATE log)
