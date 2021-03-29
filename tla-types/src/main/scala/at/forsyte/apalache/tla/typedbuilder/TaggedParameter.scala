package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir.{
  BuilderError, FormalParam, LirError, OperFormalParam, OperT1, SimpleFormalParam, TlaType1, TypeTag, Typed,
  TypingException
}

/**
 * Annotates a FormalParam with a TypeTag
 * TODO?: Make FormalParam extend Typed[_]
 */
sealed case class TaggedParameter(param: FormalParam, paramTag: TypeTag)
