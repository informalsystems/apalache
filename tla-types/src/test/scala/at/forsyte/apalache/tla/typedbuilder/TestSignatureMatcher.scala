package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.TlaFunOper
import at.forsyte.apalache.tla.lir.values._
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestSignatureMatcher extends FunSuite with TestingPredefs with BeforeAndAfter {

  // Most methods are tested within TestTypedBuilderTT1,
  // we additionally test the most complex matching methods individually

  implicit def liftTT1(tt1: TlaType1): TypeTag = Typed(tt1)
  def intV(i: Int) = ValEx(TlaInt(i))(IntT1())
  def strV(s: String) = ValEx(TlaStr(s))(StrT1())
  def boolV(b: Boolean) = ValEx(TlaBool(b))(BoolT1())
  def tupWrap(args: TlaEx*) =
    OperEx(TlaFunOper.tuple, args: _*)(
        TupT1(
            args map { x => asTlaType1(x.typeTag) }: _*
        )
    )
  def typedFn(tt1: TlaType1) = NameEx("f")(tt1)

  test("EXCEPT: Bad tag") {
    val fn = typedFn(IntT1())
    def one = intV(1)
    def oneArg = tupWrap(one)

    // [f EXCEPT ![1] = 1]
    val args = Seq(
        fn,
        oneArg,
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.isEmpty)
  }

  test("Plain EXCEPT: function") {
    val fn = typedFn(FunT1(IntT1(), IntT1()))
    def one = intV(1)
    def oneArg = tupWrap(one)

    // [f EXCEPT ![1] = 1]
    val args = Seq(
        fn,
        oneArg,
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))

  }

  test("Plain EXCEPT: tuple") {
    val fn = typedFn(TupT1(IntT1(), IntT1()))
    def one = intV(1)
    def oneArg = tupWrap(one)

    // [f EXCEPT ![1] = 1]
    val args = Seq(
        fn,
        oneArg,
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))
  }

  test("Plain EXCEPT: record") {
    val fn = typedFn(RecT1("a" -> IntT1()))

    // [f EXCEPT !["a"] = 1]
    val args = Seq(
        fn,
        tupWrap(strV("a")),
        intV(1)
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))
  }

  test("Plain EXCEPT: sequence") {
    val fn = typedFn(SeqT1(IntT1()))
    def one = intV(1)

    // [f EXCEPT ![1] = 1]
    val args = Seq(
        fn,
        tupWrap(one),
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))
  }

  test("2 point EXCEPT: function") {
    val fn = typedFn(FunT1(IntT1(), IntT1()))
    def one = intV(1)
    def two = intV(2)

    // [f EXCEPT ![1] = 1, ![2] = 2]
    val args = Seq(
        fn,
        tupWrap(one),
        one,
        tupWrap(two),
        two
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))

  }

  test("2 level EXCEPT: function only") {
    // @type: Int -> ( Str -> Int );
    val fn = typedFn(
        FunT1(
            IntT1(),
            FunT1(StrT1(), IntT1())
        )
    )
    def one = intV(1)
    def a = strV("a")
    def fnArgs = tupWrap(one, a)

    // [f EXCEPT ![1]["a"] = 1]
    val args = Seq(
        fn,
        fnArgs,
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))

  }

  test("2 level EXCEPT: function + record") {
    // @type: Int -> [ a: Int ] ;
    val fn = typedFn(
        FunT1(
            IntT1(),
            RecT1("a" -> IntT1())
        )
    )
    def one = intV(1)
    def a = strV("a")
    def fnArgs = tupWrap(one, a)

    // [f EXCEPT ![1]["a"] = 1]
    val args = Seq(
        fn,
        fnArgs,
        one
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))

  }

  test("3 level 2 point EXCEPT: function -> tup -> rec/seq") {
    // @type: Int -> ([ a: Bool ], Seq(Str)) ;
    val fn = typedFn(
        FunT1(
            IntT1(),
            TupT1(
                RecT1("a" -> BoolT1()),
                SeqT1(StrT1())
            )
        )
    )

    val fnArgs1 = tupWrap(intV(1), intV(1), strV("a"))
    val fnArgs2 = tupWrap(intV(1), intV(2), intV(3))

    // [f EXCEPT ![1][1]["a"] = TRUE, ![1][2][3] = "b" ]
    val args = Seq(
        fn,
        fnArgs1,
        boolV(true),
        fnArgs2,
        strV("b")
    )
    val argMatch = TT1OperatorSignatureMatcher.matchExceptOverloadedSig(args)

    assert(argMatch.contains(asTlaType1(fn.typeTag)))

  }

}
