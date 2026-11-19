package dev.ledgerline.testsupport

import io.kotest.core.Tag

/** Needs Docker (Testcontainers). Excluded unless Gradle is run with -PincludeIntegration. */
object Integration : Tag()
