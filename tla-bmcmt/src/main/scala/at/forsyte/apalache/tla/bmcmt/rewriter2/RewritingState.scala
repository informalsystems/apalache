package at.forsyte.apalache.tla.bmcmt.rewriter2

import at.forsyte.apalache.tla.bmcmt.smt.{SolverConfig, Z3SolverContext}
import at.forsyte.apalache.tla.bmcmt.{Arena, Binding}
import at.forsyte.apalache.tla.lir.smt.SmtTools.BoolFormula

/**
 * The internal state of the term rewriting system.
 *
 * Each state consists of three components:
 *   1) An Arena, which keeps track of existing cells and their relations
 *   2) A Binding, which keeps track of (local) variable assignments
 *   3) A collection of constraints, which will eventually be passed to an SMT solver
 *      once rewriting terminates
 *
 * Notably, TlaEx expressions are NOT part of the internal state
 */
sealed case class RewritingState(arena: Arena, binding: Binding, constraints: Seq[BoolFormula])

object RewritingState {
  def init: RewritingState =
    RewritingState(
        Arena.create(new Z3SolverContext(SolverConfig.default)),
        Binding(),
        Seq.empty
    )
}
