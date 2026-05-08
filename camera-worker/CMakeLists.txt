cmake_minimum_required(VERSION 3.16)
project(camera_worker C)

set(CMAKE_C_STANDARD 11)

find_package(PkgConfig QUIET)
find_package(Python3 REQUIRED COMPONENTS Interpreter Development)

execute_process(
        COMMAND "${Python3_EXECUTABLE}" -c "import numpy; print(numpy.get_include())"
        OUTPUT_VARIABLE NUMPY_INCLUDE_DIR
        OUTPUT_STRIP_TRAILING_WHITESPACE
)

add_executable(camera_worker
        src/main.c
        src/python_embed.c
)

target_include_directories(camera_worker PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/deps"
        "${NUMPY_INCLUDE_DIR}"
)

target_link_libraries(camera_worker PRIVATE Python3::Python)

if (PkgConfig_FOUND)
    pkg_check_modules(ARAVIS QUIET arv-0.8)
    if (ARAVIS_FOUND)
        target_compile_definitions(camera_worker PRIVATE HAVE_ARAVIS=1)
        target_include_directories(camera_worker PRIVATE ${ARAVIS_INCLUDE_DIRS})
        target_link_libraries(camera_worker PRIVATE ${ARAVIS_LIBRARIES})
        target_link_directories(camera_worker PRIVATE ${ARAVIS_LIBRARY_DIRS})
    endif ()
endif ()

if (MSVC)
    target_compile_definitions(camera_worker PRIVATE _CRT_SECURE_NO_WARNINGS)
endif ()
