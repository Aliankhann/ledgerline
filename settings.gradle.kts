rootProject.name = "ledgerline"

// Domain-neutral core, then the three bounded areas from the build plan, then the runnable service.
include("core", "plugins", "ledger", "calc", "ingest", "app", "bench")

// QE-owned test project. Dev agents must not edit anything under tests/ (enforced in CI).
include("tests")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
