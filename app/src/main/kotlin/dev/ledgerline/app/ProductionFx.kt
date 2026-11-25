package dev.ledgerline.app

import dev.ledgerline.calc.fx.FxRateCache
import dev.ledgerline.calc.fx.ValetResponse
import java.time.LocalDate

/** The single production FX source: the committed Bank of Canada file, loaded once (ADR 0008: never fetched live). */
object ProductionFx {
    private const val RESOURCE = "/fx/FXUSDCAD_2023-01-01_2025-12-31.json"
    private val cache: FxRateCache by lazy {
        val json =
            requireNotNull(ProductionFx::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" }
                .use { String(it.readBytes(), Charsets.UTF_8) }
        FxRateCache.fromValetJson(listOf(ValetResponse(json, LocalDate.of(2023, 1, 1), LocalDate.of(2025, 12, 31))))
    }

    fun cache(): FxRateCache = cache
}
