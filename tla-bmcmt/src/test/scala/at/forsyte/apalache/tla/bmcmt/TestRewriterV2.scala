package at.forsyte.apalache.tla.bmcmt

import at.forsyte.apalache.tla.bmcmt.rewriter2._
import at.forsyte.apalache.tla.lir.{TlaEx, ValEx}
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterEach, FunSuite}
import org.scalatest.junit.JUnitRunner
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.smt.SmtTools.False
import scalaz._
import scalaz.Scalaz._

@RunWith(classOf[JUnitRunner])
class TestRewriterV2 extends FunSuite with BeforeAndAfterEach {

  // Two basic rewriters. dummyrewriter just returns the expression unchanged, but doesn't throw,
  // while failingrewriter returns an Exception on any input
  val dummyrewriter = new Rewriter {
    override def rewriteUntilDone(ex: TlaEx): StateCompWithExceptions[TlaEx] =
      EitherT.right(ex)
  }

  val failingrewriter = new Rewriter {
    override def rewriteUntilDone(ex: TlaEx): StateCompWithExceptions[TlaEx] =
      EitherT.left(new Exception("Rewriter failed."))
  }

  // 1 rule instance for each rewriter type
  val ruleOk = new NegationRule(dummyrewriter)
  val ruleX = new NegationRule(failingrewriter)

  test("Good rewriter, good shape") {
    val ex = tla.not(tla.name("x"))

    val initState = RewritingState.init

    val (postState, result) = ruleOk.apply(ex).run(initState)

    // 1 cell was introduced, bindings are empty and 1 constraint (False) was added
    assert(postState.arena.cellCount == initState.arena.cellCount + 1)
    assert(postState.binding.toMap.isEmpty)
    assert(postState.constraints == Seq(False()))

    // the result is a Right (some arbitrary cell name)
    assert(result.isRight)

  }

  test("Good rewriter, bad shape") {
    val ex = tla.name("x")

    val initState = RewritingState.init

    val (postState, result) = ruleOk.apply(ex).run(initState)

    // Incompatible shape should have made it so that no part of the rewriting code gets executed, so
    // postState is initState
    assert(postState == initState)

    // Moreover, result should wrap a RewriterExpression, since the failure happened at the rule level
    val assertCond = result match {
      case -\/(_: RewriterException) => true
      case _                         => false
    }

    assert(assertCond)
  }

  test("Bad rewriter, good shape") {
    val ex = tla.not(tla.name("x"))

    val initState = RewritingState.init

    val (postState, result) = ruleX.apply(ex).run(initState)

    // Bad rewriter made it so that no part of the rewriting code after the recursive call
    // to the rewriter gets executed, so the state remains unchanged
    assert(postState == initState)

    // The result is an Exception, but it's not a RewriterException
    val assertCond = result match {
      case -\/(_: RewriterException) => false
      case -\/(_: Exception)         => true
      case _                         => false
    }

    assert(assertCond)
  }

  test("Bad rewriter, bad shape") {
    val ex = tla.name("x")

    val initState = RewritingState.init

    val (postState, result) = ruleX.apply(ex).run(initState)

    // Rewriting doesn't happen, so the state is unchanged
    assert(postState == initState)

    // Bad shape should trigger before the bad rewriter
    val assertCond = result match {
      case -\/(_: RewriterException) => true
      case _                         => false
    }

    assert(assertCond)
  }
}
