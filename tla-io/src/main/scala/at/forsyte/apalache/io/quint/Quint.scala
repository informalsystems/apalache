package at.forsyte.apalache.io.quint

import at.forsyte.apalache.io.quint.QuintEx._
import at.forsyte.apalache.io.quint.QuintType._
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.typecomp._
import at.forsyte.apalache.tla.types.tla

// scalaz is brought in For the Reader monad, which we use for
// append-only, context local state for tracking reference to nullary TLA
// operators
import scalaz._
// list and traverse give us monadic mapping over lists
// see https://github.com/scalaz/scalaz/blob/88fc7de1c439d152d40fce6b20d90aea33cbb91b/example/src/main/scala-2/scalaz/example/TraverseUsage.scala
import scalaz.std.list._
import scalaz.syntax.traverse._

import scala.util.{Failure, Success, Try}
import at.forsyte.apalache.tla.lir.values.TlaStr

// Convert a QuintEx into a TlaEx
//
// We implement a small family of mutually recursive conversion functions using this
// class in order to:
//
// - Encapsulate and store benign state used by the ScopedBuilder (see below)
// - Support and encapsulate the mutual recursion needed in the methods
//
// Since we need access to the statefull uniqeLambdaName, this class must be
// defined in the Quint class rather than in its companion object (like the toTlaType class)
class Quint(quintOutput: QuintOutput) {
  private val nameGen = new QuintNameGen // name generator, reused across the entire spec
  private val typeConv = new QuintTypeConverter
  private val table = quintOutput.table
  private val types = quintOutput.types

  // The special name used by compiled quint specs for the initialization predicate
  private val quintInitName = "q::init"

  // Stores contextual data needed while performing the conversion
  //
  // `nullarOps`:
  //
  // The set of strings records the names of the nullary operators that are
  // in scope for the computation. This is required because TLA operator taking no
  // parameter are typed as of they take a unit value. E.e., in
  //
  //    Foo == TRUE
  //
  // we type `Foo : () => Bool`.
  //
  // In order to use `Foo` later, the Apalache IR requires us to apply `Foo` to
  // the unit. E.g., `Foo() /\ TRUE`.
  //
  // To know which names are typed as operator taking the unit and which refer
  // to values, we need to track the nullary operator in scope. Use of the
  // reader monad lets us express computations in a context with set of names
  // accumulated as scopes are nested, but not shared between unrelated scopes.
  //
  // `isInit`:
  //
  // This is true if the declaration being converted is the init predicate of
  // the quint spec. We need to track this so we can construct proper equalities
  // for state variables in the init: init setting state variables should be an
  // equality, but in any other operator it should be an assignment.
  case class Context(
      nullaryOps: Set[String],
      isInit: Boolean) {

    def addNullaryOpName(n: String): Context = this.copy(nullaryOps + n)
  }

  object Context {
    def empty: Context = Context(Set(), false)
  }

  // A `ContextReader[A]` is a computation producing values of type `A` that
  // can read from the `Context`
  private type ContextReader[A] = Reader[Context, A]

  // Find the type for an id via the lookup table provided in the quint output
  private def getTypeFromLookupTable(id: BigInt): Try[QuintType] = {
    table.get(id) match {
      case None => Failure(new QuintIRParseError(s"No entry found for id ${id} in lookup table"))
      case Some(lookupEntry) =>
        types.get(lookupEntry.id) match {
          case None =>
            Failure(new QuintIRParseError(
                    s"No type found for definition ${lookupEntry.name} (${lookupEntry.id}) associated with id ${id}"))
          case Some(t) => Success(t.typ)
        }
    }
  }

  // Derive a OperParam from a paramter name and it's type.
  //
  // OperParams are required by the ScopedBuilder for building
  // operators and consist of the param's name and its arity,
  // which we here derive from the QuintType.
  private val operParam: ((QuintLambdaParameter, QuintType)) => OperParam = {
    case (param, QuintOperT(args, _)) => OperParam(param.name, args.length)
    case (param, _)                   => OperParam(param.name, 0) // Otherwise, we have a value
  }

  // QuintLambda is used both for anonymous operators and for defined
  // operators that take parameters, but these require different constructs
  // in Apalache's IR. Thus, we need to decompose the parts of a QuintLambda
  // for two different purposes.
  private val lambdaBodyAndParams: QuintLambda => ContextReader[(TBuilderInstruction, Seq[(OperParam, TlaType1)], TlaType1)] = {
    case ex @ QuintLambda(id, paramNames, _, body) =>
      val (quintParamTypes, quintResType) = types(id).typ match {
        case QuintOperT(types, resType) => (types, resType)
        case invalidType                => throw new QuintIRParseError(s"lambda ${ex} has invalid type ${invalidType}")
      }
      val operParams = paramNames.zip(quintParamTypes).map(operParam)
      val paramTypes = quintParamTypes.map(typeConv.convert(_))
      val typedParams = operParams.zip(paramTypes)
      for {
        tlaBody <- tlaExpression(body)
      } yield (tlaBody, typedParams, typeConv.convert(quintResType))
  }

