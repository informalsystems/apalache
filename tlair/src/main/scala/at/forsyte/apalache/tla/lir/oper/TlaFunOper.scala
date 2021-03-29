package at.forsyte.apalache.tla.lir.oper

/**
 * Function operators.
 */
abstract class TlaFunOper extends TlaOper {
  override def interpretation: Interpretation.Value = Interpretation.Predefined
}

object TlaFunOper {

  /**
   * A function constructor like the one for the records: [ k_1 |-> v_1, ..., k_n |-> v_n ].
   * The order of the arguments is: (k_1, v_1, ..., k_n, v_n).
   * Note that in case of records, k_1, ..., k_n are strings, that is, ValEx(TlaStr(...)), not NameEx.
   */
  object enum extends TlaFunOper {
    override def arity: OperArity = new OperArity(k => k >= 2 && k % 2 == 0)
    override val name: String = "fun-enum"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  /**
   * Define a tuple by listing its elements, i.e., < e_1, ..., e_k >.
   * One can use enum to achieve the same effect.
   */
  object tuple extends TlaFunOper {
    override val arity = AnyArity()
    override val name = "<<...>>"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  /**
   * A function application, e.g., f[e].
   * The order of the arguments is: (f, e).
   */
  object app extends TlaFunOper {
    override val arity: OperArity = FixedArity(2)
    override val name: String = "fun-app"
    override val precedence: (Int, Int) = (16, 16)
  }

  /** DOMAIN f */
  object domain extends TlaFunOper {
    override val arity: OperArity = FixedArity(1)
    override val name: String = "DOMAIN"
    override val precedence: (Int, Int) = (9, 9)
  }

  /**
   * A function constructor: [ x \in S |-> e ]. In fact, it is a lambda function (NOT the TLA+ LAMBDA!)
   * Similar to \E and \A, one can use many combinations of variables and tuples, e.g.,
   * [ x, y \in S, <<a, b>> \in S |-> e ]. We translate function constructors
   * in a list of fixed structure, where the defining expression comes first and every variables (or a tuple)
   * comes with its bounding set, e.g., (e, x, S, y, S, <<a, b>>, S).
   *
   * The arguments are always an odd-length list
   * of the following structure: body, x_1, S_1, ..., x_k, S_k.
   */
  object funDef extends TlaFunOper {
    override def arity: OperArity = new OperArity(k => k >= 3 && k % 2 == 1)
    override val name: String = "fun-def"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  /**
   * <p>A constructor of a recursive function, which is defined in TLA+ as: `f[x \in S] == ... f[y] ...`.
   * We introduce an operator that have at least 3 arguments, whose meaning is as follows:</p>
   *
   * <ul>
   * <li>function body of type TlaEx that may refer to the function via recFunRef,</li>
   * <li>NameEx(variableName1),</li>
   * <li>variable1 domain of type TlaEx.</li>
   * <li>...</li>
   * <li>NameEx(variableName_k),</li>
   * <li>variable_k domain of type TlaEx.</li>
   * </ul>
   *
   * <p>Hence, a declaration of a recursive operator looks like a nullary operator declaration,
   * whose body contains the constructor of a recursive function. The body of a recursive function may
   * refer to the function itself by using the operator recFunRef (see below).
   * Note that the output methods should convert this intermediate representation to the standard TLA+ form.</p>
   *
   * <p>There is a reason for defining a recursive function with two operators, rather than by introducing
   * a special case of `TlaDecl`. In TLA+, the operator bodies (as well as function bodies) may refer only to
   * the names that have been defined before. That is why we keep function definitions intermingled with
   * operator definitions. Moreover, we do not refer to the function name in its body, as otherwise, we would
   * run in two problems: (1) the operator name would clash with the function name, an
   * (2) the renaming transformations would try to rename the function. TLA+ forbids mutually recursive functions,
   * so it is sound to refer to the function with the operator `recFunDef`.</p>
   *
   * <p><b>Example.</b>
   * `Fact[n \in Int] == IF n <= 1 THEN 1 ELSE n * Fact[n - 1]` is translated into:</p>
   *
   * <p>
   * `TlaOperDecl("Fact", List(),
   * OperEx(recFunDef,
   * OperEx(TlaControlOper.ifThenElse,
   * (* n <= 1 *),
   * (* 1 *),
   * OperEx(Tla.ArithOper.mult,
   * NameEx("n"),
   * OperEx(TlaFunOper.app,
   * OperEx(recFunRef),
   * OperEx(TlaArithOper.minus, NameEx(n), ValEx(TlaInt(1)))
   * )
   * )
   * ),
   * NameEx("n"),
   * TlaIntSet
   * )
   * )`
   * </p>
   */
  object recFunDef extends TlaFunOper {
    override def arity: OperArity = new OperArity(k => k >= 3 && k % 2 == 1)
    override def name: String = "rec-fun-def"
    override def precedence: (Int, Int) = (100, 100) // as the operator declaration
  }

  /**
   * A reference to a recursive function inside its definition.
   *
   * @see TlaFunOper.recFunDef
   */
  object recFunRef extends TlaFunOper {

    /**
     * A unique name that can be used to refer to a recursive function inside its body.
     */
    val uniqueName = "$recFun"
    override def name: String = "rec-fun-ref"
    override def arity: OperArity = FixedArity(0)
    override def precedence: (Int, Int) = (16, 16) // as function application
  }

  /**
   * <p>A function update, e.g., [f EXCEPT ![i_1] = e_1, ![i_2] = e_2, ..., ![i_k] = e_k].
   * The order of the arguments is as follows: (f, i_1, e_1, ..., i_k, e_k).</p>
   *
   * <p>Note that all indices i_1, ..., i_k are tuples. Normally,
   * they are singleton tuples. For instance, in the expression `[f EXCEPT ![1] = 2]`, the index is `(tuple 1)`.
   * However, multiple functions can be updated in a single shot, similar to
   * multidimensional arrays (not Cartesian products!). For instance, the expression `[f EXCEPT ![1][2].name = "foo"]`
   * is equivalent to: `[ f EXCEPT ![1] = [ f[1] EXCEPT ![2] = [ f[1][2] EXCEPT !.name = "foo"] ] ]`.
   * In this case, the index is equal to `<<1, 2, "name">>`.
   * This is the design choice that comes from SANY.
   * When you write `[f EXCEPT ![<<1, 2>>] = 3]` in TLA+, expect to deal with `(except f (tuple (tuple 1 2)) 3)`.
   * When you write `[f EXCEPT ![1][2] = 3]` in TLA+, expect to deal with `(except f (tuple 1 2) 3)`.
   * </p>
   */
  object except extends TlaFunOper {
    override def arity: OperArity = new OperArity(k => k >= 3 && k % 2 == 1)
    override val name: String = "EXCEPT"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }
}
