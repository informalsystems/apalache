----- MODULE FoldDefined -----

EXTENDS Apalache

\*  Sum of all values of a set of integers
Sum(set) == LET Plus(p,q) == p + q IN FoldSet( Plus, 0, set )

\*  Re-implementation of UNION setOfSets
BigUnion(setOfSets) == LET Union(p,q) == p \union q IN FoldSet( Union, {}, setOfSets )

\*  Re-implementation of SelectSeq
SelectSeq(seq, Test(_)) == LET CondAppend(s,e) == IF Test(e) THEN Append(s, e) ELSE s
                           IN FoldSeq( CondAppend, <<>>, seq )

\*  Quantify the elements in S matching the predicate P
Quantify(S, P(_)) == LET CondCount(p,q) == p + IF P(q) THEN 1 ELSE 0
                     IN FoldSet( CondCount, 0, S )

\* The set of all values in seq
Range(seq) == LET AddToSet(S, e) == S \union {e}
              IN LET Range == FoldSeq( AddToSet, {}, seq )

\* Finds the the value that appears most often in a sequence. Returns elIfEmpty for empty sequences
Mode(seq, elIfEmpty) == LET ExtRange == Range(seq) \union {elIfEmpty}
                        IN LET CountElem(countersAndCurrentMode, e) ==
                             LET counters == countersAndCurrentMode[1]
                                 currentMode == countersAndCurrentMode[2]
                             IN LET newCounters == [ counters EXCEPT ![e] == counters[e] + 1 ]
                                IN IF newCounters[e] > newCounters[currentMode]
                                   THEN << newCounters, e >>
                                   ELSE << newCounters, currentMode >>
                           IN FoldSeq( CountElem, <<[ x \in ExtRange |-> 0 ], elIfEmpty >>, seq )[2]

\* Returns TRUE iff fn is injective
IsInjective(fn) == 
  LET SeenBefore( seenAndResult, e ) == 
    IF fn[e] \in seenAndResult[1]
    THEN [ seenAndResult EXCEPT ![2] = FALSE ]
    ELSE [ seenAndResult EXCEPT ![1] = seenAndResult[1] \union {fn[e]} ]
  IN FoldSet( SeenBefore, << {}, TRUE >>, DOMAIN fn )[2]

================================