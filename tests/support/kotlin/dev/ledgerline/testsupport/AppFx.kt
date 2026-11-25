package dev.ledgerline.testsupport

import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.ValetResponse
import java.time.LocalDate

/**
 * The FX cache the app itself loads (the committed Bank of Canada file in `app/src/main/resources/fx/`, read from the
 * classpath exactly as `Main.loadFxRates` does), so live pools built in-process match what `reconcile` replays.
 */
object AppFx {
    private const val NAME = "/fx/FXUSDCAD_2023-01-01_2025-12-31.json"

    fun cache(): FxRateCache {
        val json =
            requireNotNull(AppFx::class.java.getResourceAsStream(NAME)) { "Missing $NAME" }
                .use { String(it.readBytes(), Charsets.UTF_8) }
        val start = LocalDate.of(2023, 1, 1)
        val end = LocalDate.of(2025, 12, 31)
        return FxRateCache.fromValetJson(listOf(ValetResponse(json, start, end)))
    }
}
