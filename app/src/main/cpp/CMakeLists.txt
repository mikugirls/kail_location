cmake_minimum_required(VERSION 3.10)
project(kail_gait_sim C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

find_package(dobby CONFIG REQUIRED)

# Native hook library for system_server process (sensor simulation)
add_library(kail_native_hook SHARED
    native_hook/sensor_simulator.cpp
    native_hook/hook.cpp
)

target_include_directories(kail_native_hook PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_native_hook PRIVATE ${log-lib} dobby::dobby)

set_target_properties(kail_native_hook PROPERTIES
    OUTPUT_NAME "kail_native_hook"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# Root hook library - injected into remote processes via ptrace
add_library(kail_root_hook SHARED
    root_hook/entry.cpp
)

target_include_directories(kail_root_hook PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_root_hook PRIVATE ${log-lib} dl)

set_target_properties(kail_root_hook PROPERTIES
    OUTPUT_NAME "kail_root_hook"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# LHooker ART Method Hooking Engine (simplified)
add_library(kail_lhooker SHARED
    lhooker/lhooker.cpp
)

target_include_directories(kail_lhooker PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_lhooker PRIVATE ${log-lib} dl)

set_target_properties(kail_lhooker PROPERTIES
    OUTPUT_NAME "kail_lhooker"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)

# Ptrace injector executable - runs as root to inject libraries into remote processes
add_executable(kail_inject
    ptrace_inject/main.cpp
    ptrace_inject/ptrace_utils.cpp
)

target_include_directories(kail_inject PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_inject PRIVATE ${log-lib} dl)

set_target_properties(kail_inject PROPERTIES
    OUTPUT_NAME "kail_inject"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}"
)
