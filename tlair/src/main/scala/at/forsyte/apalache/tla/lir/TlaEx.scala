package at.forsyte.apalache.tla.lir

import at.forsyte.apalache.tla.lir.io.UTFPrinter
import at.forsyte.apalache.tla.lir.oper.{FixedArity, TlaOper}

/**
 * An abstract TLA+ expression. Note that the class is sealed, so we allow only a limited set of values.
 * Importantly, `TlaEx` accepts an implicit type tag.
 */
sealed abstract class TlaEx(implicit val typeTag: TypeTag)
    extends Identifiable with Serializable with TypeTagged[TlaEx] {

  // TODO: introduce a type class to print expressions with a custom printer (see scala cats)
  override def toString: String = UTFPrinter(this)

  def toSimpleString: String = ""
}

/**
 * This is a special expression that indicates that this expression does not have a meaningful value.
 * For instance, this expression can be used as the body of a library operator, which by default have
 * gibberish definitions by SANY.
 * We could use Option[TlaEx], but that would introduce unnecessary many pattern matches, as NoneEx is rare.
 * NullEx is always untyped.
 */
object NullEx extends TlaEx()(typeTag = Untyped()) with Serializable {
  override def toSimpleString: String = toString

  // null expressions always carry the Untyped tag
  override def withTag(newTypeTag: TypeTag): TlaEx = this
}

/**
 * A constant TLA+ value, which is usually a literal such as: 42, TRUE, "foo", BOOLEAN.
 * Importantly, `ValEx` accepts an implicit type tag.
 */
case class ValEx(value: TlaValue)(implicit typeTag: TypeTag) extends TlaEx with Serializable {
  override def toSimpleString: String = value.toString

  override def withTag(newTypeTag: TypeTag): ValEx = ValEx(value)(newTypeTag)
}

/**
 * Referring by name to a variable, constant, operator, etc.
 * Importantly, `NameEx` accepts an implicit type tag.
 */
case class NameEx(name: String)(implicit typeTag: TypeTag) extends TlaEx with Serializable {
  override def toSimpleString: String = name

  override def withTag(newTypeTag: TypeTag): NameEx = NameEx(name)(newTypeTag)
}

/*
 * A let-in definition, which is part of TLA+ syntax.
 * Importantly, `LetInEx` accepts an implicit type tag.
 */
case class LetInEx(body: TlaEx, decls: TlaOperDecl*)(implicit typeTag: TypeTag) extends TlaEx with Serializable {
  override def toSimpleString: String = s"LET ${decls.mkString(" ")} IN $body"

  override def withTag(newTypeTag: TypeTag): LetInEx = LetInEx(body, decls: _*)(newTypeTag)
}

/**
 * Application of a built-in operator. The standard operator `TlaOper.apply` allows us
 * to apply a user-defined operator (defined with `TlaOperDecl`) or an operator that is passed via a parameter
 * (that is, `OperFormalParam`). Importantly, `OperEx` accepts an implicit type tag.
 */
case class OperEx(oper: TlaOper, args: TlaEx*)(implicit typeTag: TypeTag) extends TlaEx with Serializable {
  require(oper.isCorrectArity(args.size),
      "unexpected arity %d in %s applied to %s".format(args.size, oper.name, args.map(_.toString) mkString ", "))

  require(oper.permitsArgs(args),
      "The invariant of %s is violated by the arguments: %s".format(oper.name, args.map(_.toString) mkString ", "))

  override def toSimpleString: String = {
    oper.arity match {
      case FixedArity(n) => {
        n match {
          case 1 => args.head.toSimpleString + oper.name
          case 2 => args.head.toSimpleString + " " + oper.name + " " + args.tail.head.toSimpleString
          case _ => oper.name + "(" + args.map(_.toSimpleString).mkString(", ") + ")"
        }
      }
      case _ => oper.name + "(" + args.map(_.toSimpleString).mkString(", ") + ")"

    }
  }

  override def withTag(newTypeTag: TypeTag): OperEx = {
    OperEx(oper, args: _*)(newTypeTag)
  }
}
