cmake_minimum_required(VERSION 3.20)
project(TASWorkbench LANGUAGES CXX)

if(NOT DEFINED BAKKESMOD_SDK)
  message(FATAL_ERROR "Set BAKKESMOD_SDK to your BakkesMod SDK folder (the folder containing include/ and lib/).")
endif()

add_library(TASWorkbench SHARED
  src/TASWorkbench.cpp
)

target_compile_features(TASWorkbench PRIVATE cxx_std_17)
target_include_directories(TASWorkbench PRIVATE "${BAKKESMOD_SDK}/include")
target_link_directories(TASWorkbench PRIVATE "${BAKKESMOD_SDK}/lib")
target_link_libraries(TASWorkbench PRIVATE pluginsdk)
target_compile_definitions(TASWorkbench PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
set_target_properties(TASWorkbench PROPERTIES PREFIX "" OUTPUT_NAME "TASWorkbench")

if(MSVC)
  target_compile_options(TASWorkbench PRIVATE /W4 /EHsc /permissive-)
endif()
