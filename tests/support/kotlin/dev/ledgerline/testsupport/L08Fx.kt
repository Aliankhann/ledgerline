package dev.ledgerline.testsupport

import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.ValetResponse
import java.time.LocalDate

/** L-08 helpers: the five real Valet responses of `tests/fixtures/l08/` with the ranges their file names declare. */
object L08Fx {
    private val ranges =
        listOf(
            "2025-03-03" to "2025-03-14",
            "2025-04-15" to "2025-04-22",
            "2025-06-27" to "2025-07-03",
            "2025-10-08" to "2025-10-15",
            "2025-12-22" to "2026-01-05",
        )

    val responses: List<ValetResponse> =
        ranges.map { (s, e) ->
            ValetResponse(Fixtures.text("l08/FXUSDCAD_${s}_$e.json"), LocalDate.parse(s), LocalDate.parse(e))
        }

    fun cache(): FxRateCache = FxRateCache.fromValetJson(responses)

    /** Published dates (rows) of the fixture, read independently of the cache with a regex. */
    val publishedDates: Set<LocalDate> =
        responses
            .flatMap {
                Regex("\"d\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"").findAll(it.json).map {
                        m ->
                    m.groupValues[1]
                }
            }
            .map(LocalDate::parse)
            .toSet()
}
