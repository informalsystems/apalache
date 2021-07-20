Folding in a nutshell
==========================

Apalache natively implements two operators users might be familiar with from the [community modules](https://github.com/tlaplus/CommunityModules) or functional programming. Those operators are `FoldSet` and `FoldSeq`. This brief introduction to fold operators highlights the following:

  1. What are the semantics of fold operators?
  1. How do I use these operators in Apalache?
  1. Should I use folding or recursion?
  1. Examples of common operators defined with folds

### Syntax

The syntax of the fold operators is as follows:

```tla
\* @type: ( (a, b) => a, a, Set(b) ) => a
FoldSet( operator, base, set )

\* @type: ( (a, b) => a, a, Seq(b) ) => a
FoldSeq( operator, base, seq )
```

### Semantics of fold operators

Folding refers to iterative application of a binary operator over a collection. Given an operator `Op`, a base value `b` and a collection of values `C`, the definition of folding `Op` over `C` starting with `b` depends on the type of the collection `C`.

#### Semantics of `FoldSeq`

In the case of folding over sequences, `C` is a sequence `<<a_1, ..., a_n>>`. Then, `FoldSeq( Op, b, C )` is defined as follows:

  1. If `C` is empty, then `FoldSeq( Op, b, <<>> ) = b`, regardless of `Op`
  1. If `C` is nonempty, we establish a recursive relation between folding over `C` and folding over `Tail(C)` in the following way: `FoldSeq( Op, b, C ) = FoldSeq( Op, Op(b, Head(C)), Tail(C) )`.

#### Semantics of `FoldSet`

In the case of folding over sequences, `C` is a set `{a_1, ..., a_n}`. Then, `FoldSet( Op, b, C )` is defined as follows:

  1. If `C` is empty, then `FoldSeq( Op, b, {} ) = b`, regardless of `Op`
  1. If `C` is nonempty, we establish a recursive relation between folding over `C` and folding over some subset of `C` in the following way: `FoldSeq( Op, b, C ) = FoldSeq( Op, Op(b, x), C \ {x} )`, where `x = CHOOSE y \in C: TRUE`. Note that Apalache selects `x` nondeterministically.

Note that the above are definitions of a _left fold_ in the literature. Apalache does not implement a right fold.

For example, if `C` is the sequence `<<x,y,z>>`, the result is equal to `Op( Op( Op(b, x), y), z)`. If `C = {x,y}`, the result is either `Op( Op(b, x), y)` or `Op( Op(b, y), x)`. Because the order of elements selected is nondeterministic in the case of sets, users should be careful, as the result is only uniquely defined in the case that the operator is both associative (`Op(Op(a,b),c) = Op(a,Op(b,c))`) and commutative (`Op(a,b) = Op(b,a)`). 

For example, consider the operator `Op(p,q) == 2 * p + q`, which is noncommutative, and the set `S = {1,2,3}`. The value of `FoldSet(Op, 0, S)` depends on the order in which Apalache selects elements from S:


| Order | FoldSet value |
| --- | --- |
| 1 -> 2 -> 3 | 11 | 
| 1 -> 3 -> 2 | 12 | 
| 2 -> 1 -> 3 | 13 | 
| 2 -> 3 -> 1 | 15 | 
| 3 -> 1 -> 2 | 16 | 
| 3 -> 2 -> 1 | 17 | 

Because Apalache chooses nondeterministically, all of the above results are possible outcomes, unlike in the case of the fixed-but-unknown order that would arise from a deterministic choice.

### Using fold operators in Apalache

As shown by the type signature, Apalache permits a very general form of folding, where the types of the collection elements and the type of the base element/return-type of the operator do not have to match. Again, we urge users to exercise caution when using `FoldSet` with an operator, for which the types `a` and `b` are different, as such operators cannot be commutative or associative, and therefore the result is not guaranteed to be unique and predictable. 

The other component of note is `operator`, the _name_ (not definition) of some binary operator, which is available in this context. The following are examples of valid uses of folds:

```tla
PlusOne(p,q) == p + q + 1
X == FoldSet( PlusOne, 0, {1,2,3} ) \* X = 9 

X == LET Count(p,q) == p + 1 IN FoldSeq( Count, 0, <<1,2,3>> ) \* X = 3
```

while these next examples are considered invalid:

```tla
\* LAMBDAS in folds are not supported by Apalache
\* Use a local LET-IN operator instead 
X == FoldSet( LAMBDA p,q: p + q, 0, {1,2,3} ) 

\* Built-in operators cannot be called by name in Apalache
\* Define a LET-IN operator Plus(p,q) == p + q instead
X == FoldSet( + , 0, {1,2,3} )
```

Local LET definitions can also be used as closures:

```
A(x) == LET PlusX(p,q) == p + q + x IN FoldSeq( PlusX, 0, <<1,2,3>> )

X == A(1) \* X = 9
```

### Folding VS recursion

While TLA+ allows users to write arbitrary recursive operators, they are, in our experience, mostly used to implement collection traversals. Consider the following implementations of a `Max` operator, which returns the largest element of a sequence:

```tla
\* Max(<<>>) = -inf, but integers are unbounded in TLA+, 
\* so there is no natural minimum like MIN_INT in programming languages
CONSTANT negInf 

RECURSIVE MaxRec(_)
MaxRec(seq) == IF seq = <<>>
               THEN negInf
               ELSE LET tailMax == MaxRec(Tail(seq))
                    IN IF tailMax > Head(seq)
                       THEN tailMax
                       ELSE Head(seq)

MaxFold(seq) == LET Max(p,q) == IF p > q THEN p ELSE q
                IN FoldSeq( Max, negInf, seq )
```

The first advantage of the fold implementation, we feel, is that it is much more clear and concise. It also does not require a termination condition, unlike the recursive case.
One inherent problem of using recursive operators with a symbolic encoding, is the inability to estimate termination.
While it may be immediately obvious to a human, that `MaxRec` terminates after no more than `Len(seq)` steps, automatic termination analysis is, in general, a rather complex and incomplete form of static analysis.
Apalache addresses this by finitely unrolling recursive operators and requires users to provide unroll limits (`UNROLL_LIMIT_MaxRec == ...`), which serve as a static upper bound to the number of recursive re-entries, because in general, recursive operators may take an unpredictable number of steps (e.g. computing the Collatz sequence) or never terminate at all.
Consider a minor adaptation of the above example, where the author made a mistake in implementing the operator:

```tla
RECURSIVE MaxRec(_)
MaxRec(seq) == IF seq = <<>>
               THEN negInf
               ELSE LET tailMax == MaxRec( seq ) \* forgot Tail!
                    IN IF tailMax > Head(seq)
                       THEN tailMax
                       ELSE Head(seq)
```

Now, `MaxRec` never terminates, but spotting this error might not be trivial at a glance. This is where we believe folds hold the second advantage: `FoldSet` and `FoldSeq` *always terminate* in `Cardinality(set)` or `Len(seq)` steps, and each step is simple to describe, as it consists of a single operator application.

In fact, the vast majority of the traditionally recursive operators can be equivalently rewritten as folds, for example:
```tla
RECURSIVE Cardinality(_)
Cardinality(set) == IF set = {}
                    THEN 0
                    ELSE LET x == CHOOSE y \in set: TRUE
                         IN 1 + Cardinality( set \ {x} )

CardinalityFold(set) == LET Count(p,q) == p + 1 \* the value of q, the set element, is irrelevant
                        IN FoldSet( Count, 0, set )
```

Notice that, in the case of sets, picking an arbitrary element `x`, to remove from the set at each step, utilizes the `CHOOSE` operator. This is a common trait shared by many operators that implement recursion over sets. 
Since the introduction of folds, the use of `CHOOSE` in Apalache is heavily discouraged as it is both inefficient, as well as nondeterministic (unlike how `CHOOSE` is defined in TLA+ literature). For details, see the discussion in [issue 841](https://github.com/informalsystems/apalache/issues/841).

So the third advantage of using folds is the  ability to, almost always, avoid using the `CHOOSE` operator.

The downside of folding, compared to general recursion, is the inability to express non-primitively recursive functions.
For instance, one cannot define the Ackermann function, as a fold. We find that in most specifications, this is not something the users would want to implement anyway, so in practice, we believe it is almost always better to use fold over recursive functions.

### Folding VS quantification and `CHOOSE`

Often, folding can be used to select a value from a collection, which could alternatively be described by a predicate and selected with `CHOOSE`. Let us revisit the `MaxFold` example:

```tla
MaxFold(seq) == LET Max(p,q) == IF p > q THEN p ELSE q
                IN FoldSeq( Max, negInf, seq )
```

The fold-less case could, instead of using recursion, compute the maximum as follows:

```tla
MaxChoose(seq) == LET Range == {seq[i] : i \in DOMAIN seq} IN CHOOSE m \in Range : \A n \in Range : m >= n
```

The predicate-based approach might result in a more compact specification, but that is because specifications have no notion of execution or complexity. Automatic verification tools, such as Apalache, the job of which includes finding witnesses to predicates, can work much faster with the fold approach. The reason is that _evaluating_ `CHOOSE x \in S : \A y \in S: P(x,y)` is quadratic in the size of `S` (in a symbolic approach this is w.r.t. the number of constraints). For each candidate `x`, the entire set `S` must be tested for `P(x,_)`. On the other hand, the fold approach is linear in the size of `S`, since each element is visited exactly once. 

In addition, the fold approach admits no undefined behavior. If, in the above example, `seq` was an empty sequence, the value of the computed maximum depends on the value of `CHOOSE x \in {}: TRUE`, which is undefined in TLA+, while the fold-based approach allows the user to determine behavior in that scenario (via the initial value).

Our general advice is to use folds over `CHOOSE` with quantified predicates wherever possible, if you're willing accept a very minor increase in specification size in exchange for a decrease in Apalache execution time, or, if you wish to avoid `CHOOSE` over empty sets resulting in undefined behavior.

### Examples: The versatility of folds

Here we give some examples of common operators, implemented using folds:

```tla
{{#include ../../../test/tla/FoldDefined.tla::}}
```

For the sake of comparison, we rewrite the above operators using recursion, `CHOOSE` or quantification:

```tla
{{#include ../../../test/tla/NonFoldDefined.tla::}}
```

In most cases, recursive operators are much more verbose, and the operators using `CHOOSE` and/or quantification mask double iteration (and thus have quadratic complexity). 
For instance, the evaluation of the fold-less `IsInjective` operator actually requires the traversal of all domain pairs, instead of the single domain traversal with fold.
In particular, `Mode`, the most verbose among the fold-defined operators, is still very readable (most LET-IN operators are introduced to improve readability, at the cost of verbosity) and quite efficient, as its complexity is linear w.r.t. the length of the sequence (the mode could also be computed directly, without a sub-call to `Range`, but the example would be more difficult to read), unlike the variant with `CHOOSE` and `\A`, which is quadratic.
