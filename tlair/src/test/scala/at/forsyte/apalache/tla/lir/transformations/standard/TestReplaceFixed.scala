package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir.UntypedPredefs._
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.oper.{ApalacheOper, TlaActionOper, TlaArithOper}
import at.forsyte.apalache.tla.lir.transformations.TlaExTransformation
import at.forsyte.apalache.tla.lir.transformations.impl.IdleTracker
import at.forsyte.apalache.tla.lir._
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestReplaceFixed extends AnyFunSuite with TestingPredefs {
  import tla._

  def mkTr(replacedEx: TlaEx, newEx: => TlaEx): TlaExTransformation =
    ReplaceFixed(new IdleTracker()).whenEqualsTo(replacedEx, newEx)

  test("Basic replacement") {
    val ex = n_x
    val tr = mkTr(n_x, n_y)
    assert(tr(ex) == n_y)
  }

  test("Nested replacement") {
    val ex = tla.plus(n_x, n_z)
    val tr = mkTr(n_x, n_y)
    assert(tr(ex) == tla.plus(n_y, n_z).untyped())
  }

  test("UID uniqueness") {
    val ex = tla.plus(n_x, n_x)
    val tr = mkTr(n_x, NameEx("y"))

    val assertCond = tr(ex) match {
      case OperEx(TlaArithOper.plus, y1, y2) =>
        y1 == y2 && y1.ID != y2.ID
      case _ => false
    }
    assert(assertCond)
  }

  test("Replacement with a partial function") {
    val assignX = tla.assign(tla.prime(tla.name("x")), tla.int(3))
    val eqX = tla.eql(tla.prime(tla.name("x")), tla.int(3))
    val ex = tla.and(assignX, eqX)
    val repl = ReplaceFixed(new IdleTracker()).withFun {
      case OperEx(ApalacheOper.assign, lhs @ OperEx(TlaActionOper.prime, NameEx(_)), rhs) =>
        tla.eql(lhs, rhs)
    }
    assert(repl(ex) == tla.and(eqX, eqX).untyped())
  }

  test("Replace in Let-in") {
    val decl = TlaOperDecl("A", List.empty[OperParam], n_x)
    val ex = tla.letIn(tla.appDecl(decl), decl)
    val tr = mkTr(n_x, n_y)
    val expectedDecl = TlaOperDecl("A", List.empty[OperParam], n_y)
    val expectedEx = tla.letIn(tla.appDecl(expectedDecl), expectedDecl)
    assert(tr(ex) == expectedEx.untyped())
  }

  test("Old test batch") {
    val transformation = mkTr(n_x, n_y)
    val pa1 = n_x -> n_y
    val pa2 = n_z -> n_z
    val pa3 = prime(n_x).untyped() -> prime(n_y).untyped()
    val pa4 = ite(n_p, n_x, n_y).untyped() -> ite(n_p, n_y, n_y).untyped()
    val pa5 = letIn(
        plus(n_z, appOp(n_A)),
        declOp("A", n_q).untypedOperDecl(),
    ).untyped() -> letIn(
        plus(n_z, appOp(n_A)),
        declOp("A", n_q).untypedOperDecl(),
    ).untyped()
    val pa6 = letIn(
        enumSet(plus(n_x, appOp(n_A)), appOp(n_B, n_x)),
        declOp("A", n_x).untypedOperDecl(),
        declOp("B", n_p, "p").untypedOperDecl(),
    ).untyped() -> letIn(
        enumSet(plus(n_y, appOp(n_A)), appOp(n_B, n_y)),
        declOp("A", n_y).untypedOperDecl(),
        declOp("B", n_p, "p").untypedOperDecl(),
    ).untyped()

    val expected = Seq(
        pa1,
        pa2,
        pa3,
        pa4,
        pa5,
        pa6,
    )
    val cmp = expected.map { case (k, v) =>
      (v, transformation(k))
    }
    cmp.foreach { case (ex, act) =>
      assert(ex == act)
    }
  }
}
