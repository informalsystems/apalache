package at.forsyte.apalache.tla.bmcmt.passes

import java.nio.file.Path

import at.forsyte.apalache.infra.passes.{Pass, PassOptions}
import at.forsyte.apalache.tla.assignments.ModuleAdapter
import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.analyses.{ExprGradeStore, FormulaHintsStore}
import at.forsyte.apalache.tla.bmcmt.rewriter.RewriterConfig
import at.forsyte.apalache.tla.bmcmt.search._
import at.forsyte.apalache.tla.bmcmt.smt.{RecordingZ3SolverContext, SolverContext}
import at.forsyte.apalache.tla.bmcmt.types.eager.TrivialTypeFinder
import at.forsyte.apalache.tla.imp.src.SourceStore
import at.forsyte.apalache.tla.lir.NullEx
import at.forsyte.apalache.tla.lir.storage.ChangeListener
import at.forsyte.apalache.tla.lir.transformations.LanguageWatchdog
import at.forsyte.apalache.tla.lir.transformations.standard.KeraLanguagePred
import at.forsyte.apalache.tla.pp.NormalizedNames
import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.LazyLogging

/**
  * The implementation of a bounded model checker with SMT.
  *
  * @author Igor Konnov
  */
class BoundedCheckerPassImpl @Inject() (val options: PassOptions,
                                        hintsStore: FormulaHintsStore,
                                        exprGradeStore: ExprGradeStore,
                                        sourceStore: SourceStore,
                                        changeListener: ChangeListener,
                                        @Named("AfterChecker") nextPass: Pass)
      extends BoundedCheckerPass with LazyLogging {

  /**
    * The pass name.
    *
    * @return the name associated with the pass
    */
  override def name: String = "BoundedChecker"

  /**
    * Run the pass.
    *
    * @return true, if the pass was successful
    */
  override def execute(): Boolean = {
    if (tlaModule.isEmpty) {
      throw new CheckerException(s"The input of $name pass is not initialized", NullEx)
    }
    val module = tlaModule.get

    for (decl <- module.operDeclarations) {
      LanguageWatchdog(KeraLanguagePred()).check(decl.body)
    }

    val initTrans = ModuleAdapter.getTransitionsFromSpec(module, NormalizedNames.INIT_PREFIX)
    val nextTrans = ModuleAdapter.getTransitionsFromSpec(module, NormalizedNames.NEXT_PREFIX)
    val cinitP = ModuleAdapter.getOperatorOption(module, NormalizedNames.CONST_INIT)
    val vcInvs = ModuleAdapter.getTransitionsFromSpec(module, NormalizedNames.VC_INV_PREFIX)
    val vcNotInvs = ModuleAdapter.getTransitionsFromSpec(module, NormalizedNames.VC_NOT_INV_PREFIX)
    val invariantsAndNegations = vcInvs.zip(vcNotInvs)

    val input = new CheckerInput(module, initTrans.toList, nextTrans.toList, cinitP, invariantsAndNegations.toList)
    val nworkers = options.getOrElse("checker", "nworkers", 1)
    val stepsBound = options.getOrElse("checker", "length", 10)
    val tuning = options.getOrElse("general", "tuning", Map[String, String]())
    val debug = options.getOrElse("general", "debug", false)
    val saveDir = options.getOrError("io", "outdir").asInstanceOf[Path].toFile

    val sharedState = new SharedSearchState()
    val params = new ModelCheckerParams(input, stepsBound, saveDir, tuning, debug)

    def createCheckerThread(rank: Int): Thread = {
      new Thread {
        override def run(): Unit = {
          val checker = createModelChecker(rank, sharedState, params, input, tuning)
          val outcome = checker.run()
          logger.info("Worker %d: The outcome is %s".format(rank, outcome))
        }
      }
    }

    // run the threads and join
    val workerThreads = 1.to(nworkers) map createCheckerThread
    workerThreads.foreach(_.start())
    workerThreads.foreach(_.join())

    sharedState.workerStates.values.forall(_ == BugFreeState())
  }

  private def createModelChecker(rank: Int,
                                 sharedState: SharedSearchState,
                                 params: ModelCheckerParams,
                                 input: CheckerInput,
                                 tuning: Map[String, String]): ModelChecker = {
    val profile = options.getOrElse("smt", "prof", false)
    val solverContext: SolverContext = new RecordingZ3SolverContext(params.debug, profile)

    val typeFinder = new TrivialTypeFinder
    val rewriter: SymbStateRewriterImpl = new SymbStateRewriterImpl(solverContext, typeFinder, exprGradeStore)
    rewriter.formulaHintsStore = hintsStore
    rewriter.config = RewriterConfig(tuning)
    val context = new WorkerContext(rank, typeFinder, solverContext, rewriter, sharedState.activeNode)

    new ModelChecker(input, params, sharedState, context, changeListener, sourceStore)
  }

  /**
    * Get the next pass in the chain. What is the next pass is up
    * to the module configuration and the pass outcome.
    *
    * @return the next pass, if exists, or None otherwise
    */
  override def next(): Option[Pass] =
    tlaModule map {_ => nextPass}
}
