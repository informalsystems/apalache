package at.forsyte.apalache.tla.imp

import at.forsyte.apalache.tla.imp.src.{SourceLocation, SourceStore}
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.{FixedArity, TlaFunOper, TlaOper}
import at.forsyte.apalache.tla.lir.values.{TlaDecimal, TlaInt, TlaStr}
import at.forsyte.apalache.io.annotations.store._
import at.forsyte.apalache.tla.lir.UntypedPredefs._
import com.typesafe.scalalogging.LazyLogging
// Prevent shadowing our Context trait
import tla2sany.semantic.{Context => _, _}

import scala.jdk.CollectionConverters._

/**
 * Translate a TLA+ expression.
 *
 * @author
 *   konnov
 */
class ExprOrOpArgNodeTranslator(
    sourceStore: SourceStore,
    annotationStore: AnnotationStore,
    context: Context,
    recStatus: RecursionStatus)
    extends LazyLogging {
  def translate(node: ExprOrOpArgNode): TlaEx = {
    val result =
      node match {
        // as tlatools do not provide us with a visitor pattern, we have to enumerate classes here
        case num: NumeralNode =>
          // an integer literal, e.g., 123
          translateNumeral(num)

        case str: StringNode =>
          // a string literal
          translateString(str)

        case dec: DecimalNode =>
          // a decimal literal, e.g., 123.456 (not a floating point!)
          translateDecimal(dec)

        case opApp: OpApplNode =>
          // application of an operator, e.g., F(x)
          OpApplTranslator(sourceStore, annotationStore, context, recStatus)
            .translate(opApp)

        case opArg: OpArgNode =>
          // An operator definition that is used as an expression, e.g., LAMBDA x: x = 1.
          // There are at least two cases:
          //   1. An operator that is passed as an argument to another operator, e.g., B in A(B)
          //   2. A lambda-expression that is passed as an argument ao another operator, e.g., A(LAMBDA x: x)
          translateLambdaOrOperatorAsArgument(opArg)

        case letIn: LetInNode =>
          // Example: LET Foo(a, b) == e1 IN e2
          translateLetIn(letIn)

        case substIn: SubstInNode =>
          // A substitution that originates from INSTANCE Foo WITH x <- a, y <- b.
          // The substitution contains the bindings x <- a, y <- b.
          translateSubstIn(substIn)

        case at: AtNode =>
          // the shortcut "@" that is used in EXCEPT
          translateAt(at)

        case label: LabelNode =>
          // a node label, e.g., lab(x) :: e
          translateLabel(label)

        case n =>
          throw new SanyImporterException(
              "Unexpected subclass of tla2sany.ExprOrOpArgNode: " + n.getClass
          )
      }

    sourceStore.addRec(result, SourceLocation(node.getLocation))
  }

  private def translateNumeral(node: NumeralNode) = {
    if (node.bigVal() != null) {
      ValEx(TlaInt(node.bigVal()))
    } else {
      ValEx(TlaInt(node.`val`()))
    }
  }

  private def translateString(str: StringNode) =
    // internalize the string, so several occurences of the same string are kept as the same object
    ValEx(TlaStr(str.getRep.toString.intern()))

  private def translateDecimal(dec: DecimalNode) =
    if (dec.bigVal() != null) {
      ValEx(TlaDecimal(dec.bigVal()))
    } else {
      // the normal math exponent is the negated scale
      ValEx(TlaDecimal(BigDecimal(dec.mantissa(), -dec.exponent())))
    }

  private def translateLetIn(letIn: LetInNode): TlaEx = {
    // Accumulate definitions as in ModuleTranslator.
    // (As ModuleNode does not implement Context, we cannot reuse the code from there.)

    // We only go through the operator definitions, as one cannot define constants or variables with Let-In.
    // For some reason, multiple definitions come in the reverse order in the letIn.context.
    // Hence, we reverse the list first.
    //
    // TODO: properly handle recursive declarations
    var letInDeclarations = List[TlaOperDecl]()
    var letInContext = context
    for (node <- letIn.context.getOpDefs.elements.asScala.toList.reverse) {
      node match {
        case opdef: OpDefNode =>
          val decl = OpDefTranslator(sourceStore, annotationStore, letInContext)
            .translate(opdef)
          letInDeclarations = letInDeclarations :+ decl
          letInContext = letInContext.push(DeclUnit(decl))

        case _ =>
          throw new SanyImporterException("Expected OpDefNode, found: " + node)
      }
    }

    val body = ExprOrOpArgNodeTranslator(
        sourceStore,
        annotationStore,
        letInContext,
        recStatus,
    ).translate(letIn.getBody)
    LetInEx(body, letInDeclarations: _*)
  }

  // translate an operator definition that is used as an expression, that is, LAMBDA
  private def translateLambdaOrOperatorAsArgument(
      opArgNode: OpArgNode): TlaEx = {
    // Instead of extending the IR with a new expression type, we simply introduce a local LET-IN definition.
    // Although this is a well-defined expression in the IR, it does not correspond to a well-defined TLA+ expression.
    // Hence, one has to take care of this, when printing the output to the user.
    val name = opArgNode.getName.toString
    opArgNode.getOp match {
      case defNode: OpDefNode if name == "LAMBDA" =>
        // a lambda-definition is passed as an argument
        val decl = OpDefTranslator(sourceStore, annotationStore, context)
          .translate(defNode)
        // e.g., LET Foo(x) == e1 in Foo
        LetInEx(NameEx(name), decl)

      case _: OpDefNode =>
        // An operator is passed as an argument.
        // Return a reference to the operator by name.
        // Bugfix #1254: add the prefix, if the operator is inside an instantiated module.
        // Bugfix #1626: the user may pass a built-in operator such as `\\union`, or a library operator such as `+`
        context.lookup(name) match {
          case DeclUnit(decl) =>
            // a user-defined operator passed as an argument
            NameEx(decl.name)

          case OperAliasUnit(_, oper) =>
            // An operator of the standard library. For example, I!+, where I == INSTANCE Integers.
            wrapBuiltinWithLetIn(oper)

          case ValueAliasUnit(_, tlaValue) =>
            // The built-in value, e.g., Int.
            ValEx(tlaValue)

          case NoneUnit() =>
            // No definition found. This may be a standard operator such as `\\union`.
            OpApplTranslator.simpleOpcodeToTlaOper.get(name) match {
              case Some(oper) =>
                wrapBuiltinWithLetIn(oper)

              case None =>
                throw new SanyImporterException(s"Unexpected built-in operator $name applied as an argument")
            }
        }

      // passing a parameter that carries an operator
      case _: FormalParamNode =>
        // simply return a reference to the operator by name
        NameEx(name)

      case e =>
        throw new SanyImporterException(
            "Expected an operator definition as an argument, found: " + e
        )
    }
  }

  // Similar to LAMBDA, wrap a built-in operator with a LET-IN definition.
  // For instance, `+` becomes:
  // LET __builtin_PLUS_123(__p120, __p121) == __p120 + __p121 IN
  // __builtin_PLUS_123
  private def wrapBuiltinWithLetIn(oper: TlaOper): TlaEx = {
    val nparams = oper.arity match {
      case FixedArity(n) => n
      case _ =>
        throw new SanyImporterException("Expected an operator with a fixed number of arguments, found: " + oper.name)
    }

    // Introduce `nparams` parameters.
    // Note that they cannot be higher-order parameters, as they are used in a HO operator already.
    // We are using unique identifiers to avoid name clashes.
    val params = 1.to(nparams).map(_ => "__p" + UID.unique).toList
    // Introduce a unique name for the auxiliary operator definition
    val defName = "__%s_%s".format(oper.name, UID.unique.toString)
    // the body of our definition just applies the built-in operator `oper` to the definition parameters
    val body = OperEx(oper, params.map(name => NameEx(name)): _*)
    val auxiliaryDef = TlaOperDecl(defName, params.map(OperParam(_, 0)), body)
    // once we have defined the auxiliary operator, we return it by name
    LetInEx(NameEx(defName), auxiliaryDef)
  }

  // substitute an expression with the declarations that come from INSTANCE M WITH ...
  private def translateSubstIn(substIn: SubstInNode): TlaEx = {
    SubstTranslator(sourceStore, annotationStore, context)
      .translate(substIn, translate(substIn.getBody))
  }

  private def translateAt(node: AtNode): TlaEx = {
    // e.g., in [f EXCEPT ![42] = @ + @], we have: base = f, modifier = 42
    val base = translate(node.getAtBase)
    // This translation introduces new expressions for different occurrences of @.
    // An alternative to this would be to introduce LET at = ... IN [f EXCEPT ![0] = at + at].

    // BUGFIX: the indices in EXCEPT are packed as tuples.
    // Unpack them into multiple function applications when rewriting @, e.g., (((f[1])[2])[3]).
    translate(node.getAtModifier) match {
      case OperEx(TlaFunOper.tuple, indices @ _*) =>
        def applyOne(base: TlaEx, index: TlaEx): TlaEx = {
          OperEx(TlaFunOper.app, base, index)
        }

        indices.foldLeft(base)(applyOne)

      case e @ _ =>
        throw new SanyImporterException(
            "Unexpected index expression in EXCEPT: " + e
        )
    }
  }

  private def translateLabel(node: LabelNode): TlaEx = {
    // There seems to be no way to access the formal parameters of LabelNode.
    // For the moment, just translate the parameters as an empty list
    val labelBody = translate(node.getBody.asInstanceOf[ExprNode])
    OperEx(TlaOper.label, labelBody, ValEx(TlaStr(node.getName.toString)))
  }
}

object ExprOrOpArgNodeTranslator {
  def apply(
      sourceStore: SourceStore,
      annotationStore: AnnotationStore,
      context: Context,
      recStatus: RecursionStatus): ExprOrOpArgNodeTranslator = {
    new ExprOrOpArgNodeTranslator(
        sourceStore,
        annotationStore,
        context,
        recStatus,
    )
  }
}
