----- MODULE NonFoldDefined -----

EXTENDS Apalache

RECURSIVE Sum(_)
Sum(S) == IF S = {} 
          THEN 0
          ELSE LET x == CHOOSE y \in S : TRUE
               IN  x + Sum(S \ {x})

RECURSIVE BigUnion(_)
BigUnion(setOfSets) == IF setOfSets = {} 
                       THEN {}
                       ELSE LET S == CHOOSE x \in setOfSets : TRUE
                            IN  S \union BigUnion(setOfSets \ {x})

RECURSIVE SelectSeq(_,_)
SelectSeq(seq, Test(_)) == IF seq = <<>>
                           THEN <<>>
                           ELSE LET tail == SelectSeq(Tail(seq), Test)
                                IN IF Test( Head(seq) )
                                   THEN <<Head(seq)>> \o tail
                                   ELSE tail

RECURSIVE Quantify(_,_)
Quantify(S, P(_)) == IF S = {} 
                     THEN 0
                     ELSE LET x == CHOOSE y \in S : TRUE
                          IN (IF P(x) THEN 1 ELSE 0) + Quantify(S \ {x}, P) 

RECURSIVE Range(_)
Range(seq) == IF seq = <<>>
              THEN {}
              ELSE {Head(seq)} \union Range(Tail(seq))

Mode(seq, elIfEmpty) == IF seq = <<>>
                        THEN elIfEmpty 
                        ELSE LET numOf(p) == Quantify( DOMAIN seq, LAMBDA q: q = p )
                             IN CHOOSE x \in Range(seq): \A y \in Range(seq) : numOf(x) >= numOf(y)

IsInjective(fn) == \A a,b \in DOMAIN fn : fn[a] = fn[b] => a = b

================================