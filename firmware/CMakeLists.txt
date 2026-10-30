# The ESP-IDF project file: like settings.gradle.kts, it names the project and pulls in
# ESP-IDF's build rules. Our own code is the "main" component in main/.
cmake_minimum_required(VERSION 3.22)
include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(piatra-board)
