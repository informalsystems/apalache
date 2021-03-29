package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir.values._
import at.forsyte.apalache.tla.lir._

/**
 * <p>A self-typing builder for TLA expressions. </p>
 *
 * <p>Contains methods for constructing various types of [[TlaEx]] expressions, guaranteeing
 * correct arity where the arity of the associated [[oper.TlaOper TlaOper]] is fixed.
 * If the arguments have correct types, w.r.t. the operator expression being constructed,
 * the resulting expression will have a type consistent with the signature of the operator,
 * for the given arguments. </p>
 *
 * @author jkukovec
 */
class TypedBuilder(tagSynthesizer: TagSynthesizer) {

  /** Names and values */
  def name(nameString: String, typeTag: TypeTag): NameEx = {
    tagSynthesizer.validateTagDomain(typeTag)
    NameEx(nameString)(typeTag)
  }

  private def fromTlaValue(v: TlaValue): ValEx = {
    val typeTag = tagSynthesizer.synthesizeValueTag(v)
    ValEx(v)(typeTag)
  }

  def bigInt(value: BigInt): ValEx = fromTlaValue(TlaInt(value))
  def int(value: Int): ValEx = fromTlaValue(TlaInt(value))
  def decimal(value: BigDecimal): ValEx = fromTlaValue(TlaDecimal(value))
  def bool(value: Boolean): ValEx = fromTlaValue(TlaBool(value))
  def str(value: String): ValEx = fromTlaValue(TlaStr(value))

  /**
   * The set BOOLEAN.
   *
   * @return the value expression that corresponds to BOOLEAN.
   */
  def booleanSet(): ValEx = fromTlaValue(TlaBoolSet)

  /**
   * The set Int of all integers.
   *
   * @return the value expression that corresponds to Int.
   */
  def intSet(): ValEx = fromTlaValue(TlaIntSet)

  /**
   * The set Nat of all natural numbers.
   *
   * @return the value expression that corresponds to Nat.
   */
  def natSet(): ValEx = fromTlaValue(TlaNatSet)

  /** Declarations */

  def simpleParam(name: String, tag: TypeTag): TaggedParameter = {
    tagSynthesizer.validateTagDomain(tag)
    TaggedParameter(SimpleFormalParam(name), tag)
  }

  def operParam(name: String, arity: Int, tag: TypeTag): TaggedParameter = {
    tagSynthesizer.validateTagDomain(tag)
    TaggedParameter(OperFormalParam(name, arity), tag)
  }

  def declOp(name: String, body: TlaEx, params: TaggedParameter*): TlaOperDecl = {
    val declTag = tagSynthesizer.synthesizeDeclarationTag(params, body)
    TlaOperDecl(name, params.toList.map { _.param }, body)(declTag)
  }

  private def buildOperEx(
      oper: TlaOper, args: TlaEx*
  ): OperEx = {
    val operTag = tagSynthesizer.synthesizeOperatorTag(oper, args: _*)
    OperEx(oper, args: _*)(operTag)
  }

  def appDecl(decl: TlaOperDecl, args: TlaEx*): OperEx = {
    if (args.length == decl.formalParams.length) {
      val nameEx = name(decl.name, decl.typeTag)
      val newArgs = nameEx +: args
      buildOperEx(TlaOper.apply, newArgs: _*)
    } else {
      throw new IllegalArgumentException(
          "Operator %s of %d parameters is applied to %d arguments"
            .format(decl.name, decl.formalParams.length, args.length))
    }
  }

  /** TlaOper */
  def eql(lhs: TlaEx, rhs: TlaEx): OperEx = buildOperEx(TlaOper.eq, lhs, rhs)
  def neql(lhs: TlaEx, rhs: TlaEx): OperEx = buildOperEx(TlaOper.ne, lhs, rhs)
  def appOp(operEx: TlaEx, args: TlaEx*): OperEx = buildOperEx(TlaOper.apply, operEx +: args: _*)
  def choose(variable: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaOper.chooseUnbounded, variable, predicate)
  def choose(variable: TlaEx, set: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaOper.chooseBounded, variable, set, predicate)

