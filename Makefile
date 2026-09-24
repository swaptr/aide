# AIDE — dev entry points. Run `make` for the list.

GRADLE ?= ./gradlew
ADB    ?= adb
APP_ID ?= com.sabreware.aide
# The launcher component. namespace = com.sabreware.aide.app, applicationId = com.sabreware.aide, so the
# two halves differ — spelling it out beats a `.MainActivity` shorthand that resolves against the wrong one.
ACTIVITY ?= $(APP_ID)/com.sabreware.aide.app.MainActivity

# Bare `make` must never install or launch anything — it prints the menu.
.DEFAULT_GOAL := help

.PHONY: help android install launch uninstall logcat desktop server check test device-test server-test graph map clean api-dump docs

help: ## Show this help
	@printf '\nAIDE — make targets\n\n'
	@awk 'BEGIN { FS = ":.*## " } \
		/^[a-zA-Z0-9_-]+:.*## / { printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@printf '\nOverride: GRADLE=%s ADB=%s APP_ID=%s\n\n' '$(GRADLE)' '$(ADB)' '$(APP_ID)'

# Fails with a sentence instead of adb's silent no-op when nothing is plugged in.
define require-device
	@$(ADB) get-state >/dev/null 2>&1 || { \
		printf '\nNo device: plug one in (USB debugging on) or start an emulator.\n\n'; exit 1; }
endef

android: install launch ## Build, install and launch the Android app (debug)

install: ## Build + install the debug APK (no launch)
	$(call require-device)
	$(GRADLE) :app:installDebug

launch: ## Launch (or relaunch) the installed app
	$(call require-device)
	$(ADB) shell am force-stop $(APP_ID)
	$(ADB) shell am start -W -n $(ACTIVITY)

uninstall: ## Remove the app and all its data (Room uses destructive migration — this is the reset)
	$(call require-device)
	$(ADB) uninstall $(APP_ID)

logcat: ## Tail this app's logs only (Ctrl-C to stop)
	$(call require-device)
	@pid=$$($(ADB) shell pidof $(APP_ID) | tr -d '\r'); \
	if [ -z "$$pid" ]; then printf '\n$(APP_ID) is not running — `make launch` first.\n\n'; exit 1; fi; \
	$(ADB) logcat --pid=$$pid

desktop: ## Run the Compose Desktop app (blocks until the window closes)
	$(GRADLE) :desktopApp:run

server: ## Run the Spring Boot backend on :8080 (auto-starts Postgres from server/compose.yaml)
	$(GRADLE) :server:bootRun

# The task lists live in the root build (`aideCheck` / `aideTest`) so the Makefile and CI cannot drift apart
# — and so `aideTest` discovers a module's tests instead of waiting for someone to add it to a list here.
check: ## Typecheck every target and enforce the structural invariants (packages, module graph, Room schemas)
	$(GRADLE) aideCheck

test: ## Run every host test task in the build + the desktop Koin graph check
	$(GRADLE) aideTest

api-dump: ## Regenerate the aisdk .api dumps after a deliberate API change (then commit them)
	$(GRADLE) aisdkApiDump

docs: ## Render the aisdk API reference (Dokka HTML -> aisdk/build/dokka)
	$(GRADLE) :aisdk:dokkaGeneratePublicationHtml

device-test: ## Run the instrumented tests, incl. the Android Koin graph check (needs a device)
	$(call require-device)
	$(GRADLE) :app:connectedDebugAndroidTest

server-test: ## Run the backend tests (needs Docker — Testcontainers boots a real Postgres)
	$(GRADLE) :server:test

graph: ## Regenerate docs/module-graph.md from the actual module dependencies
	$(GRADLE) moduleGraph

map: ## Regenerate docs/code-map.md (which module holds which package)
	$(GRADLE) codeMap

clean: ## Delete build outputs
	$(GRADLE) clean
