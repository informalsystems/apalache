package at.forsyte.apalache.tla.bmcmt.trex

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.rewriter.Recoverable
import at.forsyte.apalache.tla.bmcmt.rules.aux.CherryPick
import at.forsyte.apalache.tla.lir.TlaEx
import com.typesafe.scalalogging.LazyLogging

/**
  * <p>A general-purpose symbolic transition executor (or T-Rex).
  * It accumulates the basic logic for executing TLA+
  * transitions, which is used both in the sequential and parallel model checkers.
  * (It could be used to implement predicate abstraction too.)
  * This class is imperative, as taking SMT snapshots and recovering them
  * in the non-incremental case is far from being functional.</p>
  *
  * <p>This class is parameterized by the snapshot type. Typically, they are </p>
  *
  * @author Igor Konnov
  */
class TransitionExecutor[ExecCtxT](consts: Set[String], vars: Set[String], ctx: ExecutorContext[ExecCtxT])
  extends Recoverable[ExecutorSnapshot[ExecCtxT]] with LazyLogging {

  /**
    * When debug is true, the executor runs additional consistency checks.
    */
  var debug: Boolean = false
  // the current step number
  private var _stepNo: Int = 0

  // the control state of the executor
  private var controlState: ExecutorControlState = Preparing()

  private val initialArena = Arena.create(ctx.rewriter.solverContext)
  // the latest symbolic state that is produced by the rewriter
  var topState = new SymbState(initialArena.cellTrue().toNameEx, initialArena, Binding())
  // the stack of symbolic states, reversed, except topState
  private var revStack: List[SymbState] = List()

  // the transitions that are translated with prepareTransition in the current step
  private var preparedTransitions: Map[Int, SymbState] = Map()

  /**
    * The step that is currently encoded.
    */
  def stepNo: Int = _stepNo

  /**
    * Translate a transition into SMT and save it under the given number. This method returns false,
    * if the transition was found to be disabled during the translation. In this case, the translation result
    * is still saved in the SMT context. It is user's responsibility to pop the context, e.g., by recovering from
    * a snapshot. (In an incremental solver, it is cheap; in an offline solver, this may slow down the checker.)
    *
    * @param transitionNo a number associated with the transition, must be unique for the current step
    * @param transitionEx the expression that encodes the transition, it must be a TLA+ action expression
    * @return true, if the transition has been successfully translated;
    *         false, if the translation has found that the transition is disabled
    */
  def prepareTransition(transitionNo: Int, transitionEx: TlaEx): Boolean = {
    assert(controlState == Preparing())
    if (preparedTransitions.contains(transitionNo)) {
      throw new IllegalStateException(s"prepareTransition is called for $transitionNo two times")
    }
    logger.debug("Step #%d, transition #%d, SMT context level %d"
      .format(stepNo, transitionNo, ctx.rewriter.contextLevel))
    inferTypes(transitionEx)
    ctx.rewriter.solverContext.log("; ------- STEP: %d, STACK LEVEL: %d TRANSITION: %d {"
      .format(stepNo, ctx.rewriter.contextLevel, transitionNo))
    logger.debug("Translating to SMT...")
    val erased = topState.setBinding(topState.binding.forgetPrimed) // forget the previous assignments
    ctx.rewriter.exprCache.disposeActionLevel() // forget the previous action caches
    // translate the transition to SMT
    topState = ctx.rewriter.rewriteUntilDone(erased.setRex(transitionEx))
    ctx.rewriter.flushStatistics()
    // the transition can be accessed
    preparedTransitions += transitionNo -> topState

    if (debug) {
      // This is a debugging feature that allows us to find incorrect rewriting rules. Disable it in production.
      logger.debug("Checking consistency of the transition constraints")
      if (ctx.solver.sat()) {
        logger.debug("The transition constraints are OK")
      } else {
        // this is a clear sign of a bug in one of the translation rules
        logger.debug("Transition constraints are inconsistent")
        throw new CheckerException("A contradiction introduced in rewriting. Report a bug.", topState.ex)
      }
    }

    // check, whether all variables have been assigned
    val assignedVars = topState.binding.toMap.
      collect { case (name, _) if name.endsWith("'") => name.substring(0, name.length - 1) }
    if (assignedVars.toSet == vars) {
      true // the transition is probably enabled
    } else {
      logger.debug(s"Transition $transitionNo produces partial assignment. It is disabled.")
      false // the transition is disabled
    }
  }

  /**
    * Assume that a previously prepared transition fires. Use this method to check,
    * whether a prepared transition is enabled.
    * This method should be called after prepareTransition.
    *
    * @param transitionNo the number of a previously prepared transition
    */
  def assumeTransition(transitionNo: Int): Unit = {
    assert(controlState == Preparing())
    // assert that the concerned transition has been prepared
    if (!preparedTransitions.contains(transitionNo)) {
      throw new IllegalStateException(s"Use prepareTransition before calling assumeTransition for $transitionNo")
    } else {
      ctx.rewriter.solverContext.assertGroundExpr(preparedTransitions(transitionNo).ex)
    }

    controlState = Picked()
  }

  /**
    * Push an assertion about the current controlState.
    *
    * @param assertion a Boolean-valued TLA+ expression, usually a controlState expression,
    *                  though it may be an action expression.
    */
  def assertState(assertion: TlaEx): Unit = {
    val nextState = ctx.rewriter.rewriteUntilDone(topState.setRex(assertion))
    ctx.rewriter.solverContext.assertGroundExpr(nextState.ex)
    topState = nextState.setRex(topState.ex) // propagate the arena and binding, but keep the old expression
  }

  /**
    * Pick non-deterministically one transition among the transitions that are prepared
    * in the current step. Further, assume that the picked transition has fired.
    * This method must be called after at least one call to prepareTransition.
    *
    * TODO: save an object that allows us to check, which transition has actually fired.
    */
  def pickTransition(): Unit = {
    assert(controlState == Preparing())
    // assert that there is at least one prepared transition
    logger.info("Step %d, level %d: picking a transition out of %d transition(s)"
      .format(stepNo, ctx.rewriter.contextLevel, preparedTransitions.size))
    assert(preparedTransitions.nonEmpty)
    val sortedTransitions = preparedTransitions.toSeq.sortBy(_._1)

    // pick an index j \in { 0..k } of the fired transition
    // TODO: introduce a reverse mapping from oracle values to transition numbers
    val picker = new CherryPick(ctx.rewriter)
    val (oracleState, oracle) = picker.oracleFactory.newDefaultOracle(topState, sortedTransitions.length)

    if (sortedTransitions.isEmpty) {
      throw new IllegalArgumentException("unable to pick transitions from empty set")
    } else if (sortedTransitions.lengthCompare(1) == 0) {
      ctx.solver.assertGroundExpr(topState.ex)
      topState = oracleState
    } else {
      // if oracle = i, then the ith transition is enabled
      ctx.solver.assertGroundExpr(oracle.caseAssertions(oracleState, sortedTransitions.map(_._2.ex)))

      // glue the computed states S_0, ..., S_k together:
      // for every variable x', pick c_x from { S_1[x'], ..., S_k[x'] }
      //   and require \A i \in { 0.. k-1}. j = i => c_x = S_i[x']
      // Then, the final controlState binds x' -> c_x for every x' \in Vars'
      var nextState = oracleState

      def pickVar(x: String): ArenaCell = {
        val toPickFrom = sortedTransitions map (p => p._2.binding(x))
        nextState = picker.pickByOracle(nextState,
          oracle, toPickFrom, nextState.arena.cellFalse().toNameEx) // no else case
        nextState.asCell
      }

      def getAssignedVars(st: SymbState) = st.binding.forgetNonPrimed(Set()).toMap.keySet
      val primedVars = getAssignedVars(sortedTransitions.head._2) // only VARIABLES, not CONSTANTS

      val finalVarBinding = Binding(primedVars.toSeq map (n => (n, pickVar(n))): _*) // variables only
      val constBinding = Binding(oracleState.binding.toMap.filter(p => consts.contains(p._1)))
      topState = nextState.setBinding(finalVarBinding ++ constBinding)
      if (debug && !ctx.solver.sat()) {
        throw new InternalCheckerError(s"Error picking next variables (step $stepNo). Report a bug.", topState.ex)
      }
    }

    controlState = Picked()
  }

  /**
    * Advance symbolic execution by renaming primed variables to non-primed.
    * This method must be called after pickTransition.
    */
  def nextState(): Unit = {
    assert(controlState == Picked())
    val erased = topState.setBinding(topState.binding.forgetPrimed) // forget the previous assignments
    revStack = erased :: revStack
    // finally, shift the primed variables to non-primed, forget the expression
    topState = topState
      .setBinding(topState.binding.shiftBinding(consts))
      .setRex(topState.arena.cellTrue().toNameEx)
    // that is the result of this step
    shiftTypes(consts)
    // clean the prepared transitions
    preparedTransitions = Map()
    // increase the step number
    _stepNo += 1
    controlState = Preparing()
  }

  /**
    * Check, whether the current context of the symbolic execution is satisfiable.
    *
    * @param timeoutSec timeout in seconds
    * @return Some(true), if the context is satisfiable;
    *         Some(false), if the context is unsatisfiable;
    *         None, if the solver timed out or reported *unknown*.
    */
  def sat(timeoutSec: Long): Option[Boolean] = {
    ctx.rewriter.solverContext.satOrTimeout(timeoutSec)
  }

  /**
    * Create a snapshot of the current symbolic execution. The user should not access
    * the snapshot, which is an opaque object.
    *
    * @return a snapshot
    */
  def snapshot(): ExecutorSnapshot[ExecCtxT] = {
    val stack = (topState +: revStack).reverse
    new ExecutorSnapshot[ExecCtxT](controlState, stack, preparedTransitions, ctx.snapshot())
  }

  /**
    * Recover a controlState of symbolic execution from a snapshot.
    *
    * @param snapshot a snapshot that was created by an earlier call to snapshot.
    */
  def recover(snapshot: ExecutorSnapshot[ExecCtxT]): Unit = {
    ctx.recover(snapshot.ctxSnapshot)
    val rs = snapshot.stack.reverse
    topState = rs.head
    topState.updateArena(_.setSolver(ctx.solver))
    revStack = rs.tail
    preparedTransitions = snapshot.preparedTransitions
    controlState = snapshot.controlState
  }

  // infer the types and throw an exception if type inference has failed
  private def inferTypes(expr: TlaEx): Unit = {
    logger.debug("Inferring types...")
    ctx.typeFinder.inferAndSave(expr)
    if (ctx.typeFinder.typeErrors.nonEmpty) {
      throw new TypeInferenceException(ctx.typeFinder.typeErrors)
    }
  }

  /**
    * Remove the non-primed variables (except provided constants)
    * and rename the primed variables to their non-primed versions.
    * After that, remove the type finder to contain the new types only.
    */
  private def shiftTypes(constants: Set[String]): Unit = {
    val types = ctx.typeFinder.varTypes
    val nextTypes =
      types.filter(p => p._1.endsWith("'") || constants.contains(p._1))
        .map(p => (p._1.stripSuffix("'"), p._2))
    ctx.typeFinder.reset(nextTypes)
  }
}
