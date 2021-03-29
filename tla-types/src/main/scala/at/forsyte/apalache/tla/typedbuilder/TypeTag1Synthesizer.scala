package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir.{TlaEx, _}
import at.forsyte.apalache.tla.lir.oper.TlaOper
import at.forsyte.apalache.tla.lir.values._

/**
 * TagSynthesizer for the TlaType1 type system
 */
class TypeTag1Synthesizer extends TagSynthesizer {
  // Tag must be Typed, with a TlaType1 type
  override def validateTagDomain(tag: TypeTag): Unit = tag match {
    case Typed(_: TlaType1) => // all good
    case other =>
      throw new TypingException(
          s"Expecting a TlaType1 tag, found $other."
      )
  }

  // SFP can be anything Typed, OFP must be Typed(OperT1(...)) with an operator type
  // of the correct arity
  private def assertValidType1Tag(taggedParam: TaggedParameter): Unit = {
    // Note: we don't perform more sophisticated checks, e.g. making sure
    // simple parameters aren't actually higher-order, we just make sure OFP are
    // at least operator-typed, for now

    val pName = taggedParam.param.name

    taggedParam.paramTag match {
      case Typed(paramType: TlaType1) =>
        taggedParam.param match {
          case OperFormalParam(_, arity) =>
            paramType match {
              case OperT1(operArgTypes, _) =>
                val nArgsInType = operArgTypes.size
                if (nArgsInType != arity)
                  throw new TypingException(
                      s"Operator parameter $pName of arity $arity annotated with operator type of arity $nArgsInType."
                  )
              case _ =>
                throw new TypingException(
                    s"Operator parameter $pName not annotated with operator type."
                )
            }
          case _: SimpleFormalParam => ()
        }
      case x =>
        throw new TypingException(
            s"Expected a TlaType1 tag for $pName, found $x."
        )
    }
  }

  override def validateParameterTags(taggedParams: Traversable[TaggedParameter]): Unit =
    taggedParams foreach assertValidType1Tag

  // We offload signature matching to TT1OperatorSignatureMatcher
  override def synthesizeOperatorTag(oper: TlaOper, args: TlaEx*): TypeTag =
    TT1OperatorSignatureMatcher.matchSignature(oper, args) match {
      case Some(tt1) => Typed(tt1)
      case None =>
        throw new TypingException(
            s"Arguments [${args.mkString(",")}] to operator ${oper.name} do not match that operator's signature."
        )
    }

  override def synthesizeValueTag(v: TlaValue): TypeTag = {
    val tagType: TlaType1 = v match {
      case TlaInt(_)     => IntT1()
      case TlaDecimal(_) => RealT1()
      case TlaReal()     => RealT1()
      case TlaBool(_)    => BoolT1()
      case TlaStr(_)     => StrT1()

      case TlaBoolSet => SetT1(BoolT1())
      case TlaStrSet  => SetT1(StrT1())
      case TlaIntSet  => SetT1(IntT1())
      case TlaNatSet  => SetT1(IntT1())
      case TlaRealSet => SetT1(RealT1())
    }
    Typed(tagType)
  }

  override def synthesizeDeclarationTag(taggedParams: Traversable[TaggedParameter], bodyEx: TlaEx): TypeTag = {
    validateParameterTags(taggedParams)
    val paramTypes = taggedParams.toSeq map { case TaggedParameter(_, paramTag) => asTlaType1(paramTag) }
    validateTagDomain(bodyEx.typeTag)
    val bodyType = asTlaType1(bodyEx.typeTag)

    Typed(OperT1(paramTypes, bodyType))
  }
}
