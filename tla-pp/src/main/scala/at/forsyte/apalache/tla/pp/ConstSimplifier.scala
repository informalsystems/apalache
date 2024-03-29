package at.forsyte.apalache.tla.pp

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.transformations.standard.FlatLanguagePred
import at.forsyte.apalache.tla.lir.transformations.{LanguageWatchdog, TlaExTransformation, TransformationTracker}

/**
 * A simplifier of constant TLA+ expressions, e.g., rewriting 1 + 2 to 3.
 *
 * @author
 *   Igor Konnov
 */
class ConstSimplifier(tracker: TransformationTracker) extends ConstSimplifierBase with TlaExTransformation {
  override def apply(expr: TlaEx): TlaEx = {
    LanguageWatchdog(FlatLanguagePred()).check(expr)
    simplify(expr)
  }

  def simplify(rootExpr: TlaEx): TlaEx = {
    rewriteDeep(rootExpr)
  }

  private def rewriteDeep: TlaExTransformation = tracker.trackEx {
    case ex @ OperEx(oper, args @ _*) =>
      simplifyShallow(OperEx(oper, args.map(rewriteDeep): _*)(ex.typeTag))

    case ex @ LetInEx(body, defs @ _*) =>
      val newDefs = defs.map { d =>
        TlaOperDecl(d.name, d.formalParams, simplify(d.body))(d.typeTag)
      }
      LetInEx(simplify(body), newDefs: _*)(ex.typeTag)

    case ex => ex
  }
}

object ConstSimplifier {
  def apply(tracker: TransformationTracker): ConstSimplifier = new ConstSimplifier(tracker)
}