  private def typeTagOfId(id: BigInt): TypeTag = {
    Typed(typeConv.convert(types(id).typ))
  }

  private type T = TBuilderInstruction

  private def throwOperatorArityError(op: String, arity: String, args: Seq[QuintEx]) =
    // This should be impossible to hit, unless quint produces a malformed AST
    throw new QuintIRParseError(s"too many arguments passed to ${arity} operator ${op}: ${args}")

  // The following *App operators are helpers to factor out building operator applications
  // and the related error handling for operators of different arities.
  private val unaryApp: (String, T => T) => Seq[QuintEx] => ContextReader[T] =
    (op, tlaBuilder) => {
      case Seq(a) =>
        for {
          tlaA <- tlaExpression(a)
        } yield tlaBuilder(tlaA)
      case tooManyArgs => throwOperatorArityError(op, "unary", tooManyArgs)
    }

  private val binaryApp: (String, (T, T) => T) => Seq[QuintEx] => ContextReader[T] =
    (op, tlaBuilder) => {
      case Seq(a, b) =>
        for {
          tlaA <- tlaExpression(a)
          tlaB <- tlaExpression(b)
        } yield tlaBuilder(tlaA, tlaB)
      case tooManyArgs => throwOperatorArityError(op, "binary", tooManyArgs)
    }

  private val ternaryApp: (String, (T, T, T) => T) => Seq[QuintEx] => ContextReader[T] =
    (op, builder) => {
      case Seq(a, b, c) =>
        for {
          tlaA <- tlaExpression(a)
          tlaB <- tlaExpression(b)
          tlaC <- tlaExpression(c)
        } yield builder(tlaA, tlaB, tlaC)
      case tooManyArgs => throwOperatorArityError(op, "ternary", tooManyArgs)
    }

  // no opName needed since we can't hit an arity error
  private val variadicApp: (Seq[T] => T) => Seq[QuintEx] => ContextReader[T] =
    tlaBuilder => args => args.toList.traverse(tlaExpression).map(tlaBuilder)

  // A binding operator is an operator that involves binding a name to a referent within a scope.
  //
  // In quint, all binding operators delegate scoped name-binding to an anonymous operator. E.g., in
  // `Set(1, 2, 3).forall(n => n > 0)` the scoped binding of the name `n` is delegated to the
  // anonymous operator given in the second argument.
  //
  // In TLA+, we have several different binding constructs. The quint expression given above must
  // become `∀n ∈ {1, 2, 3}: (n > 0)` in Apalache's IR, which has no uniform construct for representing
  // bindings.
  //
  // The following operator deconstruct quint binding operators to provide the extracted name and body
  // to the given `tlaBuilder`, which is expected to be one of the special binding operators.
  //
  // - `op` is the name of the quint operator we are converting, and is used only for error reporting
  // - `tlaBuilder` is the tla binding operator we are to construct, if `op` has arity n, `tlaBuilder`
  //    will have arity n + 1, accounting for the need to take the name as a separate argument.
  //
  // The combinators return a function that takes a Seq of quint expressions to a tla builder instruction,
  // performing all needed validation and extraction.
  private val binaryBindingApp: (String, (T, T, T) => T) => Seq[QuintEx] => ContextReader[T] = {
    // Multi argument bindings must be wrapped in a tuple
    // See https://github.com/informalsystems/apalache/issues/2292
    val wrapArgs: Seq[T] => T = {
      case Seq(singleName) => singleName
      case names           => tla.tuple(names: _*)
    }

    (op, tlaBuilder) => {
      case Seq(set, binder) =>
        // The binder is an operator, the parameters of which will be the
        // names bound in the TLA binding operator.
        binder match {
          case lambda @ QuintLambda(_, Seq(), _, _) =>
            throw new QuintIRParseError(
                s"""|Operator ${op} is a binding operator requiring a non nullary
                    |operator as its second argument, but it was given the nullary
                    | ${lambda}""".stripMargin
            )
          case QuintLambda(_, params, _, scope) =>
            // uniquely rename parameters of name "_" to prevent shadowing of nested "_"s
            // https://github.com/informalsystems/quint/issues/926
            val translateParameterName: QuintLambdaParameter => String = {
              case QuintLambdaParameter(id, "_") => s"__QUINT_UNDERSCORE_${id}"
              case QuintLambdaParameter(_, name) => name
            }
            val tlaArgs = params.map(p => tla.name(translateParameterName(p), typeConv.convert(types(p.id).typ)))
            val varBindings = wrapArgs(tlaArgs)
            for {
              tlaSet <- tlaExpression(set)
              tlaScope <- tlaExpression(scope)
            } yield tlaBuilder(varBindings, tlaSet, tlaScope)
          case opName @ QuintName(id, _) =>
            // When the binder is a name (which must refer to an operator), we
            // generate names for the TLA binding operation based in the operator's
            // type.
            val paramTypes = types(id).typ match {
              case QuintOperT(args, _) => args
              case invalidType =>
                throw new QuintIRParseError(
                    s"""|Operator ${op} is a binding operator requiring an operator as it's second argument,
                        |but it was given ${opName} with type ${invalidType}""".stripMargin
                )
            }
            val tlaArgs = paramTypes.map(typ => tla.name(nameGen.uniqueVarName(), typeConv.convert(typ)))
            val varBindings = wrapArgs(tlaArgs)
            for {
              tlaOpName <- tlaExpression(opName)
              // Apply the operator name to the the generated names
              tlaScope = tla.appOp(tlaOpName, tlaArgs: _*)
              tlaSet <- tlaExpression(set)
            } yield tlaBuilder(varBindings, tlaSet, tlaScope)
          case invalidBinder =>
            throw new QuintIRParseError(
                s"""|Operator ${op} is a binding operator requiring an operator as it's second argument,
                    |but it was given ${invalidBinder}""".stripMargin
            )
        }
      case wrongNumberOfArgs => throwOperatorArityError(op, "binary", wrongNumberOfArgs)
    }
  }

