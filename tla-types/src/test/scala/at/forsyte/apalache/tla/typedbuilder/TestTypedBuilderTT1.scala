package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir._

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestTypedBuilderTT1 extends FunSuite {

  private val bd = new TypedBuilder(new TypeTag1Synthesizer)

  implicit def liftTT1(tt1: TlaType1): TypeTag = Typed(tt1)

  implicit val untyped: TypeTag = Untyped()

  def assertType[T](typed: TypeTagged[T], tt1: TlaType1): Unit =
    assert(typed.typeTag == Typed(tt1))

  def assertEqualType[T](lhs: TypeTagged[T], rhs: TypeTagged[T]): Unit =
    assert(lhs.typeTag == rhs.typeTag)

  def declToNameEx(d: TlaDecl): NameEx =
    bd.name(d.name, d.typeTag)

  /**
   * WE ONLY TEST TYPE-CORRECTNESS HERE,
   * STRUCTURAL CORRECTNESS IS TESTED IN TestTypedBuilderUT
   */

  test("Validation rejects Untyped()") {
    assertThrows[TypingException] {
      bd.name("a", Untyped())
    }

    assertThrows[TypingException] {
      bd.emptySet(Untyped())
    }

    assertThrows[TypingException] {
      bd.emptySequence(Untyped())
    }

    assertThrows[TypingException] {
      bd.simpleParam("s", Untyped())
    }

    assertThrows[TypingException] {
      bd.operParam("s", 1, Untyped())
    }

    assertThrows[TypingException] {
      bd.recFunRef(Untyped())
    }

  }

  test("Names and values") {
    val typeOfA = VarT1(0)
    val nameBuild: TlaEx = bd.name("a", typeOfA) // concrete type irrelevant

    assertType(nameBuild, typeOfA)

    val vBigInt: BigInt = BigInt("1000000015943534656464398536")
    val vBigDecimal: BigDecimal = 1.111132454253626474876842798573504607
    val vBool: Boolean = false
    val vString: String = "a string val"

    val biBuild = bd.bigInt(vBigInt)
    val bdBuild = bd.decimal(vBigDecimal)
    val boolBuild = bd.bool(vBool)
    val strBuild = bd.str(vString)

    assertType(biBuild, IntT1())
    assertType(bdBuild, RealT1())
    assertType(boolBuild, BoolT1())
    assertType(strBuild, StrT1())
  }

  test("Declarations") {
    val resT = StrT1()
    // @type: Int;
    val xType = IntT1()
    val xParam = bd.simpleParam("x", xType)
    val xName = bd.name("x", xType)
    // @type: () => Str;
    val bType0 = OperT1(Seq.empty, StrT1())
    val bParam0 = bd.operParam("B", 0, bType0)
    val bName0 = bd.name("B", bType0)
    // @type: (Int) => Str;
    val bType1 = OperT1(Seq(xType), resT)
    val bParam1 = bd.operParam("B", 1, bType1)
    val bName1 = bd.name("B", bType1)
    // A == c
    val cName = bd.name("c", resT)
    val decl1 = bd.declOp("A", cName)
    // A(x) == x
    val decl2 = bd.declOp("A", xName, xParam)
    // A(B()) == B()
    val decl3 = bd.declOp("A", bd.appOp(bName0), bParam0)
    // A(x, B(_)) == B(x)
    val decl4 = bd.declOp("A", bd.appOp(bName1, xName), xParam, bParam1)

    assertType(decl1, OperT1(Seq.empty, resT))
    assertType(decl2, OperT1(Seq(xType), xType))
    assertType(decl3.body, resT)
    assertType(decl3, OperT1(Seq(bType0), resT))
    assertType(decl4.body, resT)
    assertType(decl4, OperT1(Seq(xType, bType1), resT))

    val mistypedOperParam = bd.operParam("B", 1, IntT1())
    assertThrows[TypingException] {
      bd.declOp("A", bd.appOp(bName1, xName), xParam, mistypedOperParam)
    }

    // A()
    val appEx1 = bd.appDecl(decl1)
    // A(a: Int)
    def a1 = bd.name("a", IntT1())
    val appEx2 = bd.appDecl(decl2, a1)
    // A(a: () => Str)
    def a2 = bd.name("a", OperT1(Seq.empty, StrT1()))
    val appEx3 = bd.appDecl(decl3, a2)
    // A(a: Int, B: (Int) => Str)
    def b = bd.name("b", OperT1(Seq(IntT1()), StrT1()))
    val appEx4 = bd.appDecl(decl4, a1, b)

    assertType(appEx1, resT)
    assertType(appEx2, xType)
    assertThrows[TypingException] {
      // bad arg type
      bd.appDecl(decl2, a1.withTag(BoolT1()))
    }
    assertType(appEx3, resT)
    assertThrows[TypingException] {
      // bad arg type
      bd.appDecl(decl3, a1.withTag(BoolT1()))
    }
    assertType(appEx4, resT)
    assertThrows[TypingException] {
      // bad arg type
      bd.appDecl(decl4, a1.withTag(BoolT1()), b)
    }
  }

  test("TlaOper") {
    def b = bd.name("b", IntT1())
    def a = bd.name("a", IntT1())

    // a = b
    val eqBuild1 = bd.eql(a, b)
    val eqBuild2 = bd.eql(a, bd.int(2))

    assertType(eqBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.eql(a, b.withTag(StrT1()))
    }
    assertType(eqBuild2, BoolT1())

    val neBuild1 = bd.neql(a, b)
    val neBuild2 = bd.neql(a, bd.int(2))

    assertType(neBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.neql(a, b.withTag(StrT1()))
    }
    assertType(neBuild2, BoolT1())

    def nAryOp(n: Int) = bd.name("A", OperT1(Seq.fill(n) { IntT1() }, StrT1()))

    val applyBuild1 = bd.appOp(nAryOp(0))
    val applyBuild2 = bd.appOp(nAryOp(1), a)
    val applyBuild3 = bd.appOp(nAryOp(2), a, a)
    val applyBuild4 = bd.appOp(nAryOp(3), a, a, a)

    assertType(applyBuild1, StrT1())
    assertType(applyBuild2, StrT1())
    assertType(applyBuild3, StrT1())
    assertType(applyBuild4, StrT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.appOp(nAryOp(1), b.withTag(StrT1()))
    }

    def x = bd.name("x", IntT1())
    def s = bd.name("S", SetT1(IntT1()))
    def t = bd.name("T", SetT1(BoolT1()))
    def p = bd.name("p", BoolT1())
    val chooseBuild1 = bd.choose(x, p)
    val chooseBuild2 = bd.choose(x, s, p)
    val chooseBuild3 = bd.choose(p, t, p)

    assertEqualType(chooseBuild1, x)
    assertEqualType(chooseBuild2, x)
    assertEqualType(chooseBuild3, p)

    assertThrows[TypingException] {
      // bad arg type
      bd.choose(x, s, p.withTag(IntT1()))
    }

    val labelBuild1 = bd.label(x, "lab")
    val labelBuild2 = bd.label(x, "lab", "a", "b")

    assertEqualType(labelBuild1, x)
    assertEqualType(labelBuild2, x)

    // Can't pass invalid types, as label args are passed as strings, not TlaExs
  }

  test("TlaBoolOper ") {
    def a = bd.name("a", BoolT1())
    // /\ a
    val andBuild1 = bd.and(a)
    // a /\ a
    val andBuild2 = bd.and(a, a)
    // a /\ a /\ a
    val andBuild3 = bd.and(a, a, a)
    // a /\ a /\ a /\ a
    val andBuild4 = bd.and(a, a, a, a)

    assertType(andBuild1, BoolT1())
    assertType(andBuild2, BoolT1())
    assertType(andBuild3, BoolT1())
    assertType(andBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.and(a, a.withTag(IntT1()), a)
    }

    // /\ a
    val orBuild1 = bd.or(a)
    // a /\ a
    val orBuild2 = bd.or(a, a)
    // a /\ a /\ a
    val orBuild3 = bd.or(a, a, a)
    // a /\ a /\ a /\ a
    val orBuild4 = bd.or(a, a, a, a)

    assertType(orBuild1, BoolT1())
    assertType(orBuild2, BoolT1())
    assertType(orBuild3, BoolT1())
    assertType(orBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.or(a, a.withTag(IntT1()), a)
    }

    val notBuild1 = bd.not(a)

    assertType(notBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.not(a.withTag(IntT1()))
    }

    val impliesBuild1 = bd.impl(a, a)

    assertType(impliesBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.impl(a.withTag(IntT1()), a)
    }

    val equivBuild1 = bd.equiv(a, a)

    assertType(equivBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.equiv(a.withTag(IntT1()), a)
    }

    def x = bd.name("x", IntT1())
    def S = bd.name("x", SetT1(IntT1()))
    val forallBuild1 = bd.forall(x, a)
    val forallBuild2 = bd.forall(x, S, a)

    assertType(forallBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.forall(x, a.withTag(IntT1()))
    }

    assertType(forallBuild2, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.forall(x, S.withTag(SetT1(StrT1())), a)
    }

    val existsBuild1 = bd.exists(x, a)
    val existsBuild2 = bd.exists(x, S, a)

    assertType(existsBuild1, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.exists(x, a.withTag(IntT1()))
    }

    assertType(existsBuild2, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.exists(x, S.withTag(SetT1(StrT1())), a)
    }
  }

  test("TlaActionOper") {
    def a = bd.name("a", IntT1())
    def b = bd.name("b", TupT1(TupT1(StrT1(), IntT1()), FunT1(BoolT1(), IntT1())))
    def p = bd.name("p", BoolT1())
    val primeBuild1 = bd.prime(a)
    val primeBuild2 = bd.prime(b)

    assertEqualType(primeBuild1, a)
    assertEqualType(primeBuild2, b)

    // Can't pass invalid types to a (T) => T oper

    val primeEqBuild1 = bd.primeEq(a, a)
    val primeEqBuild2 = bd.primeEq(b, b)
    val primeEqBuild3 = bd.primeEq(a, bd.int(1))

    assertType(primeEqBuild1, BoolT1())
    assertType(primeEqBuild2, BoolT1())
    assertType(primeEqBuild3, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.primeEq(a, b)
    }

    val stutterBuild = bd.stutt(p, a)

    assertType(stutterBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.stutt(a, p)
    }

    val nostutterBuild = bd.nostutt(p, a)

    assertType(nostutterBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.nostutt(a, p)
    }

    val enabledBuild = bd.enabled(p)

    assertType(enabledBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.enabled(a)
    }

    val unchangedBuild1 = bd.unchanged(a)
    val unchangedBuild2 = bd.unchanged(p)

    assertEqualType(unchangedBuild1, a)
    assertEqualType(unchangedBuild2, p)

    // Can't pass invalid types to a (T) => T oper

    val compositionBuild = bd.comp(p, p)

    assertType(compositionBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.comp(a, p)
    }

  }

  test("TlaControlOper") {
    def a = bd.name("a", IntT1())
    def b = bd.name("b", TupT1(TupT1(StrT1(), IntT1()), FunT1(BoolT1(), IntT1())))
    def p = bd.name("p", BoolT1())

    val caseBuild1 = bd.caseSplit(p, a)
    val caseBuild2 = bd.caseOther(a, p, a)
    val caseBuild3 = bd.caseSplit(p, a, p, a)
    val caseBuild4 = bd.caseSplit(p, b, p, b)

    assertEqualType(caseBuild1, a)
    assertEqualType(caseBuild2, a)
    assertEqualType(caseBuild3, a)
    assertEqualType(caseBuild4, b)

    assertThrows[TypingException] {
      // bad arg type
      bd.caseSplit(a, p)
    }
    assertThrows[TypingException] {
      // bad arg type
      bd.caseSplit(p, a, p, b)
    }
    assertThrows[TypingException] {
      // bad arg type
      bd.caseOther(a, a, a)
    }
    assertThrows[TypingException] {
      // bad arg type
      bd.caseOther(a, p, a, p, b)
    }

    val iteBuild1 = bd.ite(p, a, a)
    val iteBuild2 = bd.ite(p, b, b)

    assertEqualType(iteBuild1, a)
    assertEqualType(iteBuild2, b)

    assertThrows[TypingException] {
      // bad arg type
      bd.ite(p, a, b)
    }

    // LET A == p IN a
    val letInBuild1 = bd.letIn(a, bd.declOp("A", p))
    // LET B(q) == {q} IN B(a)
    def q = bd.name("q", IntT1())
    val bDecl = bd.declOp("B", bd.enumSet(q), bd.simpleParam(q.name, q.typeTag))
    val letInBuild2 =
      bd.letIn(
          bd.appDecl(bDecl, a),
          bDecl
      )

    assertEqualType(letInBuild1, a)
    letInBuild1.decls match {
      case Seq(decl) => assertType(decl, OperT1(Seq.empty, asTlaType1(p.typeTag)))
      case _         => assert(false)
    }

    assertType(letInBuild2, SetT1(asTlaType1(a.typeTag)))
    letInBuild2.decls match {
      case Seq(decl) =>
        val qType = asTlaType1(q.typeTag)
        assertType(decl, OperT1(Seq(qType), SetT1(qType)))
      case _ => assert(false)
    }
  }

  test("TlaTempOper") {
    def a = bd.name("a", IntT1())
    def p = bd.name("p", BoolT1())

    val AABuild = bd.AA(a, p)

    assertType(AABuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.AA(p, a)
    }

    val EEBuild = bd.EE(a, p)

    assertType(EEBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.EE(p, a)
    }

    val boxBuild = bd.box(p)

    assertType(boxBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.box(a)
    }

    val diamondBuild = bd.diamond(p)

    assertType(diamondBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.diamond(a)
    }

    val leadsToBuild = bd.leadsTo(p, p)

    assertType(leadsToBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.leadsTo(a, p)
    }

    val guaranteesBuild = bd.guarantees(p, p)

    assertType(guaranteesBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.guarantees(a, p)
    }

    val strongFairnessBuild = bd.SF(a, p)

    assertType(strongFairnessBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.SF(p, a)
    }

    val weakFairnessBuild = bd.WF(a, p)

    assertType(weakFairnessBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.WF(p, a)
    }
  }

  test("TlaArithOper") {
    def a = bd.name("a", IntT1())
    def b = bd.name("b", TupT1(TupT1(StrT1(), IntT1()), FunT1(BoolT1(), IntT1())))
    def p = bd.name("p", BoolT1())
    //    def r = bd.name("r", RealT1())

    val plusBuild1 = bd.plus(a, a)
    val plusBuild2 = bd.plus(a, bd.int(2))
    val plusBuild3 = bd.plus(bd.int(1), a)
    val plusBuild4 = bd.plus(bd.int(1), bd.int(2))

    assertType(plusBuild1, IntT1())
    assertType(plusBuild2, IntT1())
    assertType(plusBuild3, IntT1())
    assertType(plusBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.plus(p, a)
    }

    val minusBuild1 = bd.minus(a, a)
    val minusBuild2 = bd.minus(a, bd.int(2))
    val minusBuild3 = bd.minus(bd.int(1), a)
    val minusBuild4 = bd.minus(bd.int(1), bd.int(2))

    assertType(minusBuild1, IntT1())
    assertType(minusBuild2, IntT1())
    assertType(minusBuild3, IntT1())
    assertType(minusBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.minus(p, a)
    }

    val uminusBuild = bd.uminus(a)

    assertType(uminusBuild, IntT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.uminus(p)
    }

    val multBuild1 = bd.mult(a, a)
    val multBuild2 = bd.mult(a, bd.int(2))
    val multBuild3 = bd.mult(bd.int(1), a)
    val multBuild4 = bd.mult(bd.int(1), bd.int(2))

    assertType(multBuild1, IntT1())
    assertType(multBuild2, IntT1())
    assertType(multBuild3, IntT1())
    assertType(multBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.mult(p, a)
    }

    val divBuild1 = bd.div(a, a)
    val divBuild2 = bd.div(a, bd.int(2))
    val divBuild3 = bd.div(bd.int(1), a)
    val divBuild4 = bd.div(bd.int(1), bd.int(2))

    assertType(divBuild1, IntT1())
    assertType(divBuild2, IntT1())
    assertType(divBuild3, IntT1())
    assertType(divBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.div(p, a)
    }

    val modBuild1 = bd.mod(a, a)
    val modBuild2 = bd.mod(a, bd.int(2))
    val modBuild3 = bd.mod(bd.int(1), a)
    val modBuild4 = bd.mod(bd.int(1), bd.int(2))

    assertType(modBuild1, IntT1())
    assertType(modBuild2, IntT1())
    assertType(modBuild3, IntT1())
    assertType(modBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.mod(p, a)
    }

//    val rDivBuild1 = bd.rDiv(a, a)
//    val rDivBuild2 = bd.rDiv(a, bd.real(2))
//    val rDivBuild3 = bd.rDiv(bd.real(1), a)
//    val rDivBuild4 = bd.rDiv(bd.real(1), bd.real(2))
//
//    assertType(rDivBuild1, IntT1())
//    assertType(rDivBuild2, IntT1())
//    assertType(rDivBuild3, IntT1())
//    assertType(rDivBuild4, IntT1())
//
//    assertThrows[TypingException] {
//      // bad arg type
//      bd.rDiv(p, a)
//    }

    val expBuild1 = bd.exp(a, a)
    val expBuild2 = bd.exp(a, bd.int(2))
    val expBuild3 = bd.exp(bd.int(1), a)
    val expBuild4 = bd.exp(bd.int(1), bd.int(2))

    assertType(expBuild1, IntT1())
    assertType(expBuild2, IntT1())
    assertType(expBuild3, IntT1())
    assertType(expBuild4, IntT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.exp(p, a)
    }

//    val dotdotBuild1 = bd.dotdot(n_a, n_b)
//    val dotdotBuild2 = bd.dotdot(n_a, bd.int(2))
//    val dotdotBuild3 = bd.dotdot(bd.int(1), n_b)
//    val dotdotBuild4 = bd.dotdot(bd.int(1), bd.int(2))
//
//    assert(dotdotBuild1 == OperEx(TlaArithOper.dotdot, n_a, n_b))
//    assert(dotdotBuild2 == OperEx(TlaArithOper.dotdot, n_a, ValEx(TlaInt(2))))
//    assert(dotdotBuild3 == OperEx(TlaArithOper.dotdot, ValEx(TlaInt(1)), n_b))
//    assert(dotdotBuild4 == OperEx(TlaArithOper.dotdot, ValEx(TlaInt(1)), ValEx(TlaInt(2))))

    val dotdotBuild1 = bd.dotdot(a, a)
    val dotdotBuild2 = bd.dotdot(a, bd.int(2))
    val dotdotBuild3 = bd.dotdot(bd.int(1), a)
    val dotdotBuild4 = bd.dotdot(bd.int(1), bd.int(2))

    assertType(dotdotBuild1, SetT1(IntT1()))
    assertType(dotdotBuild2, SetT1(IntT1()))
    assertType(dotdotBuild3, SetT1(IntT1()))
    assertType(dotdotBuild4, SetT1(IntT1()))

    assertThrows[TypingException] {
      // bad arg type
      bd.dotdot(p, a)
    }

    val ltBuild1 = bd.lt(a, a)
    val ltBuild2 = bd.lt(a, bd.int(2))
    val ltBuild3 = bd.lt(bd.int(1), a)
    val ltBuild4 = bd.lt(bd.int(1), bd.int(2))

    assertType(ltBuild1, BoolT1())
    assertType(ltBuild2, BoolT1())
    assertType(ltBuild3, BoolT1())
    assertType(ltBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.lt(p, a)
    }

    val gtBuild1 = bd.gt(a, a)
    val gtBuild2 = bd.gt(a, bd.int(2))
    val gtBuild3 = bd.gt(bd.int(1), a)
    val gtBuild4 = bd.gt(bd.int(1), bd.int(2))

    assertType(gtBuild1, BoolT1())
    assertType(gtBuild2, BoolT1())
    assertType(gtBuild3, BoolT1())
    assertType(gtBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.gt(p, a)
    }

    val leBuild1 = bd.le(a, a)
    val leBuild2 = bd.le(a, bd.int(2))
    val leBuild3 = bd.le(bd.int(1), a)
    val leBuild4 = bd.le(bd.int(1), bd.int(2))

    assertType(leBuild1, BoolT1())
    assertType(leBuild2, BoolT1())
    assertType(leBuild3, BoolT1())
    assertType(leBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.le(p, a)
    }

    val geBuild1 = bd.ge(a, a)
    val geBuild2 = bd.ge(a, bd.int(2))
    val geBuild3 = bd.ge(bd.int(1), a)
    val geBuild4 = bd.ge(bd.int(1), bd.int(2))

    assertType(geBuild1, BoolT1())
    assertType(geBuild2, BoolT1())
    assertType(geBuild3, BoolT1())
    assertType(geBuild4, BoolT1())

    assertThrows[TypingException] {
      // bad arg type
      bd.ge(p, a)
    }
  }

  test("TlaFiniteSetOper") {
    def a = bd.name("a", IntT1())
    def S = bd.name("S", SetT1(IntT1()))

    val cardinalityBuild = bd.card(S)

    assertType(cardinalityBuild, IntT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.card(a)
    }

    val isFiniteSetBuild = bd.isFin(S)

    assertType(isFiniteSetBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.isFin(a)
    }
  }

  test("TlaFunOper") {
    val aType = IntT1()
    val fCdmType = StrT1()
    def a = bd.name("a", aType)
    def T = bd.name("T", SetT1(aType))
    def R = bd.name("R", SetT1(BoolT1()))
    def s = bd.name("s", StrT1())
    def p = bd.name("p", BoolT1())
    def f = bd.name("f", FunT1(aType, fCdmType))
    def r1 = bd.name("r1", RecT1("x" -> IntT1()))
    def r2 = bd.name("r2", RecT1("x" -> IntT1(), "y" -> BoolT1()))
    def t = bd.name("t", TupT1(IntT1(), BoolT1()))
    def g = bd.name("g",
        FunT1(
            IntT1(),
            TupT1(
                RecT1("a" -> BoolT1()),
                SeqT1(StrT1())
            )
        ))

    val appBuild = bd.appFun(f, a)

    assertType(appBuild, fCdmType)
    assertThrows[TypingException] {
      // bad arg type
      bd.appFun(f, p)
    }

    val domainBuild = bd.dom(f)

    assertType(domainBuild, SetT1(aType))
    assertThrows[TypingException] {
      // bad arg type
      bd.dom(a)
    }

    val recBuild1 = bd.record(bd.str("x"), a)
    val recBuild2 = bd.record(bd.str("x"), a, bd.str("y"), p)

    assertEqualType(recBuild1, r1)
    assertEqualType(recBuild2, r2)
    assertThrows[TypingException] {
      // bad arg type
      bd.record(p, a)
    }

    // Except arguments must be TUPLES!
    val exceptBuild1 = bd.except(f, bd.tuple(a), bd.str("abc"))
    val exceptBuild2 = bd.except(
        f,
        bd.tuple(a),
        bd.str("abc"),
        bd.tuple(bd.int(1)),
        bd.str("def")
    )
    val exceptBuild3 = bd.except(r1, bd.tuple(bd.str("x")), a)
    val exceptBuild4 = bd.except(r2, bd.tuple(bd.str("x")), a)
    val exceptBuild5 = bd.except(t, bd.tuple(bd.int(1)), a)
    val exceptBuild6 = bd.except(t, bd.tuple(bd.int(2)), p)
    val exceptBuild7 = bd.except(
        g,
        bd.tuple(
            bd.int(1),
            bd.int(1),
            bd.str("a")
        ),
        p,
        bd.tuple(
            bd.int(1),
            bd.int(2),
            bd.int(3)
        ),
        bd.str("a")
    )

    assertEqualType(exceptBuild1, f)
    assertEqualType(exceptBuild2, f)
    assertEqualType(exceptBuild3, r1)
    assertEqualType(exceptBuild4, r2)
    assertEqualType(exceptBuild5, t)
    assertEqualType(exceptBuild6, t)
    assertEqualType(exceptBuild7, g)

    assertThrows[TypingException] {
      // bad arg type
      bd.except(f, bd.tuple(a), a)
    }

    assertThrows[TypingException] {
      // can't access tuple index by variable
      bd.except(t, bd.tuple(a), a)
    }

    assertThrows[TypingException] {
      // can't access record field by variable
      bd.except(t, bd.tuple(s), a)
    }

    assertThrows[TypingException] {
      // bad arg type
      bd.except(
          g,
          bd.tuple(
              bd.int(1),
              bd.int(1),
              bd.str("a")
          ),
          a // not Bool
      )
    }

    assertThrows[TypingException] {
      // bad arg type
      bd.except(
          g,
          bd.tuple(
              bd.int(1),
              bd.str("a"), // swapped order
              bd.int(1) // swapped order
          ),
          p
      )
    }

    // [ a \in T |-> s]
    val funDefBuild1 = bd.funDef(s, a, T)
    // [ a \in T, p \in R |-> s]
    val funDefBuild2 = bd.funDef(s, a, T, p, R)

    assertType(funDefBuild1, FunT1(IntT1(), StrT1()))
    assertType(funDefBuild2, FunT1(TupT1(IntT1(), BoolT1()), StrT1()))

    assertThrows[TypingException] {
      // bad arg type
      bd.funDef(s, a, R)
    }

    val tupleBuild1 = bd.tuple()
    val tupleBuild2 = bd.tuple(a, p)

    assertType(tupleBuild1, TupT1())
    assertType(tupleBuild2, TupT1(IntT1(), BoolT1()))

    // Can't pass invalid types to a (T1, ..., Tn) => (T1, ..., Tn) oper
  }

  test("TlaSeqOper") {
    def a = bd.name("a", IntT1())
    def s = bd.name("s", SeqT1(IntT1()))

    val seqBuild1 = bd.emptySequence(SeqT1(IntT1()))
    val seqBuild2 = bd.sequence(a, a)

    assertEqualType(seqBuild1, s)
    assertEqualType(seqBuild2, s)
    assertThrows[TypingException] {
      // bad arg type
      bd.sequence(a, s)
    }

    val appendBuild = bd.append(s, a)

    assertEqualType(appendBuild, s)
    assertThrows[TypingException] {
      // bad arg type
      bd.append(s, s)
    }

    val concatBuild = bd.concat(s, s)

    assertEqualType(concatBuild, s)
    assertThrows[TypingException] {
      // bad arg type
      bd.concat(s, a)
    }

    val headBuild = bd.head(s)

    assertEqualType(headBuild, a)
    assertThrows[TypingException] {
      // bad arg type
      bd.head(a)
    }

    val tailBuild = bd.tail(s)

    assertEqualType(tailBuild, s)
    assertThrows[TypingException] {
      // bad arg type
      bd.tail(a)
    }

    val lenBuild = bd.len(s)

    assertType(lenBuild, IntT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.len(a)
    }
  }

  test("TlaSetOper") {
    def a = bd.name("a", IntT1())
    def S = bd.name("S", SetT1(IntT1()))
    def p = bd.name("p", BoolT1())
    def T = bd.name("T", SetT1(BoolT1()))

    val enumSetBuild1 = bd.emptySet(S.typeTag)
    val enumSetBuild2 = bd.enumSet(a, a)

    assertEqualType(enumSetBuild1, S)
    assertEqualType(enumSetBuild2, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.emptySet(IntT1())
    }
    assertThrows[TypingException] {
      // bad arg type
      bd.enumSet(a, p)
    }

    val inBuild = bd.in(a, S)

    assertType(inBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.in(a, a)
    }

    val notinBuild = bd.notin(a, S)

    assertType(notinBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.notin(a, a)
    }

    val capBuild = bd.cap(S, S)

    assertEqualType(capBuild, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.cap(a, a)
    }

    val cupBuild = bd.cup(S, S)

    assertEqualType(cupBuild, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.cup(a, a)
    }

    val unionBuild = bd.union(bd.enumSet(S, S))

    assertEqualType(unionBuild, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.union(S)
    }

    val filterBuild = bd.filter(a, S, p)

    assertEqualType(filterBuild, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.filter(a, S, a)
    }

    val mapBuild1 = bd.map(p, a, S)
    val mapBuild2 = bd.map(p, a, S, p, T)

    assertEqualType(mapBuild1, T)
    assertEqualType(mapBuild2, T)

    assertThrows[TypingException] {
      // bad arg type
      bd.map(p, a, T)
    }

    val funSetBuild = bd.funSet(S, T)

    assertType(funSetBuild, SetT1(FunT1(IntT1(), BoolT1())))
    assertThrows[TypingException] {
      // bad arg type
      bd.funSet(S, a)
    }

    val recSetBuild1 = bd.recSet()
    val recSetBuild2 = bd.recSet(bd.str("x"), S)

    assertType(recSetBuild1, SetT1(RecT1()))
    assertType(recSetBuild2, SetT1(RecT1("x" -> IntT1())))

    assertThrows[TypingException] {
      // key arg has to be string literal
      bd.recSet(bd.name("x", StrT1()), S)
    }

    assertThrows[TypingException] {
      // bad arg type
      bd.recSet(bd.str("x"), a)
    }

    val seqSetBuild = bd.seqSet(S)

    assertType(seqSetBuild, SetT1(SeqT1(IntT1())))
    assertThrows[TypingException] {
      // bad arg type
      bd.seqSet(a)
    }

    val subseteqBuild = bd.subseteq(S, S)

    assertType(subseteqBuild, BoolT1())
    assertThrows[TypingException] {
      // bad arg type
      bd.subseteq(S, T)
    }

    val setminusBuild = bd.setminus(S, S)

    assertEqualType(setminusBuild, S)
    assertThrows[TypingException] {
      // bad arg type
      bd.setminus(S, T)
    }

    val timesBuild1 = bd.times()
    val timesBuild2 = bd.times(S)
    val timesBuild3 = bd.times(S, T)

    assertType(timesBuild1, SetT1(TupT1()))
    assertType(timesBuild2, SetT1(TupT1(IntT1())))
    assertType(timesBuild3, SetT1(TupT1(IntT1(), BoolT1())))
    assertThrows[TypingException] {
      // bad arg type
      bd.times(S, a)
    }

    val powSetBuild = bd.powSet(S)

    assertType(powSetBuild, SetT1(SetT1(IntT1())))
    assertThrows[TypingException] {
      // bad arg type
      bd.powSet(a)
    }
  }

  test("TlcOper") {
    // TODO
  }
  test("ApalacheOper") {
    // TODO
  }

}
