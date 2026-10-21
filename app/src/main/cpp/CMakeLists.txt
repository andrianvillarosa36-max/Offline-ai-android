cmake_minimum_required(VERSION 3.22.1)
project("offlineai" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always build native code optimized. Gradle debug builds default to no
# optimization, which makes model inference painfully slow.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

# Plain ARMv8-A baseline: runs on every 64-bit ARM phone.
# (A newer baseline with dot-product instructions crashed with "illegal
# instruction" on phones whose CPU does not have them.)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)

set(LLAMA_DIR ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_DIR}. Run: bash scripts/fetch_llama.sh")
endif()

add_subdirectory(${LLAMA_DIR} build-llama)

add_library(offlineai SHARED jni_bridge.cpp)

target_include_directories(offlineai PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

target_link_libraries(offlineai PRIVATE llama android log)