  private object MkTla {
    // MkTla gathers non-trivial conversion functions from quint args to TLA builder instructions
    type Converter = Seq[QuintEx] => ContextReader[TBuilderInstruction]

    def setEnumeration(elementType: QuintType): Converter =
      variadicApp {
        // Empty sets must be handled specially since we cannot infer their type
        // from the given arguments
        case Seq() => tla.emptySet(typeConv.convert(elementType))
        case args  => tla.enumSet(args: _*)
      }

    def listConstruction(id: BigInt): Converter =
      variadicApp {
        // Empty lists must be handled specially since we cannot infer their type
        // from the given arguments
        case Seq() =>
          val elementType = types(id).typ match {
            case QuintSeqT(t) => typeConv.convert(t)
            case invalidType =>
              throw new QuintIRParseError(s"List with id ${id} has invalid type ${invalidType}")
          }
          tla.emptySeq(elementType)
        case args => tla.seq(args: _*)
      }

    // Convert Quint's `select : (List[a], (a => bool)) => List[a]` to the
    // something like the rewired SelectSeq in /src/tla/__rewire_sequences_in_apalache.tla
    //
    // This should look like:
    //
    //    SelectSeq(__s, __Test(_)) ==
    //      LET __AppendIfTest(__res, __e) ==
    //        IF __Test(__e) THEN Append(__res, __e) ELSE __res IN
    //    __ApalacheFoldSeq(__AppendIfTest, <<>>, __s)
    //
    // This works when `__Test` is passed by name.
    //
    // However, when `__Test` is a lambda, and thus represented in Apalache's IR as a
    // 'de-lambdad' let expression like `LET __test(x) == p(x) IN _test`
    // we hit a snag because Apalache does not support applying such expressions
    // as operators.
    //
    // Consequently, when called with a lambda, we deconstruct the lambda as a binding operator
    // to obtain
    //
    //   __e    = boundVarFrom(__Test)
    //   __test = bodyFrom(__Test)
    //
    // and then produce:
    //
    //   SelectSeq(__s, __Test(_)) ==
    //     ApaFoldSeqLeft(
    //       LET __QUINT_LAMBDA0(__quint_var0, __e) ≜
    //         IF __test THEN (Append(__quint_var0, __e)) ELSE __quint_var0
    //       IN QUINT_LAMBDA0,
    //       <<>>,
    //       <<1, 2, 3>>
    //     )
    //
    //  Since `__e` is free in `__test` the binding in the head of `__QUINT_LAMBDA0`
    //  will bind the element at the appropriate place in the test.
    def selectSeq(opName: String, seqType: TlaType1): Converter =
      binaryApp(opName,
          (seq, testOp) => {
            // When the test operator is given by name, we apply it to the element.
            val elemType = seqType match {
              case SeqT1(elem) => elem
              case invalidType => throw new QuintIRParseError(s"sequence ${seq} has invalid type ${invalidType}")
            }
            val resultParam = tla.param(nameGen.uniqueVarName(), seqType)
            val elemParam = tla.param(nameGen.uniqueLambdaName(), elemType)
            val result = tla.name(resultParam._1.name, resultParam._2)
            val elem = tla.name(elemParam._1.name, elemParam._2)
            val ite = tla.ite(tla.appOp(testOp, elem), tla.append(result, elem), result)
            val testLambda = tla.lambda(nameGen.uniqueLambdaName(), ite, resultParam, elemParam)
            tla.foldSeq(testLambda, tla.emptySeq(elemType), seq)
          })

