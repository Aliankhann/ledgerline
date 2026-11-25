package dev.ledgerline.testsupport

import dev.ledgerline.app.ProductionFx
import dev.ledgerline.calc.fx.FxRateCache

/** The FX cache the app itself uses ([ProductionFx]), so live pools built in-process match what `reconcile` replays. */
object AppFx {
    fun cache(): FxRateCache = ProductionFx.cache()
}
