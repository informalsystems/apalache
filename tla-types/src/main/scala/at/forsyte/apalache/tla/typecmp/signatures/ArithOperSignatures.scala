package at.forsyte.apalache.tla.typecmp.signatures

import at.forsyte.apalache.tla.lir.oper.TlaArithOper
import at.forsyte.apalache.tla.lir.{BoolT1, IntT1, OperT1, SetT1}
import at.forsyte.apalache.tla.typecmp.{liftOper, SignatureMap}

/**
 * Produces a SignatureMap for all arithmetic operators
 *
 * @author
 *   Jure Kukovec
 */
object ArithOperSignatures {
  import TlaArithOper._

  def getMap: SignatureMap = {
    // All these operators have fixed arity 2 and none of them are polymorphic
    // (Int, Int) => Int
    val intOpers: SignatureMap = Seq(
        plus,
        minus,
        mult,
        div,
        mod,
        exp,
    ).map { _ -> liftOper(OperT1(Seq(IntT1(), IntT1()), IntT1())) }.toMap

    // Same as above, except they return BoolT1 instead of IntT1()
    // (Int, Int) => Bool
    val boolOpers: SignatureMap = Seq(
        lt,
        gt,
        le,
        ge,
    ).map { _ -> liftOper(OperT1(Seq(IntT1(), IntT1()), BoolT1())) }.toMap

    // - is unary and dotdot returns a set
    // (Int) => Int,
    // (Int,Int) => Set(Int)
    val rest: SignatureMap = Map(
        TlaArithOper.uminus -> OperT1(Seq(IntT1()), IntT1()),
        TlaArithOper.dotdot -> OperT1(Seq(IntT1(), IntT1()), SetT1(IntT1())),
    )
    intOpers ++ boolOpers ++ rest
  }

}
