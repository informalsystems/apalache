package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir.{LetInEx, OperEx, TlaEx, TlaOperDecl}
import at.forsyte.apalache.tla.lir.transformations.{TlaExTransformation, TransformationTracker}

/**
 * ReplacedFixed generates syntax-based substitution transformations, which replace every instance
 * of one syntactic form in an expression with a fresh copy of another.
 *
 * Example: ReplaceFixed( NameEx("x"), NameEx("t_3"), ... ) applied to x + x
 * returns t_3 + t_3, but both instances of t_3 have distinct UIDs.
 */
object ReplaceFixed {

  /** This method is applied to leaves in the expression tree */
  private def replaceOne(
      replacedEx: TlaEx,
      newEx: => TlaEx, // takes a [=> TlaEx] to allow for the creation of new instances (with distinct UIDs)
      tracker: TransformationTracker
  ): TlaExTransformation = tracker.trackEx { ex =>
    if (ex == replacedEx) newEx else ex
  }

  /**
   * Returns a transformation which replaces every instance of `replacedEx`
   * with an instance of `newEx`
   */
  def apply(
      replacedEx: TlaEx,
      newEx: => TlaEx, // takes a [=> TlaEx] to allow for the creation of new instances (with distinct UIDs)
      tracker: TransformationTracker
  ): TlaExTransformation = tracker.trackEx { ex =>
    val tr = replaceOne(replacedEx, newEx, tracker)
    lazy val self = apply(replacedEx, newEx, tracker)
    ex match {
      case LetInEx(body, defs @ _*) =>
        // Transform bodies of all op.defs
        val newDefs = defs map tracker.trackOperDecl { d => d.copy(body = self(d.body)) }
        val newBody = self(body)
        val retEx = if (defs == newDefs && body == newBody) ex else LetInEx(newBody, newDefs: _*)
        tr(retEx)

      case OperEx(op, args @ _*) =>
        val newArgs = args map self
        val retEx = if (args == newArgs) ex else OperEx(op, newArgs: _*)
        tr(retEx)

      case _ => tr(ex)
    }
  }

}
