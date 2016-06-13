#!/usr/bin/env bash

CDAS_BIN_DIR = "$( dirname "${BASH_SOURCE[0]}" )"
CDAS_MAIN_DIR=${CDAS_BIN_DIR}/../src/main
CDAS_SCALA_DIR=${CDAS_MAIN_DIR}/scala
CDAS_RESOURCES_DIR=${CDAS_MAIN_DIR}/resources
export PATH=${CDAS_BIN_DIR}:${PATH}
export CLASSPATH=${CDAS_SCALA_DIR}:${CDAS_RESOURCES_DIR}:${CLASSPATH}