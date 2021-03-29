package at.forsyte.apalache.tla.typedbuilder

import at.forsyte.apalache.tla.lir.oper._
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.values.{TlaInt, TlaStr}
import at.forsyte.apalache.tla.typecheck.etc.Substitution

/**
 * TT1OperatorSignatureMatcher provides methods for computing types of expressions, that
 * are the result of applying built-in operators to typed arguments
 */
object TT1OperatorSignatureMatcher {
  // Generates fresh, unique VarT1 values on demand
  private class TypeVarGenerator {
    private var idx: Int = 0

    def getUnique: VarT1 = {
      val ret = VarT1(idx)
      idx += 1
      ret
    }

    def getNUnique(n: Int): Seq[VarT1] = Seq.fill(n) {
      getUnique
    }
  }

  type SignatureMatch = Option[TlaType1]

  private val typeVarGenerator: TypeVarGenerator = new TypeVarGenerator

  /**
   * Computes the type of oper(args[1], ..., args[n]), if a valid type exists.
   *
   * More specifically, given a built in operator `oper` and a sequence of arguments `args`,
   * performs the following: If there exists a primitive schema `s` of the shape (T1, ... , Tn) => T,
   * such that `oper` is assigned the schema `s` or `oper` is assigned a complex schema containing s,
   * args is a sequence of length `n` of expressions with types S1, ..., Sn , and there exists
   * a substitution `sub`, for which Si and Ti unify (i.e. sub(Si) = sub(Ti)),
   * returns Some(sub(T)), otherwise returns None.
   */
  def matchSignature(oper: TlaOper, args: Seq[TlaEx]): SignatureMatch = oper match {
    /** Logic */
    case TlaOper.eq | TlaOper.ne                                   => matchBinaryTestSig(args)
    case TlaBoolOper.and | TlaBoolOper.or                          => matchVariadicBoolOpSig(args)
    case TlaBoolOper.implies | TlaBoolOper.equiv                   => matchVariadicBoolOpSig(args)
    case TlaBoolOper.not                                           => matchVariadicBoolOpSig(args)
    case TlaBoolOper.forall | TlaBoolOper.exists                   => matchBoundedQuantifierSig(args)
    case TlaBoolOper.forallUnbounded | TlaBoolOper.existsUnbounded => matchUnboundedQuantifierSig(args)

    /** Choose */
    case TlaOper.chooseBounded   => matchBoundedChoiceSig(args)
    case TlaOper.chooseUnbounded => matchUnboundedChoiceSig(args)

    /** Action */
    case TlaActionOper.prime                             => matchEndomorphismSig(args)
    case TlaActionOper.unchanged                         => matchEndomorphismSig(args)
    case TlaActionOper.enabled                           => matchVariadicBoolOpSig(args)
    case TlaActionOper.nostutter | TlaActionOper.stutter => matchStutterSig(args)
    case TlaActionOper.composition                       => matchVariadicBoolOpSig(args)

    /** Temporal */
    case TlaTempOper.box | TlaTempOper.diamond                 => matchVariadicBoolOpSig(args)
    case TlaTempOper.weakFairness | TlaTempOper.strongFairness => matchTemporalWithVarSig(args)
    case TlaTempOper.leadsTo | TlaTempOper.guarantees          => matchVariadicBoolOpSig(args)
    case TlaTempOper.AA | TlaTempOper.EE                       => matchTemporalWithVarSig(args)

    /** Arithmetic */
    case TlaArithOper.plus | TlaArithOper.minus | TlaArithOper.mult => matchVariadicArithOperSig(args)
    case TlaArithOper.div | TlaArithOper.mod | TlaArithOper.exp     => matchVariadicArithOperSig(args)
    case TlaArithOper.uminus                                        => matchVariadicArithOperSig(args)
    case TlaArithOper.dotdot                                        => matchDotdotSig(args)
    case TlaArithOper.le | TlaArithOper.lt                          => matchIntInequalitySig(args)
    case TlaArithOper.ge | TlaArithOper.gt                          => matchIntInequalitySig(args)
    //    case TlaArithOper.realDiv => TODO

    /** Sets */
    case TlaSetOper.in | TlaSetOper.notin                      => matchSetMembershipSig(args)
    case TlaSetOper.subseteq                                   => matchSetCmpSig(args)
    case TlaSetOper.cup | TlaSetOper.cap | TlaSetOper.setminus => matchBinarySetManipSig(args)
    case TlaSetOper.enumSet                                    => matchSetEnumSig(args)
    case TlaSetOper.filter                                     => matchSetFilterSig(args)
    case TlaSetOper.map                                        => matchSetMapSig(args)
    case TlaSetOper.powerset                                   => matchPowSetSig(args)
    case TlaSetOper.union                                      => matchBigUnionSig(args)

    /** Finite sets */
    case TlaFiniteSetOper.isFiniteSet => matchSetPredicateSig(args)
    case TlaFiniteSetOper.cardinality => matchCardinalitySig(args)

    /** Functions */
    case TlaSetOper.funSet => matchFunSetSig(args)
    case TlaFunOper.funDef => matchFunDefSig(args)

    /** Records */
    case TlaFunOper.enum   => matchRecEnumSig(args)
    case TlaSetOper.recSet => matchRecSetSig(args)

    /** Tuples */
    case TlaSetOper.times => matchCartesianProdSig(args)
    // Tuple always makes a tuple, never a sequence. A special builder method exists to
    // construct sequences and "cast" the tuple type to Seq
    case TlaFunOper.tuple => matchTupleSig(args)

    /** Control */
    case TlaControlOper.ifThenElse    => matchIteSig(args)
    case TlaControlOper.caseWithOther => matchCaseOtherSig(args)
    case TlaControlOper.caseNoOther   => matchCaseNoOtherSig(args)

    /** Oper */
    case TlaOper.apply => matchOperAppSig(args)

    /** Sequences */
    case TlaSeqOper.concat    => matchConcatSig(args)
    case TlaSeqOper.head      => matchHeadSig(args)
    case TlaSeqOper.tail      => matchTailSig(args)
    case TlaSeqOper.len       => matchLenSig(args)
    case TlaSetOper.seqSet    => matchSeqSetSig(args)
    case TlaSeqOper.append    => matchAppendSig(args)
    case TlaSeqOper.subseq    => matchSubSeqSig(args)
    case TlaSeqOper.selectseq => matchSelectSeqSig(args)

    /** Overloaded */
    case TlaFunOper.app    => matchApplicativeAppSig(args)
    case TlaFunOper.except => matchExceptOverloadedSig(args)
    case TlaFunOper.domain => matchApplicativeDomainSig(args)

    /** Recursion */
    case TlaFunOper.recFunDef => matchFunDefSig(args) // same signature as non-recursive function definitions
    //    case TlaFunOper.recFunRef => resolved directly in builder via type-hint

    // TODO
    /** APALACHE */

    // TODO
    /** TLC */
    //    case TlcOper.permutations => OperT(TupT(SetT(t)), SetT(FunT(t, t)))
    //    case TlcOper.atat => OperT(TupT(FunT(t1, t2), FunT(t1, t2)), FunT(t1, t2))
    //    case TlcOper.colonGreater => OperT(TupT(t1, t2), FunT(t1, t2))
    //    case TlcOper.print => OperT(TupT(StrT, t), t)

    /** LABEL */
    case TlaOper.label => matchLabelSig(args)

    case o =>
      throw new NotImplementedError(
          s"Signature matching for operator ${o.name} is not implemented yet."
      )
  }

