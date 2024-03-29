package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.convenience._
import at.forsyte.apalache.tla.lir.values.{TlaIntSet, TlaNatSet}
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import at.forsyte.apalache.tla.lir.oper.ApalacheInternalOper
import at.forsyte.apalache.tla.lir.transformations.PredResultFail
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestKeraLanguagePred extends LanguagePredTestSuite {
  private val pred = new KeraLanguagePred

  import tla._

  test("a KerA constant expression") {
    expectOk(pred.isExprOk(name("x")))
    expectOk(pred.isExprOk(prime(name("x"))))
    expectOk(pred.isExprOk(int(22)))
    expectOk(pred.isExprOk(bool(false)))
    expectOk(pred.isExprOk(str("foo")))
    expectOk(pred.isExprOk(booleanSet()))
    expectOk(pred.isExprOk(ValEx(TlaIntSet)))
    expectOk(pred.isExprOk(ValEx(TlaNatSet)))
  }

  test("a KerA set expression") {
    expectOk(pred.isExprOk(cup(enumSet(int(1)), enumSet(int(2)))))
    expectOk(pred.isExprOk(in(int(1), enumSet(int(2)))))
    expectOk(pred.isExprOk(enumSet(int(1))))
    expectOk(pred.isExprOk(filter(name("x"), enumSet(int(1)), bool(false))))
    expectOk(pred.isExprOk(map(int(2), name("x"), enumSet(int(1)))))

    expectOk(pred.isExprOk(powSet(enumSet(int(1), int(2)))))
    expectOk(pred.isExprOk(union(enumSet(enumSet(int(1), int(2))))))
    expectOk(pred.isExprOk(dotdot(int(10), int(20))))
  }

  test("a KerA finite set expression") {
    expectOk(pred.isExprOk(isFin(enumSet(int(1)))))
    expectOk(pred.isExprOk(card(enumSet(int(1)))))
  }

  test("a KerA function expression") {
    expectOk(pred.isExprOk(funDef(tla.int(1), name("x"), name("S"))))
    expectOk(pred.isExprOk(appFun(name("f"), int(3))))
    expectOk(pred.isExprOk(except(name("f"), int(3), bool(true))))
    expectOk(pred.isExprOk(funSet(name("X"), name("Y"))))
    expectOk(pred.isExprOk(dom(name("X"))))
  }

  test("KerA tuples, records, sequences") {
    expectOk(pred.isExprOk(tuple(int(1), bool(true))))
    expectOk(pred.isExprOk(enumFun(str("a"), bool(true))))
    expectOk(pred.isExprOk(head(tuple(int(1), int(2)))))
    expectOk(pred.isExprOk(tail(tuple(int(1), int(2)))))
    expectOk(pred.isExprOk(subseq(tuple(int(1), int(2)), int(3), int(4))))
    expectOk(pred.isExprOk(len(tuple(int(1), int(2)))))
    expectOk(pred.isExprOk(append(tuple(int(1), int(2)), tuple(int(2), int(3)))))
  }

  test("KerA miscellania") {
    expectOk(pred.isExprOk(label(int(2), "a")))
    expectOk(pred.isExprOk(label(int(2), "a", "b")))
  }

  test("a KerA integer expression") {
    expectOk(pred.isExprOk(lt(int(1), int(3))))
    expectOk(pred.isExprOk(le(int(1), int(3))))
    expectOk(pred.isExprOk(gt(int(1), int(3))))
    expectOk(pred.isExprOk(ge(int(1), int(3))))
    expectOk(pred.isExprOk(plus(int(1), int(3))))
    expectOk(pred.isExprOk(minus(int(1), int(3))))
    expectOk(pred.isExprOk(mult(int(1), int(3))))
    expectOk(pred.isExprOk(div(int(1), int(3))))
    expectOk(pred.isExprOk(mod(int(1), int(3))))
    expectOk(pred.isExprOk(exp(int(2), int(3))))
    expectOk(pred.isExprOk(uminus(int(2))))
  }

  test("a KerA control expression") {
    expectOk(pred.isExprOk(ite(name("p"), int(1), int(2))))
  }

  test("not a KerA set expression") {
    expectFail(pred.isExprOk(cap(enumSet(int(1)), enumSet(int(2)))))
    expectFail(pred.isExprOk(setminus(enumSet(int(1)), enumSet(int(2)))))
    expectFail(pred.isExprOk(notin(int(1), enumSet(int(2)))))
    // Keramelizer rewrites \subseteq since https://github.com/informalsystems/apalache/pull/1621
    expectFail(pred.isExprOk(subseteq(enumSet(int(1)), enumSet(int(2)))))
  }

  test("not a KerA record expression") {
    expectFail(pred.isExprOk(recSet(name("a"), name("A"))))
  }

  test("not a KerA tuple expression") {
    expectFail(pred.isExprOk(times(name("X"), name("Y"))))
  }

  test("a KerA logic expression") {
    expectOk(pred.isExprOk(or(bool(false), name("b"))))
    expectOk(pred.isExprOk(and(bool(false), name("b"))))
  }

  test("not a KerA logic expression") {
    expectFail(pred.isExprOk(equiv(bool(false), name("b"))))
    expectFail(pred.isExprOk(impl(bool(false), name("b"))))
    expectFail(pred.isExprOk(neql(bool(false), name("b"))))
  }

  test("not a KerA control expression") {
    expectFail(pred.isExprOk(caseOther(int(1), bool(false), int(2))))
    expectFail(pred.isExprOk(caseSplit(bool(false), int(2))))
  }

  test("not supported by the model checker") {
    pred.isExprOk(OperEx(ApalacheInternalOper.notSupportedByModelChecker, str("foo"))) match {
      case PredResultFail(Seq((_, "Not supported: foo"))) => () // OK
      case _                                              => fail("expected a failure")
    }
  }

  /**
   * **************************** the tests from TestFlatLanguagePred ********************************************
   */

  test("a call to a user operator") {
    val expr = enumSet(int(1), str("abc"), bool(false))
    val app = appOp(name("UserOp"), expr)
    expectFail(pred.isExprOk(app))
  }

  test("a non-nullary let-in ") {
    val app = appOp(name("UserOp"), int(3))
    val letInDef = letIn(app, declOp("UserOp", plus(int(1), name("x")), OperParam("x")).untypedOperDecl())
    expectFail(pred.isExprOk(letInDef))
  }

  test("a nullary let-in ") {
    val app = appOp(name("UserOp"))
    val letInDef = letIn(app, declOp("UserOp", plus(int(1), int(2))).untypedOperDecl())
    expectOk(pred.isExprOk(letInDef))
  }

  test("nested nullary let-in ") {
    val app = plus(appOp(name("A")), appOp(name("B")))
    val letInDef = letIn(app, declOp("A", plus(int(1), int(2))).untypedOperDecl())
    val outerLetIn =
      letIn(letInDef, declOp("B", int(3)).untypedOperDecl())
    expectOk(pred.isExprOk(outerLetIn))
  }

  test("a call to a user operator in a module") {
    val appB = appOp(name("B"), int(1))
    val defA = declOp("A", appB)
    val mod = new TlaModule("mod", Seq(defA))
    expectFail(pred.isModuleOk(mod))
  }

  test("a module without calls") {
    val appB = int(1)
    val defA = declOp("A", appB)
    val mod = new TlaModule("mod", Seq(defA))
    expectOk(pred.isModuleOk(mod))
  }
}
