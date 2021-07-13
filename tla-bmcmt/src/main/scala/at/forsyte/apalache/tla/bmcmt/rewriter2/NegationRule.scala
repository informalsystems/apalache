package at.forsyte.apalache.tla.bmcmt.rewriter2

import at.forsyte.apalache.tla.lir.{OperEx, TlaEx}
import at.forsyte.apalache.tla.lir.oper.TlaBoolOper
import BasicOps._
import at.forsyte.apalache.tla.bmcmt.RewriterException
import at.forsyte.apalache.tla.bmcmt.types.BoolT
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import scalaz._
import scalaz.Scalaz._

/**
 * An alternate implementation of NegRule
 */
class NegationRule(rewriter: Rewriter) extends Rule {
  // Basic applicability check
  override def isApplicable(ex: TlaEx): Boolean = ex match {
    case OperEx(TlaBoolOper.not, _) => true
    case _                          => false
  }

  // Rule application. Returns a Right containing the rewritten expression, if ex has the correct shape,
  // or a Left, containing the appropriate exception
  override def apply(ex: TlaEx): StateCompWithExceptions[TlaEx] = for {
    // .point lifts the static transformation into a stateful computation that does not modify the internal
    // state. Then, fromEither bridges from Either[...] to EitherT[...]
    // if exOrException(ex) returns a Right, that value is read into inner
    inner <- EitherT.fromEither(exOrException(ex).point[RewritingComputation])
    // Then, the inner value is recursively rewritten, using the provided rewriter.
    newExRewritten <- rewriter.rewriteUntilDone(inner) // "c0"
    // Next, we prep a fresh BoolT-typed cell ...
    predCell <- newCell(BoolT())
    predCellName = predCell.toNameEx // "c1"
    // ... and add the relevant assertion to the constraints ( -c1 = c0 )
    _ <- assertGroundExpr(tla.eql(tla.not(predCellName), newExRewritten))
    // The result, if none of the steps fail with an Exception, is the cell containing the predicate, c1
    // If, at any point, one of the Either[...] values produced was a Left (= exception),
    // that value is propagated to the actual final result (i.e. the first exception encountered is "thrown")
  } yield predCellName

  // Static evaluation of ex as a representation of a TlaOper.not expression.
  // If ex is equal to (- y), returns Right(y), otherwise returns Left(RewritereException)
  private def exOrException(ex: TlaEx): Either[Exception, TlaEx] = ex match {
    case OperEx(TlaBoolOper.not, negEx) => Right(negEx)
    case badEx =>
      Left(new RewriterException("%s is not applicable".format(getClass.getSimpleName), badEx))
  }
}