  // A sequence of arguments a1: T1, ... , an: Tn
  // defines an operator type (T1, ... , Tn) => T, for some fresh T
  private def getArgImplied(args: Seq[TlaEx]): OperT1 = {
    val t = typeVarGenerator.getUnique
    OperT1(
        args map { _.typeTag } map { asTlaType1 },
        t
    )
  }

  // For most operators, where the signature is explicitly known,
  // it suffices to unify the operator given by the signature, with the
  // operator implied by the arguments.
  // The unconstrained codomain type of the implicit operator gets concretized
  // by unification.
  private def matchArgImplied(tt1: TlaType1, args: Seq[TlaEx]): SignatureMatch = {
    val impliedT = getArgImplied(args)

    val unified = singleUnification(tt1, impliedT)
    unified match {
      case Some((_, tt1)) =>
        tt1 match {
          // If two operators unify, the result is an operator.
          // The operator application will have the codomain value
          case OperT1(_, ret) => Some(ret)
          case _              => None
        }
      case _ => None
    }
  }

  /**
   * * * * * *
   * METHODS *
   * * * * * *
   */

  // \A T . (T,T) => Bool
  def matchBinaryTestSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, t),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . Bool^n => Bool
  def matchVariadicBoolOpSig(args: Seq[TlaEx]): SignatureMatch = {
    val operT =
      OperT1(
          Seq.fill(args.length)(BoolT1()),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T,Set(T),Bool) => Bool
  def matchBoundedQuantifierSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, SetT1(t), BoolT1()),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T,Bool) => Bool
  def matchUnboundedQuantifierSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, BoolT1()),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T,Set(T),Bool) => T
  def matchBoundedChoiceSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, SetT1(t), BoolT1()),
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T,Bool) => T
  def matchUnboundedChoiceSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, BoolT1()),
          t
      )
    matchArgImplied(operT, args)
  }

  // Int^n => Int
  def matchVariadicArithOperSig(args: Seq[TlaEx]): SignatureMatch = {
    val operT =
      OperT1(
          Seq.fill(args.length)(IntT1()),
          IntT1()
      )
    matchArgImplied(operT, args)
  }

  // (Int, Int) => Set(Int)
  def matchDotdotSig(args: Seq[TlaEx]): SignatureMatch = {
    val operT =
      OperT1(
          Seq(IntT1(), IntT1()),
          SetT1(IntT1())
      )
    matchArgImplied(operT, args)
  }

  // (Int, Int) => Bool
  def matchIntInequalitySig(args: Seq[TlaEx]): SignatureMatch = {
    val operT =
      OperT1(
          Seq(IntT1(), IntT1()),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T, Set(T)) => Bool
  def matchSetMembershipSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, SetT1(t)),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(T), Set(T)) => Bool
  def matchSetCmpSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t), SetT1(t)),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(T), Set(T)) => Set(T)
  def matchBinarySetManipSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t), SetT1(t)),
          SetT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . T^n => Set(T)
  def matchSetEnumSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq.fill(args.length)(t),
          SetT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T, Set(T), Bool) => Set(T)
  def matchSetFilterSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, SetT1(t), BoolT1()),
          SetT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T,T_1,...,T_n . (T, T_1, Set(T_1),..., T_m, Set(T_m)) => Set(T)
  def matchSetMapSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs >= 3 && nArgs % 2 == 1)
    val n = (nArgs - 1) / 2
    val t = typeVarGenerator.getUnique
    val ts = typeVarGenerator.getNUnique(n)
    val allPairs = ts flatMap { x => Seq(x, SetT1(x)) }
    val operT =
      OperT1(
          t +: allPairs,
          SetT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(T)) => Set(Set(T))
  def matchPowSetSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t)),
          SetT1(SetT1(t))
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(Set(T))) => Set(T)
  def matchBigUnionSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(SetT1(t))),
          SetT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(T)) => Bool
  def matchSetPredicateSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t)),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Set(T)) => Int
  def matchCardinalitySig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t)),
          IntT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T) => T
  def matchEndomorphismSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t),
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Bool,T) => Bool
  def matchStutterSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(BoolT1(), t),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T,Bool) => Bool
  def matchTemporalWithVarSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(t, BoolT1()),
          BoolT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T_1,T_2 . (Set(T_1), Set(T_2)) => Set(T_1 -> T2)
  def matchFunSetSig(args: Seq[TlaEx]): SignatureMatch = {
    val Seq(t1, t2) = typeVarGenerator.getNUnique(2)
    val operT =
      OperT1(
          Seq(SetT1(t1), SetT1(t2)),
          SetT1(FunT1(t1, t2))
      )
    matchArgImplied(operT, args)
  }

  // \A T,T_1,...,T_n . (T, T_1, Set(T_1),..., T_m, Set(T_m)) =>
  // (case n = 1) : T_1 -> T
  // (case n > 1) : (T_1, ..., T_n) -> T
  def matchFunDefSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs >= 3 && nArgs % 2 == 1)
    val n = (nArgs - 1) / 2
    val t = typeVarGenerator.getUnique
    val ts = typeVarGenerator.getNUnique(n)
    val allPairs = ts flatMap { x => Seq(x, SetT1(x)) }
    val domT = ts match {
      case single +: Nil => single
      case _             => TupT1(ts: _*)
    }
    val operT =
      OperT1(
          t +: allPairs,
          FunT1(domT, t)
      )
    matchArgImplied(operT, args)
  }

  // \A T_1,...,T_n . (Str, T_1,..., Str, T_m) => [ s1: T_1, ... , sn: T_n ]
  def matchRecEnumSig(args: Seq[TlaEx]): SignatureMatch = {
    val recordKeyTypePairs = args.zipWithIndex.collect {
      case (ValEx(TlaStr(s)), i) if i % 2 == 0 =>
        s -> typeVarGenerator.getUnique
    }

    val argTypes = recordKeyTypePairs flatMap { case (_, typeVar) =>
      Seq(StrT1(), typeVar)
    }

    val operT =
      OperT1(
          argTypes,
          RecT1(recordKeyTypePairs: _*)
      )
    matchArgImplied(operT, args)
  }

  // \A T_1,...,T_n . (Str, Set(T_1),..., Str, Set(T_m)) => Set( [ s1: T_1, ... , sn: T_n ] )
  def matchRecSetSig(args: Seq[TlaEx]): SignatureMatch = {
    val recordKeyTypePairs = args.zipWithIndex.collect {
      case (ValEx(TlaStr(s)), i) if i % 2 == 0 =>
        s -> typeVarGenerator.getUnique
    }

    val argTypes = recordKeyTypePairs flatMap { case (_, typeVar) =>
      Seq(StrT1(), SetT1(typeVar))
    }

    val operT =
      OperT1(
          argTypes,
          SetT1(RecT1(recordKeyTypePairs: _*))
      )
    matchArgImplied(operT, args)
  }

  // \A T_1,...,T_n . (Set(T_1),..., Set(T_m)) => Set( (T_1, ... ,T_n) )
  def matchCartesianProdSig(args: Seq[TlaEx]): SignatureMatch = {
    val ts = typeVarGenerator.getNUnique(args.length)
    val operT =
      OperT1(
          ts map SetT1,
          SetT1(TupT1(ts: _*))
      )
    matchArgImplied(operT, args)
  }

  // \A T_1,...,T_n . (T_1,..., T_m) => (T_1, ... ,T_n)
  def matchTupleSig(args: Seq[TlaEx]): SignatureMatch = {
    val ts = typeVarGenerator.getNUnique(args.length)
    val operT =
      OperT1(
          ts,
          TupT1(ts: _*)
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Bool,T,T) => T
  def matchIteSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(BoolT1(), t, t),
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . (T, Bool, T, ..., Bool, T) => T
  def matchCaseOtherSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs >= 3 && nArgs % 2 == 1)
    val n = (nArgs - 1) / 2
    val t = typeVarGenerator.getUnique
    val tupArgs = (1 to n) flatMap { _ => Seq(BoolT1(), t) }
    val operT =
      OperT1(
          t +: tupArgs,
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . (Bool, T, ..., Bool, T) => T
  def matchCaseNoOtherSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs >= 2 && nArgs % 2 == 0)
    val n = nArgs / 2
    val t = typeVarGenerator.getUnique
    val tupArgs = (1 to n) flatMap { _ => Seq(BoolT1(), t) }
    val operT =
      OperT1(
          tupArgs,
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T, T_1, ..., T_n . ( (T_1,...,T_n) => T, T_1, ..., T_n ) => T
  def matchOperAppSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs > 0)
    val n = nArgs - 1
    val t = typeVarGenerator.getUnique
    val ts = typeVarGenerator.getNUnique(n)

    val operT =
      OperT1(
          OperT1(ts, t) +: ts,
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T), Seq(T) ) => Seq(T)
  def matchConcatSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t), SeqT1(t)),
          SeqT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T) ) => T
  def matchHeadSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t)),
          t
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T) ) => Seq(T)
  def matchTailSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t)),
          SeqT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T) ) => Int
  def matchLenSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t)),
          IntT1()
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Set(T) ) => Set(Seq(T))
  def matchSeqSetSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SetT1(t)),
          SetT1(SeqT1(t))
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T), T ) => Seq(T)
  def matchAppendSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t), t),
          SeqT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T), Int, Int ) => Seq(T)
  def matchSubSeqSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t), IntT1(), IntT1()),
          SeqT1(t)
      )
    matchArgImplied(operT, args)
  }

  // \A T . ( Seq(T), (T) => Bool ) ) => Seq(T)
  def matchSelectSeqSig(args: Seq[TlaEx]): SignatureMatch = {
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          Seq(SeqT1(t), OperT1(Seq(t), BoolT1())),
          SeqT1(t)
      )
    matchArgImplied(operT, args)
  }

  // Several types in TT1 describe objects with the same kind of functionality: application
  // Instead of case-splitting in matching, we can simply view each type, for which this is the
  // case as a pair of two values: (fromT, toT) [Applicative],
  // describing the required argument type and the type of the object after application respectively.
  // For records and tuples, we additionally take an index hint as an argument, because
  // the codomain type may depend on the argument
  sealed case class Applicative(fromT: TlaType1, toT: TlaType1)

  def asInstanceOfApplicative(tt1: TlaType1, argHint: TlaEx): Option[Applicative] =
    tt1 match {
      // A function T1 -> T2 application takes a T1 argument and returns a T2 value
      case FunT1(domT, cdmT) => Some(Applicative(domT, cdmT))
      // A record [ s2: T1, s2: T2, ... ] application takes a Str argument and returns
      // a value of type Ti, which depends on the argument value (not just type)
      case RecT1(fieldTypes) =>
        argHint match {
          case ValEx(TlaStr(s)) => Some(Applicative(StrT1(), fieldTypes(s)))
          case _                => None
        }
      // A sequence Seq(T) application takes an Int argument and returns a T value
      case SeqT1(t) => Some(Applicative(IntT1(), t))
      // Sparse tuples are similar to records
      case SparseTupT1(indexTypes) =>
        argHint match {
          case ValEx(TlaInt(i)) => Some(Applicative(IntT1(), indexTypes(i.toInt)))
          case _                => None
        }
      // Tuples are similar to records
      case TupT1(tupArgs @ _*) =>
        argHint match {
          case ValEx(TlaInt(i)) =>
            val j = (i - 1).toInt // TLA is 1-indexed, indexedSeq is 0-indexed
            Some(Applicative(IntT1(), tupArgs.toIndexedSeq(j)))
          case _ => None
        }
      case _ => None
    }

  def matchApplicativeAppSig(args: Seq[TlaEx]): SignatureMatch = {
    assert(args.length == 2)
    val Seq(applicativeEx, argEx) = args
    for {
      Applicative(fromT, toT) <- asInstanceOfApplicative(
          asTlaType1(applicativeEx.typeTag),
          argHint = argEx
      )
      (subst, _) <- singleUnification(fromT, asTlaType1(argEx.typeTag))
    } yield subst(toT)
  }

  // 2 factors make except difficult: Overloading and multi-level syntax
  def matchExceptOverloadedSig(args: Seq[TlaEx]): SignatureMatch = {
    val nArgs = args.length
    assert(nArgs >= 3 && nArgs % 2 == 1)
    // Each row lists all of the arguments that define one update,
    val argumentRows = args.zipWithIndex collect {
      case (OperEx(TlaFunOper.tuple, tupArgs @ _*), i) if i % 2 == 1 =>
        tupArgs
    }

    // Ensure that all of the arguments are uniformly sized
    val rowSizes = (argumentRows map {
      _.length
    }).toSet
    assert(rowSizes.size == 1)

    // The first argument is the applicative (fn/rec/seq/tup), the other even positions hold
    // the updates in the final codomain
    val applicativeCand +: codomainArgs = args.zipWithIndex collect {
      case (ex, i) if i % 2 == 0 =>
        ex
    }

    // The arguments match the signature of (multi-level) except, if the argument types consecutively unify
    // with the appropriate layer of applicativeCand.type
    def matchUpdateWithArgChain(guidingType: TlaType1, rowArgTypes: Seq[TlaEx], updateType: TlaType1,
        subst: Substitution = Substitution.empty): Option[(Substitution, TlaType1)] =
      rowArgTypes match {
        case Nil =>
          // If we've run out of arguments, guidingType has been peeled until the update layer
          // It must unify with the updateType
          singleUnification(guidingType, updateType)
        case headEx +: tailExs =>
          for {
            // Otherwise, `guidingType` must be some form of Applicative(fromT, toT).
            // As headEx is the argument at the current depth, its value gives us a hint for tuples/records.
            Applicative(fromT, toT) <- asInstanceOfApplicative(guidingType, argHint = headEx)
            // The toT type, in the case of multi-level EXCEPT may be another applicative, so
            // we recurse with it over the other arguments given by tailExs.
            (subst1, _) <- matchUpdateWithArgChain(toT, tailExs, updateType, subst)
            // Lastly, the type of headEx must unify with the domain type at the current layer, fromT
            (subst2, _) <- singleUnification(asTlaType1(headEx.typeTag), fromT, subst1)
            // If this process fails to unify at any step, we get None, otherwise we
            // have shown type compliance with guidingType at this layer (which may morph slightly under
            // the computed substitution)
          } yield (subst2, subst2(guidingType))
      }

    // We now analyze each update point in sequence.
    // A point is a pair (a,b), where a is a sequence of arguments to the Applicative of each layer
    // and b is the final codomain update.
    val finalMatch =
      argumentRows
        .zip(codomainArgs)
        .foldLeft(
            // the starting type for the result of except is the type of the 1st argument,
            // i.e. the object being updated
            Option((Substitution.empty, asTlaType1(applicativeCand.typeTag)))
        ) {
          // For each point, we use matchUpdateWithArgChain to see if the given arguments/codomain value
          // unify with the type of the object being updated
          case (Some((subst, partialUnifApplicativeType)), (rowExs, codomainEx)) =>
            matchUpdateWithArgChain(
                partialUnifApplicativeType,
                rowExs,
                asTlaType1(codomainEx.typeTag),
                subst
            )
          case _ => None
        }
    // Finally, we drop the substitution, as it is no longer needed
    finalMatch map { _._2 }
  }

  // \A T . Applicative( T, _ ) => Set(T)
  def matchApplicativeDomainSig(args: Seq[TlaEx]): SignatureMatch = {
    assert(args.size == 1)
    val tt1 = asTlaType1(args.head.typeTag)
    tt1 match {
      case FunT1(domT, _) => Some(SetT1(domT))
      case _: RecT1       => Some(SetT1(StrT1()))
      case _: SeqT1       => Some(SetT1(IntT1()))
      case _: SparseTupT1 => Some(SetT1(IntT1()))
      case _: TupT1       => Some(SetT1(IntT1()))
      case _              => None
    }
  }

  // \A T . (T, Str, ..., Str) => T
  def matchLabelSig(args: Seq[TlaEx]): SignatureMatch = {
    // 1st arg is anything, label and extra label args are strings
    val nLabelArgs = args.length - 1
    val t = typeVarGenerator.getUnique
    val operT =
      OperT1(
          t +: Seq.fill(nLabelArgs)(StrT1()),
          t
      )
    matchArgImplied(operT, args)
  }
}
