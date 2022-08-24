package at.forsyte.apalache.tla.pp.passes

import at.forsyte.apalache.infra.passes.Pass.PassResult
import at.forsyte.apalache.io.ConfigurationError
import at.forsyte.apalache.io.lir.TlaWriterFactory
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.{TlaActionOper, TlaBoolOper, TlaOper, TlaTempOper}
import at.forsyte.apalache.tla.lir.transformations.standard.NonrecursiveLanguagePred
import at.forsyte.apalache.tla.lir.transformations.{LanguageWatchdog, TransformationTracker}
import at.forsyte.apalache.tla.pp._
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging

import java.io.{File, IOException}
import at.forsyte.apalache.infra.passes.DerivedPredicates
import at.forsyte.apalache.infra.passes.options.OptionGroup
import at.forsyte.apalache.infra.tlc.config.TlcConfig
import at.forsyte.apalache.infra.tlc.config.InitNextSpec
import at.forsyte.apalache.infra.tlc.config.TemporalSpec
import at.forsyte.apalache.infra.tlc.config.NullSpec
import at.forsyte.apalache.infra.tlc.config.TlcConfigParseError

/**
 * The pass that collects the configuration parameters and overrides constants and definitions. This pass also overrides
 * attributes in the PassOptions object: checker.init, checker.next, checker.cinit, checker.inv. In general, passes
 * should not override options. This is a reasonable exception to this rule, as this pass configures the options based
 * on the user input.
 *
 * @param options
 *   pass options
 * @param nextPass
 *   next pass to call
 */
