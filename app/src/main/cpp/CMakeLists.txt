cmake_minimum_required(VERSION 3.22.1)
project("nanoai" C CXX)

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Optimization flags for release (no -ffast-math due to llama.cpp requirements)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -funroll-loops")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -funroll-loops")

# Android-specific settings
if(ANDROID)
    # Enable NEON for ARM
    if(${ANDROID_ABI} STREQUAL "arm64-v8a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd")
        add_definitions(-DGGML_USE_NEON=1)
    elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon-vfpv4 -mfloat-abi=softfp")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon-vfpv4 -mfloat-abi=softfp")
        add_definitions(-DGGML_USE_NEON=1)
    endif()
endif()

# Llama.cpp configuration - disable GPU backends for Android
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_LTO OFF CACHE BOOL "" FORCE)
set(LLAMA_CUDA OFF CACHE BOOL "" FORCE)
set(LLAMA_METAL OFF CACHE BOOL "" FORCE)
set(LLAMA_VULKAN OFF CACHE BOOL "" FORCE)
set(LLAMA_HIPBLAS OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENCL OFF CACHE BOOL "" FORCE)
set(LLAMA_SYCL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# GGML options
set(GGML_STATIC ON CACHE BOOL "" FORCE)
set(GGML_LTO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Memory mapping for efficient model loading
add_definitions(-DGGML_USE_MMAP=1)

# Check if llama.cpp exists as submodule
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(STATUS "Using llama.cpp from submodule")
    add_subdirectory(llama.cpp)
    set(LLAMA_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include")
    set(LLAMA_LIB llama)
    set(GGML_LIB ggml)
else()
    # Fallback: expect llama.cpp sources directly in cpp folder
    message(STATUS "Building llama.cpp from source files")

    # GGML source files
    set(GGML_SOURCES
        ggml/src/ggml.c
        ggml/src/ggml-alloc.c
        ggml/src/ggml-backend.c
        ggml/src/ggml-quants.c
        ggml/src/ggml-aarch64.c
    )

    # Llama source files
    set(LLAMA_SOURCES
        llama/src/llama.cpp
        llama/src/llama-vocab.cpp
        llama/src/llama-grammar.cpp
        llama/src/llama-sampling.cpp
        llama/src/unicode.cpp
        llama/src/unicode-data.cpp
    )

    # Check for common include
    if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/common/common.cpp")
        list(APPEND LLAMA_SOURCES
            common/common.cpp
            common/sampling.cpp
            common/grammar-parser.cpp
        )
        set(HAS_COMMON ON)
    endif()

    # GGML library
    add_library(ggml STATIC ${GGML_SOURCES})
    target_include_directories(ggml PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
        ${CMAKE_CURRENT_SOURCE_DIR}/ggml/src
    )

    # Llama library
    add_library(llama STATIC ${LLAMA_SOURCES})
    target_include_directories(llama PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/llama/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama/src
        ${CMAKE_CURRENT_SOURCE_DIR}/ggml/include
    )
    if(HAS_COMMON)
        target_include_directories(llama PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/common)
    endif()
    target_link_libraries(llama PUBLIC ggml)

    set(LLAMA_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama/include")
    set(LLAMA_LIB llama)
    set(GGML_LIB ggml)
endif()

# JNI Bridge library
add_library(nanoai_jni SHARED
    llama_jni.cpp
)

# Include directories
target_include_directories(nanoai_jni PRIVATE
    ${LLAMA_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(nanoai_jni
    ${LLAMA_LIB}
    ${GGML_LIB}
    android
    log
    z
)

# Additional optimizations for Android
if(ANDROID)
    target_link_libraries(nanoai_jni
        ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c
    )
    target_include_directories(nanoai_jni PRIVATE
        ${ANDROID_NDK}/sources/android/cpufeatures
    )
endif()

# Strip debug symbols in release for smaller APK
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(nanoai_jni PROPERTIES
        LINK_FLAGS "-s"
    )
endif()
