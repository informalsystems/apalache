package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.TlaOper

/**
 * A TagSynthesizer determines what flavor of TypeTag gets assigned to expressions in the TypedBuilder
 * expression-construction process.
 */
trait TagSynthesizer {

  /**
   * Throws TypingException if `tag` is not the correct
   * subtype of TypeTag (e.g. Untyped() when expecting Typed(_) )
   */
  def validateTagDomain(tag: TypeTag): Unit

  /**
   * Throws TypingException if `taggedParams` are not tagged correctly
   * (e.g. OperFormalParam tagged with Typed(IntT1()) instead of Typed(OperT1(_,_))
   */
  def validateParameterTags(taggedParams: Traversable[TaggedParameter]): Unit

  /**
   * Determines the appropriate type of OperEx( oper, args:_* ), from the requirements of
   * the operator and the types of the arguments
   */
  def synthesizeOperatorTag(oper: TlaOper, args: TlaEx*): TypeTag

  /**
   * Determines the appropriate type of ValEx(v), from the shape of v
   */
  def synthesizeValueTag(v: TlaValue): TypeTag

  /**
   * Determines the appropriate type of TlaOperDecl(_,_,_), from the tags of the
   * parameters and the tag of the body
   */
  def synthesizeDeclarationTag(taggedParams: Traversable[TaggedParameter], bodyEx: TlaEx): TypeTag
}

class UntypedSynthesizer extends TagSynthesizer {
  // Untyped doesn't care about types, so it doesn't complain on Typed(_) tags
  override def validateTagDomain(tag: TypeTag): Unit = ()
  // Untyped doesn't care about types, so it doesn't complain on Typed(_) tags
  override def validateParameterTags(taggedParams: Traversable[TaggedParameter]): Unit = ()

  override def synthesizeOperatorTag(oper: TlaOper, args: TlaEx*): TypeTag = Untyped()

  override def synthesizeValueTag(v: TlaValue): TypeTag = Untyped()

  override def synthesizeDeclarationTag(taggedParams: Traversable[TaggedParameter], bodyEx: TlaEx): TypeTag = Untyped()
}
