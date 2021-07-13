package at.forsyte.apalache.tla.bmcmt

import scalaz._
import scalaz.Scalaz._

package object rewriter2 {

  type RewritingComputation[T] = State[RewritingState, T]

  type StateCompWithExceptions[T] = EitherT[Exception, RewritingComputation, T]
}
