# Common commands. Everything here is also runnable directly; see CLAUDE.md.
.PHONY: help up down logs build test spec property chaos integration lint format ui-dev ui-build roles hooks clean reconcile mutation

help:            ## List targets
	@grep -E '^[a-z-]+:.*##' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-12s %s\n", $$1, $$2}'

up:              ## Start postgres, redpanda and the app (waits for health)
	docker compose up -d --build --wait

down:            ## Stop the stack and delete volumes
	docker compose down -v

logs:            ## Tail app logs
	docker compose logs -f app

build:           ## Compile, lint and run non-integration tests
	./gradlew build

test:            ## All non-integration tests
	./gradlew :tests:test

spec:            ## Story acceptance tests only
	./gradlew :tests:specTest

property:        ## Invariant property tests only
	./gradlew :tests:propertyTest

chaos:           ## Chaos scenarios (needs Docker)
	./gradlew :tests:chaosTest -PincludeIntegration

integration:     ## Every test, including Testcontainers (needs Docker)
	./gradlew :tests:test -PincludeIntegration

reconcile:       ## Replay the ledger and diff against calc.pool in the running stack
	docker compose exec -T app java -cp '/opt/ledgerline/lib/*' dev.ledgerline.app.MainKt reconcile

mutation:        ## Pitest mutation report for ledger/ and calc/ (report-only)
	./gradlew :tests:pitest

lint:            ## ktlint check
	./gradlew ktlintCheck

format:          ## ktlint auto-format
	./gradlew ktlintFormat

ui-dev:          ## Run the React UI dev server on :5173
	cd ui && npm install && npm run dev

ui-build:        ## Typecheck and build the UI
	cd ui && npm ci && npm run build

roles:           ## Run the role/test-ownership check against main
	scripts/check-test-ownership.sh origin/main HEAD

hooks:           ## Install the commit-msg hook that requires an Agent trailer
	git config core.hooksPath .githooks

clean:           ## Remove build outputs
	./gradlew clean && rm -rf ui/dist
