package at.forsyte.apalache.tla.bmcmt.rewriter2

import at.forsyte.apalache.tla.lir.TlaEx

/**
 * A Rewriter using the new computation framework.
 */
trait Rewriter {
  def rewriteUntilDone(ex: TlaEx): StateCompWithExceptions[TlaEx]
}