  /**
   * Decorate a TLA+ expression with a label (a TLA+2 feature), e.g.,
   * lab(a, b) :: e decorates e with the label "lab" whose arguments are "a" and "b".
   * This method needs a type tag for `name` and `args`. The type of the expression itself is inherited from ex.
   *
   * @param ex   a TLA+ expression to decorate
   * @param name label identifier
   * @param args label arguments (also identifiers)
   * @return OperEx(TlaOper.label, ex, name as ValEx(TlaStr(_)), args as ValEx(TlaStr(_)))
   */
  def label(ex: TlaEx, name: String, args: String*): OperEx = {
    val nameAsVal = str(name)
    val argsAsVals = args.map(s => str(s))
    buildOperEx(TlaOper.label, ex +: nameAsVal +: argsAsVals: _*)
  }

  /** TlaBoolOper */
  def and(args: TlaEx*): OperEx = buildOperEx(TlaBoolOper.and, args: _*)

  def or(args: TlaEx*): OperEx = buildOperEx(TlaBoolOper.or, args: _*)

  def not(pred: TlaEx): OperEx = buildOperEx(TlaBoolOper.not, pred)

  def impl(lhs: TlaEx, rhs: TlaEx): OperEx = buildOperEx(TlaBoolOper.implies, lhs, rhs)

  def equiv(lhs: TlaEx, rhs: TlaEx): OperEx = buildOperEx(TlaBoolOper.equiv, lhs, rhs)

  def forall(variable: TlaEx, set: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaBoolOper.forall, variable, set, predicate)

  def forall(variable: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaBoolOper.forallUnbounded, variable, predicate)

  def exists(variable: TlaEx, set: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaBoolOper.exists, variable, set, predicate)

  def exists(variable: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaBoolOper.existsUnbounded, variable, predicate)

  /** TlaActionOper */
  def prime(name: TlaEx): OperEx =
    buildOperEx(TlaActionOper.prime, name)

  def primeEq(lhs: TlaEx, rhs: TlaEx): OperEx =
    eql(prime(lhs), rhs)

  def stutt(action: TlaEx, underscored: TlaEx): OperEx =
    buildOperEx(TlaActionOper.stutter, action, underscored)

  def nostutt(action: TlaEx, underscored: TlaEx): OperEx =
    buildOperEx(TlaActionOper.nostutter, action, underscored)

  def enabled(action: TlaEx): OperEx =
    buildOperEx(TlaActionOper.enabled, action)

  def unchanged(expr: TlaEx): OperEx =
    buildOperEx(TlaActionOper.unchanged, expr)

  def unchangedTup(args: TlaEx*): OperEx =
    unchanged(tuple(args: _*))

  def comp(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaActionOper.composition, lhs, rhs)

  /** TlaControlOper */
  def caseSplit(guard1: TlaEx, effect1: TlaEx, guardsAndEffectsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaControlOper.caseNoOther, guard1 +: effect1 +: guardsAndEffectsInterleaved: _*)

  def caseOther(effectOnOther: TlaEx, guard1: TlaEx, effect1: TlaEx, guardsAndEffectsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaControlOper.caseWithOther, effectOnOther +: guard1 +: effect1 +: guardsAndEffectsInterleaved: _*)

  def ite(condition: TlaEx, thenArm: TlaEx, elseArm: TlaEx): OperEx =
    buildOperEx(TlaControlOper.ifThenElse, condition, thenArm, elseArm)

  // [[LetIn]]
  def letIn(body: TlaEx, defs: TlaOperDecl*): LetInEx = {
    // TODO: Enforce type equality between operators declared in defs
    //  and their occurrences in body ?
    LetInEx(body, defs: _*)(body.typeTag)
  }

  /** TlaTempOper */
  def AA(variable: TlaEx, formula: TlaEx): OperEx =
    buildOperEx(TlaTempOper.AA, variable, formula)