    def exceptWithUpdate(opName: String, id: BigInt): Converter =
      // f.setBy(x, op) ~~>
      //
      // LET f_cache = f IN
      // [f_cache EXCEPT ![k] |-> op(f_cache[k])]
      ternaryApp(opName,
          (f, x, op) => {
            val f_cache_name = nameGen.uniqueVarName()
            val f_type = typeConv.convert(types(id).typ)
            val f_cache = tla.appOp(tla.name(f_cache_name, OperT1(Seq(), f_type)))
            val cacheDecl = tla.decl(f_cache_name, f)
            tla.letIn(
                tla.except(f_cache, x, tla.appOp(op, tla.app(f_cache, x))),
                cacheDecl,
            )
          })

    def extendFunction(opName: String): Converter =
      quintArgs =>
        ternaryApp(opName,
            (map, key, value) => {
              // (key :> value) @@ map ==
              //    LET __map_cache == __map IN
              //    LET __dom == DOMAIN __map_cache IN
              //    [__x \in {key} \union __dom |-> IF __x = key THEN value ELSE __map_cache[__x]]
              // extract types
              val mapType = typeConv.convert(types(quintArgs(0).id).typ)
              val keyType = typeConv.convert(types(quintArgs(1).id).typ)
              // string names
              val mapCacheName = nameGen.uniqueVarName()
              val domName = nameGen.uniqueVarName()
              // TLA+ name expressions
              val mapCache = tla.name(mapCacheName, OperT1(Seq(), mapType))
              val dom = tla.name(domName, OperT1(Seq(), SetT1(keyType)))
              // build the final funDef, i.e., the LET-IN body
              val bindingVar = tla.name(nameGen.uniqueVarName(), keyType)
              val ite = tla.ite(tla.eql(bindingVar, key), value, tla.app(tla.appOp(mapCache), bindingVar))
              val composed = tla.funDef(ite, (bindingVar, tla.cup(tla.enumSet(key), tla.appOp(dom))))
              // build the entire LET-IN
              tla.letIn(
                  composed,
                  tla.decl(mapCacheName, map),
                  tla.decl(domName, tla.dom(tla.appOp(mapCache))),
              )
            })(quintArgs)

    // We cannot simply use DOMAIN b/c quint lists are 0-indexed
    // so we convert `s.indices` into
    //
    //    LET dom ≜ DOMAIN s IN
    //    IF dom = {} THEN
    //      {}
    //    ELSE
    //      (dom ∪ {0}) ∖ {Len(s)}
    def indices(opName: String): Converter =
      unaryApp(opName,
          seq => {
            val emptyDom = tla.emptySet(IntT1)
            val domNameStr = nameGen.uniqueVarName()
            val domName = tla.name(domNameStr, OperT1(Seq(), SetT1(IntT1)))
            val dom = tla.decl(domNameStr, tla.dom(seq))
            val body = tla.ite(
                tla.eql(tla.appOp(domName), emptyDom),
                emptyDom,
                tla.setminus(tla.cup(tla.appOp(domName), tla.enumSet(tla.int(0))), tla.enumSet(tla.len(seq))),
            )
            tla.letIn(body, dom)
          })

    // Create a TLA record
    def record(rowVar: Option[String]): Converter = { quintArgs =>
      // The quint Rec operator takes its field and value arguments
      // via a variadic operator requiring field names passed as strings to
      // be alternated with values. E.g.,
      //
      //    Rec("f1", 1, "f2", 2)
      //
      // So we first separate out the field names from the values, so we
      // can make use of the existing combinator for variadic operators.
      //
      // Empty records are fine: those are the unit value.
      val (fieldNames, quintVals) = quintArgs
        .grouped(2)
        .foldRight((List[String](), List[QuintEx]())) {
          case (Seq(QuintStr(_, f), v), (fields, values)) => ((f :: fields), v :: values)
          case (invalidArgs, _) =>
            throw new QuintIRParseError(s"Invalid argument given to Rec ${invalidArgs}")
        }
      variadicApp { tlaVals =>
        val fieldsAndArgs = fieldNames.zip(tlaVals)
        tla.rowRec(rowVar, fieldsAndArgs: _*)
      }(quintVals)
    }

    // Create a TLA variant
    def variant(quintType: QuintType): Converter = {
      val tlaType = typeConv.convert(quintType)
      tlaType match {
        case variantType: VariantT1 =>
          binaryApp("variant",
              (labelInstruction, expr) =>
                // The builder requires a string literal, rather than a string expression
                // so we have to build the converted TLA expression and extract its string value.
                labelInstruction.flatMap {
                  case ValEx(TlaStr(label)) => tla.variant(label, expr, variantType)
                  case invalidLabel =>
                    throw new QuintIRParseError(s"Invalid label found in application of variant ${invalidLabel}")
                })
        case _ => throw new QuintIRParseError(s"Invalid type inferred for application of variant ${quintType}")
      }
    }

