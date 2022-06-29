---------------------------- MODULE TestVariants -------------------------------
(*
 * Functional tests for operators over variants.
 * We introduce a trivial state machine and write tests as state invariants.
 *)

EXTENDS Integers, FiniteSets, Apalache, Variants

Init == TRUE
Next == TRUE

(* DEFINITIONS *)

\* A variant that belongs to a more general type.
\*
\* @type: A(Int) | B({ value: Str });
VarA == Variant("A", 1)

\* A variant that belongs to a more general type.
\*
\* @type: A(Int) | B({ value: Str });
VarB == Variant("B", [ value |-> "hello" ])

\* A singleton variant, e.g., to be used with a filter.
\*
\* @type: C({ value: Str });
VarC == Variant("C", [ value |-> "world" ])

TestVariant ==
    VarA \in { VarA, VarB }

TestVariantFilter ==
    \E v \in VariantFilter("B", { VarA, VarB }):
        v.value = "hello"

TestVariantUnwrap ==
    \* We could just pass "world", without wrapping it in a record.
    \* But we want to see how it works with records too.
    VariantUnwrap("C", VarC) = [ value |-> "world" ]

TestVariantGetUnsafe ==
    \* The unsafe version gives us only a type guarantee.
    VariantGetUnsafe("A", VarB) \in Int

TestVariantGetOrElse ==
    \* When the tag name is different from the actual one, return the default value.
    VariantGetOrElse("A", VarB, 12) = 12

TestVariantMatch ==
    VariantMatch(
        "A",
        VarA,
        LAMBDA i: i > 0,
        LAMBDA v: FALSE
    )

AllTests ==
    /\ TestVariant
    /\ TestVariantFilter
    /\ TestVariantUnwrap
    /\ TestVariantGetUnsafe
    /\ TestVariantGetOrElse
    \* Disabled as unsupported by the model checker yet
    \*/\ TestVariantMatch

===============================================================================