  def EE(variable: TlaEx, formula: TlaEx): OperEx =
    buildOperEx(TlaTempOper.EE, variable, formula)

  def box(formula: TlaEx): OperEx =
    buildOperEx(TlaTempOper.box, formula)

  def diamond(formula: TlaEx): OperEx =
    buildOperEx(TlaTempOper.diamond, formula)

  def guarantees(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaTempOper.guarantees, lhs, rhs)

  def leadsTo(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaTempOper.leadsTo, lhs, rhs)

  def SF(underscored: TlaEx, action: TlaEx): OperEx =
    buildOperEx(TlaTempOper.strongFairness, underscored, action)

  def WF(underscored: TlaEx, action: TlaEx): OperEx =
    buildOperEx(TlaTempOper.weakFairness, underscored, action)

  /** TlaArithOper */

  def plus(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.plus, lhs, rhs)

  def minus(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.minus, lhs, rhs)

  def uminus(arg: TlaEx): OperEx =
    buildOperEx(TlaArithOper.uminus, arg)

  def mult(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.mult, lhs, rhs)

  def div(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.div, lhs, rhs)

  def mod(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.mod, lhs, rhs)

//  def rDiv(lhs: TlaEx, rhs: TlaEx): OperEx =
//    buildOperEx(TlaArithOper.realDiv, lhs, rhs)

  def exp(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.exp, lhs, rhs)

  def dotdot(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.dotdot, lhs, rhs)

  def lt(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.lt, lhs, rhs)

  def gt(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.gt, lhs, rhs)

  def le(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.le, lhs, rhs)

  def ge(lhs: TlaEx, rhs: TlaEx): OperEx =
    buildOperEx(TlaArithOper.ge, lhs, rhs)

  /** TlaFiniteSetOper */
  def card(set: TlaEx): OperEx =
    buildOperEx(TlaFiniteSetOper.cardinality, set)

  def isFin(set: TlaEx): OperEx =
    buildOperEx(TlaFiniteSetOper.isFiniteSet, set)

  /** TlaFunOper */
  def appFun(fun: TlaEx, arg: TlaEx): OperEx =
    buildOperEx(TlaFunOper.app, fun, arg)

  def dom(fun: TlaEx): OperEx =
    buildOperEx(TlaFunOper.domain, fun)

  def record(key1: TlaEx, value1: TlaEx, keysAndValuesInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaFunOper.enum, key1 +: value1 +: keysAndValuesInterleaved: _*)

  def except(fun: TlaEx, key1: TlaEx, value1: TlaEx, keysAndValuesInterleaved: TlaEx*): OperEx = {
    val allArgs = fun +: key1 +: value1 +: keysAndValuesInterleaved
    allArgs.zipWithIndex foreach {
      case (ex, i) if i % 2 == 1 =>
        ex match {
          case OperEx(TlaFunOper.tuple, _ @_*) => // all good
          case _ =>
            throw new IllegalArgumentException(
                s"Malformed domain arguments to EXCEPT - expecting a tuple at position $i, found $ex"
            )
        }
      case _ => // all good
    }
    buildOperEx(TlaFunOper.except, allArgs: _*)
  }

  def funDef(mapExpr: TlaEx, var1: TlaEx, set1: TlaEx, varsAndSetsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaFunOper.funDef, mapExpr +: var1 +: set1 +: varsAndSetsInterleaved: _*)

  def recFunDef(mapExpr: TlaEx, var1: TlaEx, set1: TlaEx, varsAndSetsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaFunOper.recFunDef, mapExpr +: var1 +: set1 +: varsAndSetsInterleaved: _*)

  def recFunRef(typeTag: TypeTag): OperEx = {
    tagSynthesizer.validateTagDomain(typeTag)
    OperEx(TlaFunOper.recFunRef)(typeTag)
  }

  def tuple(args: TlaEx*): OperEx =
    buildOperEx(TlaFunOper.tuple, args: _*)