    // the quint builtin operator representing match expressions looks like
    //
    // matchVariant(expr, "F1", elim_1, ..., "Fn", elim_n[, "_", defaultElim])
    //
    // Where each `elim_i` is an operator applying to value wrapped in field `Fi` of a variant.
    //
    // This is converted into the following Apalache expression, using Apalache's variant operators:
    //
    // CASE VariantTag(expr) = "F1" -> elim_1(VariantGetUnsafe("F1", expr))
    //   [] ...
    //   [] VariantTag(expr) = "Fn" -> elim_n(VariantGetUnsafe("Fn", expr))
    //   [] OTHER -> defaultElim([])
    //
    // This ensures that we will apply the proper eliminator to the expected value
    // associated with whatever tag is carried by the variant `expr`.
    //
    // The final, default case may not be present, in which case no `OTHER` case is
    // constructed.
    def matchVariant: Converter = variadicApp { case expr +: cases =>
      val variantTagCondition = (caseTag) => tla.eql(tla.variantTag(expr), caseTag)

      // Check the last case to see if there is a default case, which will need special treatment
      // If a valid quint match expression has a default case, it will always be the last case
      // in a match.
      val (matchCases, defaultCase) = cases.grouped(2).toSeq match {
        case Seq() =>
          (Seq(), None) // A match expression with no cases is invalid: we let the builder handle the error
        case allCases @ (cs :+ Seq(label, defaultElim)) =>
          build(label) match {
            case ValEx(TlaStr("_")) =>
              // We have a default case, which is always paired with an eliminator that
              // can be applied to the unit value (an empty record).
              (cs, Some(tla.appOp(defaultElim, tla.rowRec(None))))
            case _ =>
              // All cases have match expressions
              (allCases, None)
          }
      }

      val casesInstructions: Seq[(T, T)] =
        matchCases.map { case Seq(label, elim) =>
          val appliedElim = label.flatMap {
            case ValEx(TlaStr(labelLit)) =>
              tla.appOp(elim, tla.variantGetUnsafe(labelLit, expr))
            case invalidLabel =>
              throw new QuintIRParseError(s"Invalid label found in matchVariant case ${invalidLabel}")
          }
          variantTagCondition(label) -> appliedElim
        }

      defaultCase match {
        case None          => tla.caseSplit(casesInstructions: _*)
        case Some(default) => tla.caseOther(default, casesInstructions: _*)
      }
    }

    def assign: Converter = {
      case Seq(variable, value) =>
        // Read from the context to see if we are in the init predicate
        Reader((ctx: Context) => ctx.isInit).flatMap(isInit =>
          for {
            tlaVariable <- tlaExpression(variable)
            tlaValue <- tlaExpression(value)
          } yield
            if (isInit) {
              // If we are in the init predicate, we just have an equality
              tla.eql(tlaVariable, tlaValue)
            } else {
              // Otherwise, we have an assignment
              tla.assign(tla.prime(tlaVariable), tlaValue)
            })
      case tooManyArgs => throwOperatorArityError("assign", "binary", tooManyArgs)
    }
  }

  // Increments the TLA expression (as a TBuilderInstruction), which is assumed
  // to be an integer.
  //
  // Used in the conversion of quint list operator to TLA sequence operators,
  // due to the fact that quint indexing is 0-based but TLA indexing is 1-based.
  private val incrTla: T => T = tlaEx => tla.plus(tlaEx, tla.int(1))

