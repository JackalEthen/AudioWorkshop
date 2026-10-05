cmake_minimum_required(VERSION 3.22.1)

set(ANDROID_STL c++_static)

project(qishui_mp3 C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(LAME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/lame" ABSOLUTE)
get_filename_component(RNNOISE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rnnoise" ABSOLUTE)
get_filename_component(STRETCH_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/signalsmith-stretch" ABSOLUTE)
get_filename_component(LINEAR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/signalsmith-linear" ABSOLUTE)
get_filename_component(EBUR128_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libebur128" ABSOLUTE)

file(GLOB RNNOISE_SOURCES "${RNNOISE_ROOT}/src/*.c")
# 这些是命令行小工具，不带 main 会重复符号；rnn_train.py 不是 C 源码
list(REMOVE_ITEM RNNOISE_SOURCES
    "${RNNOISE_ROOT}/src/dump_features.c"
    "${RNNOISE_ROOT}/src/dump_rnnoise_tables.c"
    "${RNNOISE_ROOT}/src/write_weights.c"
)

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
    jni_rnnoise.c
    ${RNNOISE_SOURCES}
    "${RNNOISE_ROOT}/src/rnnoise_data_little.c"
    jni_signalsmith.cpp
    "${EBUR128_ROOT}/ebur128/ebur128.c"
)

target_include_directories(mp3lame_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${LAME_ROOT}"
    "${LAME_ROOT}/include"
    "${LAME_ROOT}/libmp3lame"
    "${RNNOISE_ROOT}/include"
    "${RNNOISE_ROOT}/src"
    "${STRETCH_ROOT}"
    "${LINEAR_ROOT}/include"
    "${EBUR128_ROOT}/ebur128"
)

target_compile_definitions(mp3lame_jni PRIVATE HAVE_CONFIG_H FLOAT_APPROX)

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

  # __android_log_print 在 liblog 里
  find_library(log-lib log)
  target_link_libraries(mp3lame_jni PRIVATE ${log-lib})