  def emptySequence(typeTag: TypeTag): OperEx = {
    tagSynthesizer.validateTagDomain(typeTag)
    typeTag match {
      case Typed(tt1: TlaType1) =>
        tt1 match {
          case _: SeqT1 => // all good
          case t =>
            throw new TypingException(
                s"Attempting to build an empty sequence with non-sequence type $t."
            )
        }
      case _ => // all good
    }
    OperEx(TlaFunOper.tuple)(typeTag)
  }

  def sequence(firstArg: TlaEx, args: TlaEx*): OperEx = {
    val asTuple = tuple(firstArg +: args: _*)
    val tt = asTuple.typeTag
    val sequenceTag = tt match {
      case Typed(TupT1(tupArgs @ _*)) =>
        // We make sure the tuple type is a valid sequence type, i.e. all args are unifiable
        // tupArgs cannot be empty
        val unifiedType = tupArgs reduceLeft [TlaType1] { case (argT1, argT2) =>
          singleUnification(argT1, argT2) match {
            case Some((_, unifT)) => unifT
            case None =>
              throw new TypingException(
                  s"Cannot construct a sequence type from argument types $tupArgs: No unifying type."
              )
          }
        }
        Typed(SeqT1(unifiedType))
      case otherTag => otherTag
    }
    asTuple.withTag(sequenceTag)
  }

  /** TlaSeqOper */
  def append(seq: TlaEx, elem: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.append, seq, elem)

  def concat(leftSeq: TlaEx, rightSeq: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.concat, leftSeq, rightSeq)

  def head(seq: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.head, seq)

  def tail(seq: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.tail, seq)

  def len(seq: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.len, seq)

  /**
   * Get a subsequence of S, that is, SubSeq(S, from, to)
   *
   * @param seq       a sequence, e.g., constructed with tla.tuple
   * @param fromIndex the first index of the subsequene, greater or equal to 1
   * @param toIndex   the last index of the subsequence, not greater than Len(S)
   * @return the expression that corresponds to SubSeq(S, from, to)
   */
  def subseq(seq: TlaEx, fromIndex: TlaEx, toIndex: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.subseq, seq, fromIndex, toIndex)

  /**
   * Get the subsequence of S that consists of the elements matching a predicate.
   *
   * @param seq  a sequence
   * @param test a predicate, it should be an action name
   * @return the expression that corresponds to SelectSeq(S, test)
   */
  def selectseq(seq: TlaEx, test: TlaEx): OperEx =
    buildOperEx(TlaSeqOper.selectseq, seq, test)

  /** TlaSetOper */
  def enumSet(arg1: TlaEx, moreArgs: TlaEx*): OperEx =
    buildOperEx(TlaSetOper.enumSet, arg1 +: moreArgs: _*)

  def emptySet(typeTag: TypeTag): OperEx = {
    tagSynthesizer.validateTagDomain(typeTag)
    typeTag match {
      case Typed(tt1: TlaType1) =>
        tt1 match {
          case _: SetT1 => // all good
          case t =>
            throw new TypingException(
                s"Attempting to build an empty set with non-set type $t."
            )
        }
      case _ => // all good
    }
    OperEx(TlaSetOper.enumSet)(typeTag)
  }

  def in(elem: TlaEx, set: TlaEx): OperEx =
    buildOperEx(TlaSetOper.in, elem, set)

  def notin(elem: TlaEx, set: TlaEx): OperEx =
    buildOperEx(TlaSetOper.notin, elem, set)

  def cap(leftSet: TlaEx, rightSet: TlaEx): OperEx =
    buildOperEx(TlaSetOper.cap, leftSet, rightSet)

  def cup(leftSet: TlaEx, rightSet: TlaEx): OperEx =
    buildOperEx(TlaSetOper.cup, leftSet, rightSet)

  def union(set: TlaEx): OperEx =
    buildOperEx(TlaSetOper.union, set)

