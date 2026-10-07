cmake_minimum_required(VERSION 3.22.1)

add_library(terminal
             SHARED
             src/main/cpp/terminal.cpp)

target_link_options(terminal PRIVATE
             "-Wl,-z,max-page-size=16384")

target_link_libraries(terminal)
