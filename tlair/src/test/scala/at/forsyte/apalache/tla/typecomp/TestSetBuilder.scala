package at.forsyte.apalache.tla.typecomp

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.TlaSetOper
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import scalaz.unused

import scala.collection.immutable.SortedMap

@RunWith(classOf[JUnitRunner])
class TestSetBuilder extends BuilderTest {

  test("polyTest") {
    val t = VarT1("c")

    def n_a = builder.name("a", t)

    val r = build(builder.enumSet(n_a, n_a))

    assert(r.typeTag == Typed(SetT1(t)))

  }

  test("enumSet") {
    type T = Seq[TBuilderInstruction]
    def mkWellTyped(n: Int)(tt: TlaType1): T =
      (1 to n).map { i => builder.name(s"x$i", tt) }
    def mkIllTyped(n: Int)(tt: TlaType1): Seq[T] =
      if (n > 1)
        Seq(
            builder.name("x1", InvalidTypeMethods.differentFrom(tt)) +: (2 to n).map { i =>
              builder.name(s"x$i", tt)
            }
        )
      else Seq.empty

    def resultIsExpected(n: Int) = expectEqTyped[TlaType1, T](
        TlaSetOper.enumSet,
        mkWellTyped(n),
        ToSeq.variadic,
        tt => SetT1(tt),
    )

    def run(tparam: TlaType1) = {
      (1 to 5).forall { n =>
        runVariadic(
            builder.enumSet,
            mkWellTyped(n),
            mkIllTyped(n),
            resultIsExpected(n),
        )(tparam)
      }
    }

    checkRun(Generators.singleTypeGen)(run)

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.enumSet())
    }
  }

  test("emptySet") {

    def run(tt: TlaType1): Boolean = {
      val empty: TlaEx = builder.emptySet(tt)

      assert(
          empty.eqTyped(
              OperEx(TlaSetOper.enumSet)(Typed(SetT1(tt)))
          )
      )
      true
    }

    checkRun(Generators.singleTypeGen)(run)
  }

  test("(not)in") {

    type T = (TBuilderInstruction, TBuilderInstruction)

    def mkWellTyped(tt: TlaType1): T =
      (
          builder.name("x", tt),
          builder.name("S", SetT1(tt)),
      )
    def mkIllTyped(tt: TlaType1): Seq[T] =
      Seq(
          (
              builder.name("x", tt),
              builder.name("S", InvalidTypeMethods.notSet),
          ),
          (
              builder.name("x", InvalidTypeMethods.differentFrom(tt)),
              builder.name("S", SetT1(tt)),
          ),
      )

    def resultIsExpected(op: TlaSetOper) = expectEqTyped[TlaType1, T](
        op,
        mkWellTyped,
        ToSeq.binary,
        _ => BoolT1,
    )

    def run(
        op: TlaSetOper,
        method: (TBuilderInstruction, TBuilderInstruction) => TBuilderInstruction,
      )(tt: TlaType1): Boolean =
      runBinary(
          method,
          mkWellTyped,
          mkIllTyped,
          resultIsExpected(op),
      )(tt)

    checkRun(Generators.singleTypeGen)(run(TlaSetOper.in, builder.in))
    checkRun(Generators.singleTypeGen)(run(TlaSetOper.notin, builder.notin))
  }

  test("cap/cup/setminus") {

    type T = (TBuilderInstruction, TBuilderInstruction)

    def mkWellTyped(tt: TlaType1): T =
      (
          builder.name("S", SetT1(tt)),
          builder.name("T", SetT1(tt)),
      )
    def mkIllTyped(tt: TlaType1): Seq[T] =
      Seq(
          (
              builder.name("S", InvalidTypeMethods.notSet),
              builder.name("T", SetT1(tt)),
          ),
          (
              builder.name("S", SetT1(InvalidTypeMethods.differentFrom(tt))),
              builder.name("T", SetT1(tt)),
          ),
      )

    def resultIsExpected(op: TlaSetOper) = expectEqTyped[TlaType1, T](
        op,
        mkWellTyped,
        ToSeq.binary,
        tt => SetT1(tt),
    )

    def run(
        op: TlaSetOper,
        method: (TBuilderInstruction, TBuilderInstruction) => TBuilderInstruction,
      )(tt: TlaType1): Boolean =
      runBinary(
          method,
          mkWellTyped,
          mkIllTyped,
          resultIsExpected(op),
      )(tt)

    checkRun(Generators.singleTypeGen)(run(TlaSetOper.cap, builder.cap))
    checkRun(Generators.singleTypeGen)(run(TlaSetOper.cup, builder.cup))
    checkRun(Generators.singleTypeGen)(run(TlaSetOper.setminus, builder.setminus))
  }

  test("union") {

    type T = TBuilderInstruction

    def mkWellTyped(tt: TlaType1): T =
      builder.name("S", SetT1(SetT1(tt)))
    def mkIllTyped(@unused tt: TlaType1): Seq[T] =
      Seq(
          builder.name("S", InvalidTypeMethods.notSet),
          builder.name("S", SetT1(InvalidTypeMethods.notSet)),
      )

    def resultIsExpected = expectEqTyped[TlaType1, T](
        TlaSetOper.union,
        mkWellTyped,
        ToSeq.unary,
        tt => SetT1(tt),
    )

    checkRun(Generators.singleTypeGen)(
        runUnary(
            builder.union,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

  }

  test("filter") {

    type T = (TBuilderInstruction, TBuilderInstruction, TBuilderInstruction)
    type TParam = (TlaType1, Seq[TlaType1])

    def mkWellTyped(tparam: TParam): T = {
      val (t, ts) = tparam
      varInSet((_, _, builder.name("p", BoolT1)))(0, t, ts)
    }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      val (t, ts) = tparam
      Seq(
          varInSet((_, _, builder.name("p", InvalidTypeMethods.notBool)))(0, t, ts),
          (
              builder.name("x", t),
              builder.name("S", InvalidTypeMethods.notSet),
              builder.name("p", BoolT1),
          ),
      ) :++
        ts.indices.map { badIndex =>
          val names = ts.zipWithIndex.map { case (tj, j) =>
            builder.name(s"x$j", if (j == badIndex) InvalidTypeMethods.differentFrom(tj) else tj)
          }
          (
              builder.tuple(names: _*),
              builder.name(s"S", SetT1(TupT1(ts: _*))),
              builder.name("p", BoolT1),
          )
        }
    }

    val resultIsExpected = expectEqTyped[TParam, T](
        TlaSetOper.filter,
        mkWellTyped,
        ToSeq.ternary,
        { case (tt, ts) => SetT1(if (ts.isEmpty) tt else TupT1(ts: _*)) },
    )

    checkRun(Generators.typeAndSeqGen)(
        runTernary(
            builder.filter,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    assertThrowsBoundVarIntroductionTernary(builder.filter)
    assertThrowsBoundVarIntroductionTernaryTupled(builder.filter)
  }

  test("map") {
    type T = (TBuilderInstruction, Seq[TBuilderInstruction])
    type TParam = (TlaType1, Seq[(TlaType1, Seq[TlaType1])])

    def mkWellTyped(tparam: TParam): T = {
      val (t, ts) = tparam
      (
          builder.name("e", t),
          ts.zipWithIndex.flatMap { case ((ti, tsi), i) => varInSet(Seq(_, _))(i, ti, tsi) },
      )
    }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      val (t, ts) = tparam
      ts.indices.map { badIdx =>
        (
            builder.name("e", t),
            ts.zipWithIndex.flatMap { case ((ti, tsi), i) =>
              if (i == badIdx) {
                if (tsi.isEmpty)
                  Seq(
                      builder.name(s"x$i", InvalidTypeMethods.differentFrom(ti)),
                      builder.name(s"S$i", SetT1(ti)),
                  )
                else {
                  val names = tsi.zipWithIndex.map { case (tij, j) =>
                    builder.name(s"x${i}_$j", if (j == 0) InvalidTypeMethods.differentFrom(tij) else tij)
                  }
                  Seq(
                      builder.tuple(names: _*),
                      builder.name(s"S$i", SetT1(TupT1(tsi: _*))),
                  )
                }
              } else {
                varInSet(Seq(_, _))(i, ti, tsi)
              }
            },
        )
      } :+
        (
            builder.name("e", t),
            ts.zipWithIndex.flatMap { case ((ti, _), i) =>
              Seq(
                  builder.name(s"x$i", ti),
                  builder.name(s"S$i", InvalidTypeMethods.notSet),
              )
            },
        )

    }

    val resultIsExpected = expectEqTyped[TParam, T](
        TlaSetOper.map,
        mkWellTyped,
        ToSeq.variadicWithDistinguishedFirst,
        { case (t, _) => SetT1(t) },
    )

    checkRun(Generators.typeAndNonemptySeqOfTypeAndSeqGen)(
        runVariadicWithDistinguishedFirst(
            builder.mapMixed,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.mapMixed(builder.name("x", IntT1)))
    }

    // test fail on n = 1
    assertThrows[IllegalArgumentException] {
      build(builder.mapMixed(builder.name("x", IntT1), builder.name("x", IntT1)))
    }

    // throws on duplicate vars
    assertThrows[IllegalArgumentException] {
      build(
          builder.mapMixed(
              builder.name("e", IntT1),
              builder.name(s"x1", IntT1),
              builder.name(s"S1", SetT1(IntT1)),
              builder.name(s"x1", IntT1),
              builder.name(s"S2", SetT1(IntT1)),
          )
      )
    }

    assertThrowsBoundVarIntroductionTernary { case (variable, set, expr) =>
      builder.mapMixed(expr, variable, set)
    }

    assertThrowsBoundVarIntroductionTernaryTupled { case (variable, set, expr) =>
      builder.mapMixed(expr, variable, set)
    }

    // throws on shadowing: multi-arity
    assertThrows[TBuilderScopeException] {
      build(
          // { \E y \in T: TRUE : x \in S, y \in T }
          builder.mapMixed(
              builder.exists(
                  builder.name("y", IntT1),
                  builder.name("T", SetT1(IntT1)),
                  builder.bool(true),
              ),
              builder.name("x", StrT1),
              builder.name("S", SetT1(StrT1)),
              builder.name("y", IntT1),
              builder.name("T", SetT1(IntT1)),
          )
      )
    }

    // throws on shadowing: multi-arity tupled
    assertThrows[TBuilderScopeException] {
      build(
          // { \E y \in T: TRUE : x \in S, y \in T }
          builder.mapMixed(
              builder.exists(
                  builder.name("y", IntT1),
                  builder.name("T", SetT1(IntT1)),
                  builder.bool(true),
              ),
              builder.name("a", StrT1),
              builder.name("A", SetT1(StrT1)),
              builder.tuple(builder.name("x", StrT1), builder.name("y", IntT1)),
              builder.name("ST", SetT1(TupT1(StrT1, IntT1))),
          )
      )
    }

    // does not throw on non-shadowing: multi-arity
    // { e: x \in S, y \in {z \in T: \E x \in S: TRUE} }
    build(
        builder.mapMixed(
            builder.name("e", StrT1),
            builder.name("x", StrT1),
            builder.name("S", SetT1(StrT1)),
            builder.name("y", IntT1),
            builder.filter(
                builder.name("z", IntT1),
                builder.name("T", SetT1(IntT1)),
                builder.exists(
                    builder.name("x", StrT1),
                    builder.name("S", SetT1(StrT1)),
                    builder.bool(true),
                ),
            ),
        )
    )

    // now for builder.map (not mapMixed)

    type T2 = (TBuilderInstruction, Seq[(TBuilderInstruction, TBuilderInstruction)])

    def mkWellTyped2(tparam: TParam): T2 = {
      val (t, ts) = tparam
      (
          builder.name("e", t),
          ts.zipWithIndex.map { case ((ti, tsi), i) =>
            varInSet(_ -> _)(i, ti, tsi)
          },
      )
    }

    def mkIllTyped2(tparam: TParam): Seq[T2] = {
      val (t, ts) = tparam
      ts.indices.map { badIdx =>
        (
            builder.name("e", t),
            ts.zipWithIndex.map { case ((ti, tsi), i) =>
              if (i == badIdx) {
                if (tsi.isEmpty)
                  builder.name(s"x$i", InvalidTypeMethods.differentFrom(ti)) ->
                    builder.name(s"S$i", SetT1(ti))
                else {
                  val names = tsi.zipWithIndex.map { case (tij, j) =>
                    builder.name(s"x${i}_$j", if (j == 0) InvalidTypeMethods.differentFrom(tij) else tij)
                  }
                  builder.tuple(names: _*) ->
                    builder.name(s"S$i", SetT1(TupT1(tsi: _*)))
                }
              } else {
                varInSet(_ -> _)(i, ti, tsi)
              }
            },
        )
      } :+ (
          builder.name("e", t),
          ts.zipWithIndex.map { case ((ti, _), i) =>
            builder.name(s"x$i", ti) ->
              builder.name(s"S$i", InvalidTypeMethods.notSet)
          },
      )
    }

    val resultIsExpected2 = expectEqTyped[TParam, T2](
        TlaSetOper.map,
        mkWellTyped2,
        ToSeq.variadicPairsWithDistinguishedFirst,
        { case (t, _) => SetT1(t) },
    )

    checkRun(Generators.typeAndNonemptySeqOfTypeAndSeqGen)(
        runVariadicWithDistinguishedFirst(
            builder.map,
            mkWellTyped2,
            mkIllTyped2,
            resultIsExpected2,
        )
    )

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.map(builder.name("x", IntT1)))
    }

    assertThrowsBoundVarIntroductionTernary { case (variable, set, expr) =>
      builder.map(expr, (variable, set))
    }

    assertThrowsBoundVarIntroductionTernaryTupled { case (variable, set, expr) =>
      builder.map(expr, (variable, set))
    }

    // throws on duplicate vars
    assertThrows[IllegalArgumentException] {
      build(
          builder.map(
              builder.name("e", IntT1),
              (
                  builder.name(s"x1", IntT1),
                  builder.name(s"S1", SetT1(IntT1)),
              ),
              (
                  builder.name(s"x1", IntT1),
                  builder.name(s"S2", SetT1(IntT1)),
              ),
          )
      )
    }

    // throws on duplicate vars
    assertThrows[IllegalArgumentException] {
      build(
          builder.map(
              builder.name("e", IntT1),
              (
                  builder.name(s"x1", IntT1),
                  builder.name(s"S1", SetT1(IntT1)),
              ),
              (
                  builder.tuple(builder.name(s"x1", IntT1), builder.name(s"x2", IntT1)),
                  builder.name(s"S2", SetT1(TupT1(IntT1, IntT1))),
              ),
          )
      )
    }

    // throws on shadowing: multi-arity
    assertThrows[TBuilderScopeException] {
      build(
          // { \E y \in T: TRUE : x \in S, y \in T }
          builder.map(
              builder.exists(
                  builder.name("y", IntT1),
                  builder.name("T", SetT1(IntT1)),
                  builder.bool(true),
              ),
              (builder.name("x", StrT1), builder.name("S", SetT1(StrT1))),
              (builder.name("y", IntT1), builder.name("T", SetT1(IntT1))),
          )
      )
    }

    // throws on shadowing: multi-arity tupled
    assertThrows[TBuilderScopeException] {
      build(
          // { \E y \in T: TRUE : x \in S, y \in T }
          builder.map(
              builder.exists(
                  builder.name("y", IntT1),
                  builder.name("T", SetT1(IntT1)),
                  builder.bool(true),
              ),
              builder.name("a", StrT1) ->
                builder.name("A", SetT1(StrT1)),
              builder.tuple(builder.name("x", StrT1), builder.name("y", IntT1)) ->
                builder.name("ST", SetT1(TupT1(StrT1, IntT1))),
          )
      )
    }

    // does not throw on non-shadowing: multi-arity
    // { e : x \in S, y \in {z \in T: \E x \in S: TRUE} }
    build(
        builder.map(
            builder.name("e", StrT1),
            (
                builder.name("x", StrT1),
                builder.name("S", SetT1(StrT1)),
            ),
            (
                builder.name("y", IntT1),
                builder.filter(
                    builder.name("z", IntT1),
                    builder.name("T", SetT1(IntT1)),
                    builder.exists(
                        builder.name("x", StrT1),
                        builder.name("S", SetT1(StrT1)),
                        builder.bool(true),
                    ),
                ),
            ),
        )
    )

  }

  test("funSet") {

    type T = (TBuilderInstruction, TBuilderInstruction)
    type TParam = (TlaType1, TlaType1)

    def mkWellTyped(tparam: TParam): T = {
      val (t1, t2) = tparam
      (
          builder.name("S", SetT1(t1)),
          builder.name("T", SetT1(t2)),
      )
    }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      val (t1, _) = tparam
      Seq(
          (
              builder.name("S", SetT1(t1)),
              builder.name("T", InvalidTypeMethods.notSet),
          )
      )
    }

    def resultIsExpected = expectEqTyped[TParam, T](
        TlaSetOper.funSet,
        mkWellTyped,
        ToSeq.binary,
        { case (t1, t2) => SetT1(FunT1(t1, t2)) },
    )

    checkRun(Generators.doubleTypeGen)(
        runBinary(
            builder.funSet,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )
  }

  test("recSet") {
    type T = Seq[TBuilderInstruction]

    type TParam = Seq[TlaType1]

    def mkWellTyped(tparam: TParam): T =
      tparam.zipWithIndex.flatMap { case (tt, i) =>
        Seq(
            builder.str(s"x$i"),
            builder.name(s"S$i", SetT1(tt)),
        )
      }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      Seq(
          tparam.zipWithIndex.flatMap { case (_, i) =>
            Seq(
                builder.str(s"x$i"),
                builder.name(s"S$i", InvalidTypeMethods.notSet),
            )
          }
      )
    }

    val resultIsExpected = expectEqTyped[TParam, T](
        TlaSetOper.recSet,
        mkWellTyped,
        ToSeq.variadic,
        { ts =>
          val map = ts.zipWithIndex.foldLeft(SortedMap.empty[String, TlaType1]) { case (m, (t, i)) =>
            m + (s"x$i" -> t)
          }
          SetT1(RecT1(map))
        },
    )

    checkRun(Generators.nonEmptySeqOfTypesGen)(
        runVariadic(
            builder.recSetMixed,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.recSetMixed())
    }

    // test fail on n = 1
    assertThrows[IllegalArgumentException] {
      build(builder.recSetMixed(builder.str("x")))
    }

    // test fail on repeated key
    assertThrows[IllegalArgumentException] {
      build(builder.recSetMixed(
              builder.str("k"),
              builder.name("S", SetT1(IntT1)),
              builder.str("k"),
              builder.name("T", SetT1(IntT1)),
          ))
    }

    // test fail on non-literal key
    assertThrows[IllegalArgumentException] {
      build(builder.recSetMixed(
              builder.name("k", StrT1),
              builder.name("S", SetT1(IntT1)),
          ))
    }

    // now for builder.map (not mapMixed)

    type T2 = Seq[(String, TBuilderInstruction)]

    def mkWellTyped2(tparam: TParam): T2 =
      tparam.zipWithIndex.map { case (tt, i) =>
        s"x$i" ->
          builder.name(s"S$i", SetT1(tt))
      }

    def mkIllTyped2(tparam: TParam): Seq[T2] = {
      Seq(
          tparam.zipWithIndex.map { case (_, i) =>
            s"x$i" ->
              builder.name(s"S$i", InvalidTypeMethods.notSet)
          }
      )
    }

    val resultIsExpected2 = expectEqTyped[TParam, T2](
        TlaSetOper.recSet,
        mkWellTyped2,
        ToSeq.variadicPairs,
        { ts =>
          val map = ts.zipWithIndex.foldLeft(SortedMap.empty[String, TlaType1]) { case (m, (t, i)) =>
            m + (s"x$i" -> t)
          }
          SetT1(RecT1(map))
        },
    )

    checkRun(Generators.nonEmptySeqOfTypesGen)(
        runVariadic(
            builder.recSet,
            mkWellTyped2,
            mkIllTyped2,
            resultIsExpected2,
        )
    )

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.recSet())
    }

    // test fail on repeated key
    assertThrows[IllegalArgumentException] {
      build(builder.recSet(
              ("k", builder.name("S", SetT1(IntT1))),
              ("k", builder.name("T", SetT1(IntT1))),
          ))
    }
  }

  test("seqSet") {
    type T = TBuilderInstruction

    def mkWellTyped(tt: TlaType1): T =
      builder.name("S", SetT1(tt))
    def mkIllTyped(@unused tt: TlaType1): Seq[T] =
      Seq(
          builder.name("S", InvalidTypeMethods.notSet)
      )

    def resultIsExpected = expectEqTyped[TlaType1, T](
        TlaSetOper.seqSet,
        mkWellTyped,
        ToSeq.unary,
        tt => SetT1(SeqT1(tt)),
    )

    checkRun(Generators.singleTypeGen)(
        runUnary(
            builder.seqSet,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )
  }

  test("subseteq") {

    type T = (TBuilderInstruction, TBuilderInstruction)

    def mkWellTyped(tt: TlaType1): T =
      (
          builder.name("S", SetT1(tt)),
          builder.name("T", SetT1(tt)),
      )
    def mkIllTyped(tt: TlaType1): Seq[T] =
      Seq(
          (
              builder.name("S", InvalidTypeMethods.notSet),
              builder.name("T", SetT1(tt)),
          ),
          (
              builder.name("S", SetT1(InvalidTypeMethods.differentFrom(tt))),
              builder.name("T", SetT1(tt)),
          ),
      )

    val resultIsExpected = expectEqTyped[TlaType1, T](
        TlaSetOper.subseteq,
        mkWellTyped,
        ToSeq.binary,
        _ => BoolT1,
    )

    checkRun(Generators.singleTypeGen)(
        runBinary(
            builder.subseteq,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

  }

  test("times") {
    type T = Seq[TBuilderInstruction]

    type TParam = Seq[TlaType1]

    def mkWellTyped(tparam: TParam): T =
      tparam.zipWithIndex.map { case (t, i) => builder.name(s"S$i", SetT1(t)) }

    def mkIllTyped(tparam: TParam): Seq[T] = {
      // assume |tparam| >= 2
      Seq(
          builder.name("S1", InvalidTypeMethods.notSet) +: tparam.tail.zipWithIndex.map { case (t, i) =>
            builder.name(s"S${i + 2}", t)
          }
      )
    }

    def resultIsExpected = expectEqTyped[TParam, T](
        TlaSetOper.times,
        mkWellTyped,
        ToSeq.variadic,
        tts => SetT1(TupT1(tts: _*)),
    )

    checkRun(Generators.seqOfTypesGenMinLenGen(2))(
        runVariadic(
            builder.times,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )

    // test fail on n = 0
    assertThrows[IllegalArgumentException] {
      build(builder.times())
    }

    // test fail on n = 1
    assertThrows[IllegalArgumentException] {
      build(builder.times(builder.name("x", SetT1(IntT1))))
    }
  }

  test("powSet") {

    type T = TBuilderInstruction

    def mkWellTyped(tt: TlaType1): T =
      builder.name("S", SetT1(tt))
    def mkIllTyped(@unused tt: TlaType1): Seq[T] =
      Seq(
          builder.name("S", InvalidTypeMethods.notSet)
      )

    def resultIsExpected = expectEqTyped[TlaType1, T](
        TlaSetOper.powerset,
        mkWellTyped,
        ToSeq.unary,
        tt => SetT1(SetT1(tt)),
    )

    checkRun(Generators.singleTypeGen)(
        runUnary(
            builder.powSet,
            mkWellTyped,
            mkIllTyped,
            resultIsExpected,
        )
    )
  }

}