  def filter(variable: TlaEx, set: TlaEx, predicate: TlaEx): OperEx =
    buildOperEx(TlaSetOper.filter, variable, set, predicate)

  def map(mapExpr: TlaEx, var1: TlaEx, set1: TlaEx, varsAndSetsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaSetOper.map, mapExpr +: var1 +: set1 +: varsAndSetsInterleaved: _*)

  def funSet(fromSet: TlaEx, toSet: TlaEx): OperEx =
    buildOperEx(TlaSetOper.funSet, fromSet, toSet)

  def recSet(varsAndSetsInterleaved: TlaEx*): OperEx =
    buildOperEx(TlaSetOper.recSet, varsAndSetsInterleaved: _*)

  def seqSet(set: TlaEx): OperEx =
    buildOperEx(TlaSetOper.seqSet, set)

  def subseteq(leftSet: TlaEx, rightSet: TlaEx): OperEx =
    buildOperEx(TlaSetOper.subseteq, leftSet, rightSet)

  def setminus(leftSet: TlaEx, rightSet: TlaEx): OperEx =
    buildOperEx(TlaSetOper.setminus, leftSet, rightSet)

  def times(sets: TlaEx*): OperEx =
    buildOperEx(TlaSetOper.times, sets: _*)

  def powSet(set: TlaEx): OperEx =
    buildOperEx(TlaSetOper.powerset, set)

  // tlc
//  def tlcAssert(assertion: TlaEx, errorMessage: String): OperEx = {
//    buildOperEx(TlcOper.assert, assertion, BuilderVal(TlaStr(errorMessage)))
//  }
//
//  def primeInSingleton(nameToPrime: TlaEx, onlySetElem: TlaEx): OperEx = {
//    in(prime(nameToPrime), enumSet(onlySetElem))
//  }
//
//  /**
//   * The TLC operator that creates a singleton function: key :> value.
//   */
//  def colonGreater(key: TlaEx, value: TlaEx): OperEx = {
//    buildOperEx(TlcOper.colonGreater, key, value)
//  }
//
//  /**
//   * The TLC operator that concatenates two functions: fun1 @@ fun2.
//   *
//   * @param lhs function on the left-hand side
//   * @param rhs function on the right-hand side
//   * @return a new function that operates on the joint domain of lhs and rhs
//   */
//  def atat(lhs: TlaEx, rhs: TlaEx): OperEx = {
//    buildOperEx(TlcOper.atat, lhs, rhs)
//  }
//
//  /**
//   * Produce a function out of a sequence of keys and values, that is, key_1 :> value_1 @@ ... @@ key_k :> value_k.
//   *
//   * TODO: this method introduces an intermediate builder expression, so it cannot be used to construct a typed expression.
//   *
//   * @param args an alternating list of keys and values
//   */
//  def atatInterleaved(args: TlaEx*): OperEx = {
//    if (args.isEmpty) {
//      buildOperEx(TlcOper.atat)
//    } else {
//      val kvs = args.sliding(2, 2).toList
//      val pairs = kvs.map(p => buildOperEx(TlcOper.colonGreater, p.head, p(1)))
//      buildOperEx(TlcOper.atat, pairs: _*)
//    }
//  }
//
//  // apalache operators
//  @deprecated("This operator introduces an old-style apalache annotation. It will be removed soon.")
//  def withType(expr: TlaEx, typeAnnot: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.withType, expr, typeAnnot)
//  }
//
//  def assign(lhs: TlaEx, rhs: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.assign, lhs, rhs)
//  }
//
//  def assignPrime(leftName: TlaEx, rightExpr: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.assign, prime(leftName), rightExpr)
//  }
//
//  def apalacheExpand(ex: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.expand, ex)
//  }
//
//  def apalacheSkolem(ex: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.skolem, ex)
//  }
//
//  def apalacheConstCard(ex: TlaEx): OperEx = {
//    buildOperEx(ApalacheOper.constCard, ex)
//  }
}