class ConfigurationPassImpl @Inject() (
    val options: OptionGroup.HasChecker,
    val derivedPreds: DerivedPredicates,
    tracker: TransformationTracker,
    writerFactory: TlaWriterFactory)
    extends ConfigurationPass with LazyLogging {

  override def name: String = "ConfigurationPass"

  override def execute(tlaModule: TlaModule): PassResult = {
    // Since this is the 1st pass after Inline, check absence of recursion first
    LanguageWatchdog(NonrecursiveLanguagePred).check(tlaModule)

    // try to read from the TLC configuration file and produce constant overrides
    val overrides = options.checker.predicates.tlcConfig match {
      case None                             => setDerivedPredicates(); List.empty // No overrides
      case Some((tlcConfig, tlcConfigFile)) => loadOptionsFromTlcConfig(tlaModule, tlcConfig, tlcConfigFile)
    }

    val constOverrideNames = tlaModule.constDeclarations.map(_.name).map(ConstAndDefRewriter.OVERRIDE_PREFIX + _).toSet
    val (constOverrides, otherOverrides) = overrides.partition(d => constOverrideNames.contains(d.name))

    val newDecls =
      if (constOverrides.nonEmpty) {
        // If there are constant overrides, we need a CInit predicate, so either
        // fetch the configured one or use the default operator name "CInit"
        derivedPreds.cinit = options.checker.cinit.orElse(Some("CInit"))
        extendCinitWithOverrides(constOverrides, tlaModule, derivedPreds.cinit.get)
      } else {
        tlaModule.declarations
      }

    val currentAndOverrides = TlaModule(tlaModule.name, newDecls ++ otherOverrides)

    // make sure that the required operators are defined
    ensureDeclarationsArePresent(currentAndOverrides)

    // rewrite constants and declarations
    val configuredModule = new ConstAndDefRewriter(tracker)(currentAndOverrides)
    // Igor: I thought of rewriting all definitions in NormalizedNames.OPTION_NAMES into normalized names.
    // However, that should be done very carefully. Maybe we should do that in the future.

    // dump the configuration result
    writeOut(writerFactory, configuredModule)

    Right(configuredModule)
  }

  // Returns new declarations, where CInit has been extended (or constructed)
  // with OVERRIDEs relating to specification constants
  private def extendCinitWithOverrides(
      constOverrides: Seq[TlaDecl],
      tlaModule: TlaModule,
      cinitName: String): Seq[TlaDecl] = {
    val oldCinitOpt = tlaModule.operDeclarations
      .find {
        _.name == derivedPreds.cinit
      }

    val boolTag = Typed(BoolT1)
    val overridesAsEql = constOverrides.collect { case TlaOperDecl(name, _, body) =>
      val varName = name.drop(ConstAndDefRewriter.OVERRIDE_PREFIX.length)
      OperEx(TlaOper.eq, NameEx(varName)(body.typeTag), body)(boolTag)
    }

    val newCinitDecl = oldCinitOpt
      .map { d =>
        d.copy(body = OperEx(TlaBoolOper.and, d.body +: overridesAsEql: _*)(boolTag))
      }
      .getOrElse {
        TlaOperDecl(cinitName, List.empty, OperEx(TlaBoolOper.and, overridesAsEql: _*)(boolTag))(Typed(OperT1(Seq.empty,
                    BoolT1)))
      }

    // Since declarations are a Seq not a Set, we may need to remove the old CInit first
    tlaModule.declarations.filterNot(_.name == derivedPreds.cinit) :+ newCinitDecl
  }

  private def setDerivedPredicates(): Unit = {
    options.checker.predicates.behaviorSpec match {
      case InitNextSpec(next, init) => derivedPreds.next = next; derivedPreds.init = init
      // Should we change data structure to make this state unrepresentable?
      case _ => throw new Exception("TODO: should be impossible, since this arises only with no TLCC config")
    }
    derivedPreds.temporal = options.checker.predicates.temporal
    derivedPreds.invariants = options.checker.predicates.invariants
    derivedPreds.cinit = options.checker.cinit
  }

  /**
   * Extract the data from a TlaModule needed to finalize the configuration options provided in a specified TlcConfig
   *
   * @param module
   *   the input module
   * @param config
   *   the TlcConfig parsed during program configuration
   * @param configFile
   *   the name of the file from which the TlcConfig was loaded
   *
   * @return
   *   additional declarations, which originate from assignments and replacements
   */
  private def loadOptionsFromTlcConfig(module: TlaModule, config: TlcConfig, configFile: File): Seq[TlaDecl] = {
    val basename = configFile.getName()
    var configuredModule = module

    try {
      configuredModule = new TlcConfigImporter(config)(module)
      val (init, next) = options.checker.predicates.behaviorSpec match {
        case InitNextSpec(init, next) => (init, next)

        case TemporalSpec(specName) =>
          logger.info(s"  > $basename: Using SPECIFICATION $specName")
          extractFromSpec(module, basename, specName)

        case NullSpec() => ("Init", "Next")
      }

      derivedPreds.init = init
      derivedPreds.next = next
      derivedPreds.invariants = options.checker.predicates.invariants // TODO rm?
      derivedPreds.temporal = options.checker.predicates.temporal // TODO rm?

      if (derivedPreds.invariants.nonEmpty) {
        logger.info(s"""  > $basename: found INVARIANTS: ${derivedPreds.invariants.mkString(", ")}""")
      }
      if (derivedPreds.temporal.nonEmpty) {
        logger.info(s"""  > $basename: found PROPERTIES: ${derivedPreds.temporal.mkString(", ")}""")
      }

      val namesOfOverrides =
        (config.constAssignments.keySet ++ config.constReplacements.keySet)
          .map(ConstAndDefRewriter.OVERRIDE_PREFIX + _)
      configuredModule.declarations.filter(d => namesOfOverrides.contains(d.name))
    } catch {
      // TODO Move into config parsing section
      case e: IOException =>
        val msg = s"  > $basename: IO error when loading the TLC config: ${e.getMessage}"
        throw new TLCConfigurationError(msg)

      case e: TlcConfigParseError =>
        val msg = s"  > $basename:${e.pos}:  Error parsing the TLC config file: ${e.msg}"
        throw new TLCConfigurationError(msg)
    }
  }

  // Make sure that all operators passed via --init, --cinit, --next, --inv, --temporal are present in the module.
  private def ensureDeclarationsArePresent(mod: TlaModule): Unit = {
    def assertDecl(role: String, name: String): Unit = {
      logger.info(s"  > Set $role to $name")
      if (mod.operDeclarations.forall(_.name != name)) {
        throw new ConfigurationError(
            s"Operator $name not found (used as $role)"
        )
      }
    }

    // Required predicates
    assertDecl("the initialization predicate", derivedPreds.init)
    assertDecl("the transition predicate", derivedPreds.next)
    // Optional predicates
    derivedPreds.cinit.foreach(assertDecl("the constant initialization predicate", _))
    derivedPreds.invariants.foreach(assertDecl("an invariant", _))
    derivedPreds.temporal.foreach(assertDecl("a temporal property", _))
  }

  /**
   * Extract Init and Next from the spec definition that has the canonical form Init /\ [Next]_vars /\ ...
   *
   * @param module
   *   TLA+ module
   * @param specName
   *   the name of the specification definition
   * @return
   *   the pair (Init, Next)
   */
  private def extractFromSpec(
      module: TlaModule,
      contextName: String,
      specName: String): (String, String) = {
    // flatten nested conjunctions into a single one
    def flattenAnds: TlaEx => TlaEx = {
      case OperEx(TlaBoolOper.and, args @ _*) =>
        val flattenedArgs = args.map(flattenAnds)
        val (ands, nonAnds) = flattenedArgs.partition {
          case OperEx(TlaBoolOper.and, _*) => true
          case _                           => false
        }
        val propagated = ands.collect { case OperEx(TlaBoolOper.and, as @ _*) =>
          as
        }.flatten
        OperEx(TlaBoolOper.and, propagated ++ nonAnds: _*)

      case ex @ _ => ex
    }

    def throwError(d: TlaOperDecl): Nothing = {
      logger.error(
          s"Operator $specName of ${d.formalParams.length} arguments is defined as: " + d.body
      )
      val msg =
        s"$contextName: Expected $specName to be in the canonical form Init /\\ [][Next]_vars /\\ ..."
      throw new ConfigurationError(msg)
    }

    module.operDeclarations.find(_.name == specName) match {
      case None =>
        throw new ConfigurationError(
            s"$contextName: Operator $specName not found (used as SPECIFICATION)"
        )

      // the canonical form: Init /\ [][Next]_vars /\ ...
      case Some(decl @ TlaOperDecl(_, List(), spec)) =>
        flattenAnds(spec) match {
          case OperEx(TlaBoolOper.and, args @ _*) =>
            val (nonFairness, fairness) = args.partition {
              case OperEx(TlaTempOper.weakFairness, _*)   => false
              case OperEx(TlaTempOper.strongFairness, _*) => false
              case _                                      => true
            }
            val (init, next) =
              nonFairness match {
                case Seq(
                        OperEx(TlaOper.apply, NameEx(init)), // Init
                        OperEx(
                            TlaTempOper.box,
                            OperEx(
                                TlaActionOper.stutter,
                                OperEx(TlaOper.apply, NameEx(next)),
                                _*,
                            ),
                        ), // [][Next]_vars
                    ) =>
                  (init, next)

                case Seq(
                        OperEx(
                            TlaTempOper.box,
                            OperEx(
                                TlaActionOper.stutter,
                                OperEx(TlaOper.apply, NameEx(next)),
                                _*,
                            ),
                        ),
                        OperEx(TlaOper.apply, NameEx(init)), // Init
                    ) =>
                  (init, next)

                case _ => throwError(decl)
              }

            if (fairness.nonEmpty) {
              val es = fairness.mkString(" /\\ ")
              val msg = s"Fairness constraints are ignored by Apalache: $es"
              logger.warn(msg)
            }

            (init, next)
          case _ => throwError(decl)
        }

      case Some(d) =>
        throwError(d)
    }
  }

  // This is an implementation dependency, not a logical one
  // We should refactor this pass to make it independent from type checking
  override def dependencies = Set(ModuleProperty.TypeChecked)

  override def transformations = Set(ModuleProperty.Configured)
}
