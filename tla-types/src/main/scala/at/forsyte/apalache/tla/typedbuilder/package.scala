package at.forsyte.apalache.tla

import at.forsyte.apalache.tla.lir.{TlaType1, TypeTag, Typed, TypingException}
import at.forsyte.apalache.tla.typecheck.etc.{Substitution, TypeUnifier}

/**
 * Misc. helpful methods
 */
package object typedbuilder {

  /**
   * Unwrap a type tag of the form Typed(_: TlaType1) into TlaType1. If the tag does not match this pattern,
   * throw TypingException.
   *
   * @return the type that is stored in the tag
   */
  def asTlaType1(tag: TypeTag): TlaType1 = {
    tag match {
      case Typed(tt: TlaType1) => tt
      case _                   => throw new TypingException("Expected Typed(_: TlaType1), found: " + tag)
    }
  }

  // Performs unificaiton on 2 types with a fresh unifier
  def singleUnification(
      lhs: TlaType1, rhs: TlaType1, subst: Substitution = Substitution.empty
  ): Option[(Substitution, TlaType1)] = {
    (new TypeUnifier).unify(subst, lhs, rhs)
  }

}
