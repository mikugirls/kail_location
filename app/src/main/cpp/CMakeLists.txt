cmake_minimum_required(VERSION 3.10)
project(kail_gait_sim C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

find_package(dobby CONFIG REQUIRED)

# ============================================================
# Native hook library for system_server (sensor simulation)
# ============================================================
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

# ============================================================
# Ptrace injector (64-bit) - C++ source with .c extension
# ============================================================
add_executable(kail_inject
    fakelocation/inject64.c
)

set_source_files_properties(fakelocation/inject64.c PROPERTIES LANGUAGE CXX)

target_include_directories(kail_inject PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kail_inject PRIVATE ${log-lib} dl)

set_target_properties(kail_inject PROPERTIES
    OUTPUT_NAME "kail_inject"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_RUNTIME_OUTPUT_DIRECTORY}"
)

# ============================================================
# fakelocation native targets (disable for Phase 1 MVP)
#
# These source files contain decompilation artifacts that
# require targeted cleanup before they can compile with
# modern NDK. Enable individually after fixing:
#
#   kail_root_hook      - fakelocation/libfakeloc_init.c
#   kail_lhooker         - fakelocation/liblhooker.c
#   kail_inject_32       - fakelocation/inject.c
#   kail_antidetect      - fakelocation/libantidetect.c
#   kail_fakeloc_apphook - fakelocation/libfakeloc_apphook.c
#   kail_fakeloc_initzygote - fakelocation/libfakeloc_initzygote.c
#   kail_step_sensor     - fakelocation/libStepSensor.c
# ============================================================
