# Makefile for District UI Components
# 
# Requirements:
#  - Leiningen
#  - npm, (preferably through nvm defined by .nvmrc)
#  - CircleCI CLI (Optional)
#  - docker (Optional)
#
# Quickstart Testing:
# - make clean-all deps test
#
# Quickstart Install
# - make install

.PHONY: deps
.PHONY: test test-headless test-circleci
.PHONY: install clean clean-all help

help:
	@echo "Makefile for District UI Components"
	@echo ""
	@echo "  help                   :: Show this help message"
	@echo "  deps                   :: Retrieve Library Dependencies (clojure, npm)"
	@echo "  --"
	@echo "  test                   :: Run doo test runner with chrome"
	@echo "  test-headless          :: Run doo test runner with chrome headless"
	@echo "  test-circleci          :: Run doo test runner using local CircleCI execution"
	@echo "  --"
	@echo "  install                :: Install component as a local maven dependency"
	@echo "  --"
	@echo "  clean                  :: Clean out build artifacts"
	@echo "  clean-all              :: Clean out build artifacts, and dev dependencies"
	@echo ""

deps:
	lein deps
	lein npm install

test:
	lein doo chrome once

test-headless:
	lein doo chrome-headless once

test-circleci:
	circleci local execute --job test

local-install:
	lein install

clean:
	lein clean

clean-all: clean
	rm -rf node_modules


# Check all of the required/optional executables
REQUIRED_EXECUTABLES = lein npm node
OPTIONAL_EXECUTABLES = docker circleci

CHK := $(foreach exec,$(REQUIRED_EXECUTABLES),\
	        $(if $(shell which $(exec)),,$(error "ERROR: Required Executable '$(exec)' not on your PATH")))

CHK := $(foreach exec,$(OPTIONAL_EXECUTABLES),\
	        $(if $(shell which $(exec)),,$(warning "WARNING: Optional Executable '$(exec)' not on your PATH")))


# Check to make sure the node version in .nvmrc matches the current node version
CURRENT_NVM_VERSION  := $(shell cat .nvmrc | cut -d"v" -f2)
CURRENT_NODE_VERSION := $(shell node --version | cut -d"v" -f2)

ifneq ($(CURRENT_NVM_VERSION), $(CURRENT_NODE_VERSION))
  $(warning WARNING: Wrong Node Version: $(CURRENT_NODE_VERSION))
  $(warning -        Expected Version:   $(CURRENT_NVM_VERSION))
  $(info Note: You can use nvm to set your node version with 'nvm use' while in this repository.)
endif
