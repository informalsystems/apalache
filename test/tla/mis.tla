-------------------------------- MODULE mis --------------------------------
EXTENDS Integers, TLC, Variants

CONSTANT
    \* @type: Bool;
    HAS_BUG

IntroBug ==
    HAS_BUG = TRUE

AvoidBug ==
    HAS_BUG = FALSE

N == 3
N4 == 81
Nodes == 1..N

(*
  @typeAlias: MESSAGE =
      Val({ src: Int, val: Int })
    | Winner(Int)
    | Loser(Int)
    ;
 *)
mis_typedefs == TRUE

\* @type: (Int, Int) => MESSAGE;
Val(src, val) == Variant("Val", [ src |-> src, val |-> val ])

\* @type: Int => MESSAGE;
Winner(val) == Variant("Winner", val)

\* @type: Int => MESSAGE;
Loser(val) == Variant("Loser", val)

VARIABLES
    \* @type: Set(<<Int, Int>>);
    Nb,
    \* @type: Int;
    round,
    \* @type: Int -> Int;
    val,
    \* @type: Int -> Bool;
    awake,
    \* @type: Int -> Set(Int);
    rem_nbrs,
    \* @type: Int -> Str;
    status,
    \* @type: Int -> Set(MESSAGE);
    msgs

Pred(n) == IF n > 1 THEN n - 1 ELSE N
Succ(n) == IF n < N THEN n + 1 ELSE 1

Init == \*/\ Nb = [ n \in Nodes |-> {Pred(n), Succ(n)} ]
        /\ Nb \in SUBSET(Nodes \X Nodes)
        /\ \A e \in Nb: <<e[2], e[1]>> \in Nb \* the graph is undirected
        /\ round = 1
        /\ val \in [Nodes -> 1..N4]
        /\ awake = [n \in Nodes |-> TRUE]
        /\ rem_nbrs = [ u \in Nodes |-> { v \in Nodes : <<u, v>> \in Nb}]
        /\ status = [n \in Nodes |-> "unknown"]
        /\ msgs = [n \in Nodes |-> {}]
    
Senders(u) == {v \in Nodes: awake[v] /\ u \in rem_nbrs[v] }

SentValues(u) == { Val(w, val'[w]): w \in Senders(u) }
    
IsWinner(u) ==
    \A m \in VariantFilter("Val", msgs'[u]):
        IF HAS_BUG
        THEN TRUE \* introduce a buggy condition
        ELSE val'[u] > m.val
    
Round1 ==
    /\ round = 1
    /\ val' \in [Nodes -> 1..N4] \* non-determinism, no randomness
    /\ msgs' = [u \in Nodes |-> SentValues(u)]
    /\ status' = [n \in Nodes |->
        IF awake[n] /\ IsWinner(n) THEN "winner" ELSE status[n]]
    /\ UNCHANGED <<rem_nbrs, awake>>

SentWinners(u) ==
    IF \E w \in Senders(u): awake[w] /\ status[w] = "winner"
    THEN { Winner(u) }
    ELSE {}

IsLoser(u) == VariantFilter("Winner", msgs'[u]) /= {}
    
Round2 ==
    /\ round = 2
    /\ msgs' = [u \in Nodes |-> SentWinners(u)]
    /\ status' = [n \in Nodes |->
        IF awake[n] /\ IsLoser(n) THEN "loser" ELSE status[n]]
    /\ UNCHANGED <<rem_nbrs, awake, val>>

SentLosers(u) ==
    { Loser(s):
        s \in {w \in Senders(u): awake[w] /\ status[w] = "loser"} }

ReceivedLosers(u) ==
    VariantFilter("Loser", msgs'[u])
    
Round3 ==
    /\ round = 3 
    /\ msgs' = [u \in Nodes |-> SentLosers(u)]
    /\ awake' = [n \in Nodes |->
        IF status[n] \notin {"winner", "loser"} THEN TRUE ELSE FALSE]
    /\ rem_nbrs' = [u \in Nodes |-> rem_nbrs[u] \ ReceivedLosers(u)] 
    /\ UNCHANGED <<status, val>>

Next ==
    round' = 1 + (round % 3) /\ (Round1 \/ Round2 \/ Round3) /\ UNCHANGED Nb
    
IsIndependent ==
    \A edge \in Nb:
        (status[edge[1]] /= "winner" \/ status[edge[2]] /= "winner")

Terminated == \A n \in Nodes: awake[n] = FALSE

=============================================================================
\* Modification History
\* Last modified Thu Jul 19 21:28:53 BST 2018 by igor
\* Created Sun Jul 15 17:03:47 CEST 2018 by igor
