package at.forsyte.apalache.tla.bmcmt.rewriter2

import at.forsyte.apalache.tla.lir.TlaEx

/**
 * A rule in the new rewriting system. It can be tested, or applied. In the latter case, it either returns
 * a TlaEx (rewritten expression) or throws a RewritingException (represented by computation)
 */
trait Rule {
  def isApplicable(ex: TlaEx): Boolean

  def apply(ex: TlaEx): StateCompWithExceptions[TlaEx]

}
