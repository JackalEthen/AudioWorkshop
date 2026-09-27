cmake_minimum_required(VERSION 3.22.1)

project(qishui_mp3 C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(LAME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lame" ABSOLUTE)

add_library(mp3lame_jni SHARED
    jni_lame_mp3_encoder.c
    "${LAME_ROOT}/libmp3lame/VbrTag.c"
    "${LAME_ROOT}/libmp3lame/bitstream.c"
    "${LAME_ROOT}/libmp3lame/encoder.c"
    "${LAME_ROOT}/libmp3lame/fft.c"
    "${LAME_ROOT}/libmp3lame/gain_analysis.c"
    "${LAME_ROOT}/libmp3lame/id3tag.c"
    "${LAME_ROOT}/libmp3lame/lame.c"
    "${LAME_ROOT}/libmp3lame/newmdct.c"
    "${LAME_ROOT}/libmp3lame/presets.c"
    "${LAME_ROOT}/libmp3lame/psymodel.c"
    "${LAME_ROOT}/libmp3lame/quantize.c"
    "${LAME_ROOT}/libmp3lame/quantize_pvt.c"
    "${LAME_ROOT}/libmp3lame/reservoir.c"
    "${LAME_ROOT}/libmp3lame/set_get.c"
    "${LAME_ROOT}/libmp3lame/tables.c"
    "${LAME_ROOT}/libmp3lame/takehiro.c"
    "${LAME_ROOT}/libmp3lame/util.c"
    "${LAME_ROOT}/libmp3lame/vbrquantize.c"
    "${LAME_ROOT}/libmp3lame/version.c"
    "${LAME_ROOT}/libmp3lame/mpglib_interface.c"
)

target_include_directories(mp3lame_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${LAME_ROOT}"
    "${LAME_ROOT}/include"
    "${LAME_ROOT}/libmp3lame"
)

target_compile_definitions(mp3lame_jni PRIVATE HAVE_CONFIG_H)

target_compile_options(mp3lame_jni PRIVATE
    -O3
    -fno-strict-aliasing
    -ffast-math
    -Wno-implicit-fallthrough
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-sign-compare
    -Wno-deprecated-declarations
)

target_link_options(mp3lame_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--gc-sections"
)
