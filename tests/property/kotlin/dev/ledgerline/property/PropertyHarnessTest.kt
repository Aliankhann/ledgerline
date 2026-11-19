package dev.ledgerline.property

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Placeholder proving the property-test harness runs. The eight invariant suites
 * (balance, idempotency, immutability, reversal symmetry, replay determinism,
 * order independence, no negative shares, money precision) replace this from step 3.
 */
class PropertyHarnessTest : FunSpec({
    test("integer addition is commutative (harness check)") {
        checkAll(Arb.long(-1_000_000_000L..1_000_000_000L), Arb.long(-1_000_000_000L..1_000_000_000L)) { a, b ->
            a + b shouldBe b + a
        }
    }
})
