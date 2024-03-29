package at.forsyte.apalache.tla.lir.oper

/**
 * Sequence operators.
 *
 * @author
 *   Jure Kukovec
 *
 * Created by jkukovec on 11/17/16.
 */

abstract class TlaSeqOper extends TlaOper {
  override def interpretation: Interpretation.Value = Interpretation.StandardLib
}

/**
 * The standard module of Sequences. Note that there is no standard constructor for a sequence. Use the tuples
 * constructor, @see TlaOper.
 */
object TlaSeqOper {

  object head extends TlaSeqOper {
    override val arity = FixedArity(1)
    override val name = "Sequences!Head"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  object tail extends TlaSeqOper {
    override val arity = FixedArity(1)
    override val name = "Sequences!Tail"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  object append extends TlaSeqOper {
    override val arity = FixedArity(2)
    override val name = "Sequences!Append"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  object concat extends TlaSeqOper {
    override val arity = FixedArity(2)
    override val name = "Sequences!Concat"
    override val precedence: (Int, Int) = (13, 13)
  }

  object len extends TlaSeqOper {
    override val arity = FixedArity(1)
    override val name = "Sequences!Len"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  object subseq extends TlaSeqOper {
    override val arity = FixedArity(3)
    override val name = "Sequences!SubSeq"
    override val precedence: (Int, Int) = (16, 16) // as the function application
  }

  // This operator is rewired in __rewire_sequences_in_apalache.tla.
  // We keep it for a reference. Do not construct its instance.
  object selectseq extends TlaSeqOper {
    override val arity = FixedArity(2)
    override val name = "Sequences!SelectSeq"
    override val precedence: (Int, Int) = (16, 16) // as the function application

    require(false, "This operator is rewired in __rewire_sequences_in_apalache.tla")
  }
}