  private val tlaApplication: QuintApp => ContextReader[TBuilderInstruction] = { case QuintApp(id, opName, quintArgs) =>
    val applicationBuilder: Seq[QuintEx] => ContextReader[TBuilderInstruction] = opName match {
      // First we check for application of builtin operators

      // Illegal builtins
      // We expect that these builins will be eliminated in earlier stages
      // of the translation (e.g., inside special forms like `nondet`) or
      // via rewrites in quint.
      case "oneOf" =>
        // See https://github.com/informalsystems/apalache/issues/2774
        throw new QuintIRParseError(
            s"`oneOf` can only occur as the principle operator of a `nondet` declaration: `oneOf` operator with id ${id} applied to ${quintArgs}")

      // Booleans
      case "eq"      => binaryApp(opName, tla.eql)
      case "neq"     => binaryApp(opName, tla.neql)
      case "iff"     => binaryApp(opName, tla.equiv)
      case "implies" => binaryApp(opName, tla.impl)
      case "not"     => unaryApp(opName, tla.not)
      case "and"     => variadicApp(args => tla.and(args: _*))
      case "or"      => variadicApp(args => tla.or(args: _*))

      // Integers
      case "iadd"    => binaryApp(opName, tla.plus)
      case "isub"    => binaryApp(opName, tla.minus)
      case "imul"    => binaryApp(opName, tla.mult)
      case "idiv"    => binaryApp(opName, tla.div)
      case "imod"    => binaryApp(opName, tla.mod)
      case "ipow"    => binaryApp(opName, tla.exp)
      case "ilt"     => binaryApp(opName, tla.lt)
      case "igt"     => binaryApp(opName, tla.gt)
      case "ilte"    => binaryApp(opName, tla.le)
      case "igte"    => binaryApp(opName, tla.ge)
      case "iuminus" => unaryApp(opName, tla.uminus)

      // Sets
      case "Set" =>
        types(id).typ match {
          case QuintSetT(t) => MkTla.setEnumeration(t)
          case invalidType  => throw new QuintIRParseError(s"Set with id ${id} has invalid type ${invalidType}")
        }
      case "exists"    => binaryBindingApp(opName, tla.exists)
      case "forall"    => binaryBindingApp(opName, tla.forall)
      case "in"        => binaryApp(opName, tla.in)
      case "contains"  => binaryApp(opName, (set, elem) => tla.in(elem, set))
      case "notin"     => binaryApp(opName, tla.notin)
      case "union"     => binaryApp(opName, tla.cup)
      case "intersect" => binaryApp(opName, tla.cap)
      case "exclude"   => binaryApp(opName, tla.setminus)
      case "subseteq"  => binaryApp(opName, tla.subseteq)
      case "filter"    => binaryBindingApp(opName, tla.filter)
      case "map"       => binaryBindingApp(opName, (name, set, expr) => tla.map(expr, (name, set)))
      case "fold"      => ternaryApp(opName, (set, init, op) => tla.foldSet(op, init, set))
      case "powerset"  => unaryApp(opName, tla.powSet)
      case "flatten"   => unaryApp(opName, tla.union)
      case "allLists"  => unaryApp(opName, tla.seqSet)
      case "isFinite"  => unaryApp(opName, tla.isFiniteSet)
      case "size"      => unaryApp(opName, tla.cardinality)
      case "to"        => binaryApp(opName, tla.dotdot)
      case "chooseSome" => {
        // `chooseSome(S)` is translated to `CHOOSE x \in S: TRUE`
        // and to construct the latter we need to generate a unique
        // variable name for `x` and find the expected type
        val elementType = typeConv.convert(types(id).typ)
        val varName = tla.name(nameGen.uniqueVarName(), elementType)
        unaryApp(opName, tla.choose(varName, _, tla.bool(true)))
      }

      // Lists (Sequences)
      case "List"      => MkTla.listConstruction(id)
      case "append"    => binaryApp(opName, tla.append)
      case "concat"    => binaryApp(opName, tla.concat)
      case "head"      => unaryApp(opName, tla.head)
      case "tail"      => unaryApp(opName, tla.tail)
      case "length"    => unaryApp(opName, tla.len)
      case "indices"   => MkTla.indices(opName)
      case "foldl"     => ternaryApp(opName, (seq, init, op) => tla.foldSeq(op, init, seq))
      case "nth"       => binaryApp(opName, (seq, idx) => tla.app(seq, incrTla(idx)))
      case "replaceAt" => ternaryApp(opName, (seq, idx, x) => tla.except(seq, incrTla(idx), x))
      case "slice"     => ternaryApp(opName, (seq, from, to) => tla.subseq(seq, incrTla(from), incrTla(to)))
      case "select"    => MkTla.selectSeq(opName, typeConv.convert(types(id).typ))
      case "range" =>
        binaryApp(opName,
            (low, high) => {
              val iParam = tla.param(nameGen.uniqueVarName(), IntT1)
              val i = tla.name(iParam._1.name, iParam._2)
              tla.mkSeqConst(tla.minus(high, low),
                  tla.lambda(nameGen.uniqueLambdaName(), tla.minus(tla.plus(low, i), tla.int(1)), iParam))
            })

      // Tuples
      case "Tup" => variadicApp(args => tla.tuple(args: _*))
      // product projection is just function application on TLA
      case "item"   => binaryApp(opName, tla.app)
      case "tuples" => variadicApp(tla.times)

      // Records
      case "Rec" =>
        val rowVar = types(id).typ match {
          case r: QuintRecordT => r.rowVar
          case invalidType =>
            throw new QuintIRParseError(s"Invalid type given for Rec operator application ${invalidType}")
        }
        MkTla.record(rowVar)
      case "field"      => binaryApp(opName, tla.app)
      case "fieldNames" => unaryApp(opName, tla.dom)
      case "with"       => ternaryApp(opName, tla.except)

      // Sum types
      case "variant"      => MkTla.variant(types(id).typ)
      case "matchVariant" => MkTla.matchVariant

      // Maps (functions)
      // Map is variadic on n tuples, so build a set of these tuple args
      // before converting the resulting set of tuples to a function.
      case "Map" =>
        quintArgs =>
          types(id).typ match {
            case QuintFunT(dom, codom) =>
              MkTla.setEnumeration(QuintType.QuintTupleT.ofTypes(dom, codom))(quintArgs).map(tla.setAsFun)
            case invalidType => throw new QuintIRParseError(s"Map with id ${id} has invalid type ${invalidType}")
          }
      case "get"       => binaryApp(opName, tla.app)
      case "keys"      => unaryApp(opName, tla.dom)
      case "setToMap"  => unaryApp(opName, tla.setAsFun)
      case "setOfMaps" => binaryApp(opName, tla.funSet)
      case "set"       => ternaryApp(opName, tla.except)
      case "mapBy"     => binaryBindingApp(opName, (name, set, expr) => tla.funDef(expr, (name, set)))
      case "setBy"     => MkTla.exceptWithUpdate(opName, id)
      case "put"       => MkTla.extendFunction(opName)

      // Action operators
      case "assign"     => MkTla.assign
      case "actionAll"  => variadicApp(args => tla.and(args: _*))
      case "actionAny"  => variadicApp(args => tla.or(args: _*))
      case "assert"     => unaryApp(opName, identity) // `assert` does not have side-effects in Apalache
      case "fail"       => unaryApp(opName, tla.not)
      case "next"       => unaryApp(opName, tla.prime)
      case "orKeep"     => binaryApp(opName, tla.stutt)
      case "mustChange" => binaryApp(opName, tla.nostutt)
      case "enabled"    => unaryApp(opName, tla.enabled)
      case "then"       => binaryApp(opName, tla.comp)
      case "repeated"   => throw new QuintIRParseError("Operator 'repeated' is not supported")

      // Temporal operators
      case "always"     => unaryApp(opName, tla.box)
      case "eventually" => unaryApp(opName, tla.diamond)
      case "weakFair"   => binaryApp(opName, (action, vars) => tla.WF(vars, action))
      case "strongFair" => binaryApp(opName, (action, vars) => tla.SF(vars, action))

      case "ite" => ternaryApp(opName, tla.ite)

      // Otherwise, the applied operator is defined, and not a builtin
      case definedOpName => { args =>
        val quintType = getTypeFromLookupTable(id).recoverWith { case err: QuintIRParseError =>
          Failure(new QuintIRParseError(
                  s"While converting operator application of defined operator '${definedOpName}' to arguments ${args}: ${err
                  .getMessage()}"))
        }.get
        val operType = typeConv.convert(quintType)
        val oper = tla.name(definedOpName, operType)
        args.toList.traverse(tlaExpression).map(tlaArgs => tla.appOp(oper, tlaArgs: _*))
      }
    }
    applicationBuilder(quintArgs)
  }

  // Convert Quint's nondet variable binding
  //
  //   val nondet name = oneOf(domain); scope
  //   ~~>
  //   \E name \in domain: scope
  private val nondetBinding: (QuintDef.QuintOpDef, QuintEx) => ContextReader[TBuilderInstruction] = {
    case (QuintDef.QuintOpDef(_, name, "nondet", QuintApp(id, "oneOf", Seq(domain))), scope) =>
      val elemType = typeConv.convert(types(id).typ)
      val tlaName = tla.name(name, elemType)
      for {
        tlaDomain <- tlaExpression(domain)
        tlaScope <- tlaExpression(scope)
      } yield tla.exists(tlaName, tlaDomain, tlaScope)
    case invalidValue =>
      throw new QuintIRParseError(s"nondet keyword used to bind invalid value ${invalidValue}")
  }

  private val opDefConverter: QuintDef.QuintOpDef => ContextReader[(TBuilderOperDeclInstruction, Option[String])] = {
    case QuintDef.QuintOpDef(_, name, _, expr) =>
      (expr match {
        // Parameterized operators are defined in Quint using Lambdas
        case lam: QuintLambda =>
          lambdaBodyAndParams(lam).map { case (body, params, _) => (body, params) }
        // Otherwise it's an operator with no params
        case other => tlaExpression(other).map(b => (b, List()))
      }).map {
        case (body, params) => {
          val nullaryName = if (params.isEmpty) Some(name) else None
          (tla.decl(name, body, params: _*), nullaryName)
        }
      }
  }

  def tlaExpression(qEx: QuintEx): ContextReader[TBuilderInstruction] =
    qEx match {
      // scalar values don't need to read anything
      case QuintBool(_, b) => Reader(_ => tla.bool(b))
      case QuintInt(_, i)  => Reader(_ => tla.int(i))
      case QuintStr(_, s)  => Reader(_ => tla.str(s))
      case QuintName(id, name) =>
        name match {
          // special case: predefined set BOOLEAN is Bool in Quint
          case "Bool" => Reader(_ => tla.booleanSet())
          case "Int"  => Reader(_ => tla.intSet())
          case "Nat"  => Reader(_ => tla.natSet())
          // general case: some other name
          case _ =>
            val t = typeConv.convert(types(id).typ)
            Reader { ctx =>
              if (ctx.nullaryOps.contains(name)) {
                tla.appOp(tla.name(name, OperT1(Seq(), t)))
              } else {
                tla.name(name, t)
              }
            }
        }
      case QuintLet(_, binding: QuintDef.QuintOpDef, scope) if binding.qualifier == "nondet" =>
        nondetBinding(binding, scope)
      case QuintLet(_, opDef, expr) =>
        opDefConverter(opDef).flatMap { case (tlaOpDef, nullaryName) =>
          tlaExpression(expr)
            .local { (ctx: Context) =>
              nullaryName match {
                case None    => ctx
                case Some(n) => ctx.addNullaryOpName(n)
              }
            }
            .map(tlaExpr => tla.letIn(tlaExpr, tlaOpDef))
        }
      case lam: QuintLambda =>
        lambdaBodyAndParams(lam).map { case (body, typedParams, resultType) =>
          tla.lambda(nameGen.uniqueLambdaName(), body, resultType, typedParams: _*)
        }
      case app: QuintApp => tlaApplication(app)
    }

  // `tlaDef(quintDef)` is a ContextReader that can be run to obtain a value `Some((maybeName, tlaDecl))`
  // where `tlaDecl` is derived from the given `quintDef`, and `maybeName` is `Some(n)` when the `quintDef`
  // defines a nullary operator named `n`, or  `None` when `quintDef` is not a nullary operator definition.
  // If the `quintDef` is not convertable (e.g., a quint type definition), then the outer value is `None`.
  def tlaDef(quintDef: QuintDef): ContextReader[Option[(Option[String], TlaDecl)]] = {
    import QuintDef._
    Reader(ctx =>
      quintDef match {
        // We don't currently support type definitions in the Apalache IR:
        // all compound types are expected to be inlined.
        case _: QuintTypeDef => None
        // Constant and var declarations are trivial to construct, and
        // no methods for them are provided by the ScopedBuilder.
        case QuintConst(id, name, _) => Some(None, TlaConstDecl(name)(typeTagOfId(id)))
        case QuintVar(id, name, _)   => Some(None, TlaVarDecl(name)(typeTagOfId(id)))
        case d @ QuintAssume(_, name, quintEx) =>
          val tlaEx = build(tlaExpression(quintEx).run(ctx))
          val definedName = Option.unless(d.isUnnamed)(name)
          // assume declarations have no entry in the type map, and are always typed bool
          Some(None, TlaAssumeDecl(definedName, tlaEx)(Typed(BoolT1)))
        case op: QuintOpDef if op.qualifier == "run" =>
          // We don't currently support run definitions
          None
        case op: QuintOpDef =>
          // If we are entering into the init predicate, mark that in the context
          val ctx0 = ctx.copy(isInit = (op.name == quintInitName))
          val (tlaInstruction, maybeName) = opDefConverter(op).run(ctx0)
          val tlaDecl =
            try {
              build(tlaInstruction)
            } catch {
              // If the builder fails, then we've done something wrong in our
              // conversion logic or quint construction, and this is an internal error
              case err @ (_: TBuilderScopeException | _: TBuilderTypeException) =>
                throw new QuintIRParseError(
                    s"Conversion failed while building operator definition `${op.name}`: ${err.getMessage}")
            }
          Some(maybeName, tlaDecl)
      })
  }

  def tlaModule(module: QuintModule): Try[TlaModule] = for {
    declarations <- Try {
      // For each quint declaration, we need to try converting it to
      // a TlaDecl. Additionally, we need to update the context such that
      //
      // - if it is a nullary operator, we need to add its name to our set of nullary operators
      // - if it is the init predicate, we record that
      val accumulatedTlaDecls = List[TlaDecl]()
      // Translate all definitions from the quint module
      module.declarations
        .foldLeft((Context.empty, accumulatedTlaDecls)) {
          // Accumulate the converted definition and the name of the operator, of it is nullary
          case ((ctx, tlaDecls), quintDef) =>
            tlaDef(quintDef).run(ctx) match {
              case None =>
                // Couldn't convert the declaration (e.g., for a type declaration) so ignore it
                (ctx, tlaDecls)
              case Some((None, tlaDecl)) =>
                // Converted a non-nullary operator declaration
                (ctx, tlaDecl :: tlaDecls)
              case Some((Some(nullOp), tlaDecl)) =>
                // Converted a nullary operator declaration, record the nullary op name
                (ctx.addNullaryOpName(nullOp), tlaDecl :: tlaDecls)
            }
        }
        ._2 // Just take the resulting TlaDecls
        .reverse // Return the declarations to their original order
    }
  } yield TlaModule(module.name, declarations)
}
