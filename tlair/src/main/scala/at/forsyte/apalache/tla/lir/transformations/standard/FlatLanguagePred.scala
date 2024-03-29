package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir.oper.TlaOper
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.transformations.{PredResult, PredResultFail, PredResultOk}

/**
 * <p>Test whether the expressions fit into the flat fragment: all calls to user operators are inlined, except the calls
 * to nullary let-in definitions.</p>
 *
 * <p>To get a better idea of the accepted fragment, check TestFlatLanguagePred.</p>
 *
 * @see
 *   TestFlatLanguagePred
 * @author
 *   Igor Konnov
 */
class FlatLanguagePred extends ContextualLanguagePred {

  override protected def isOkInContext(letDefs: Set[String], expr: TlaEx): PredResult = {
    expr match {
      case LetInEx(body, defs @ _*) =>
        // check the let-definitions first, in a sequence, as they may refer to each other
        val defsResult = eachDefRec(letDefs, defs.toList)
        val newLetDefs = defs.map(_.name).toSet
        // check the terminal expression in the LET-IN chain, by assuming the context generated by the definitions
        defsResult
          .and(isOkInContext(letDefs ++ newLetDefs, body))

      case e @ OperEx(TlaOper.apply, NameEx(opName), args @ _*) =>
        // the only allowed case is calling a nullary operator that was declared with let-in
        if (!letDefs.contains(opName)) {
          PredResultFail(List((e.ID, s"undeclared operator $opName")))
        } else if (args.nonEmpty) {
          PredResultFail(List((e.ID, s"non-nullary operator $opName")))
        } else {
          PredResultOk()
        }

      case OperEx(_, args @ _*) =>
        // check the arguments recursively
        args.foldLeft[PredResult](PredResultOk()) { case (r, arg) =>
          r.and(isOkInContext(letDefs, arg))
        }

      case _ =>
        PredResultOk()
    }
  }
}

object FlatLanguagePred {
  def apply(): FlatLanguagePred = {
    new FlatLanguagePred
  }
}
