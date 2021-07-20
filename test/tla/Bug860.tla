------------------------------------ MODULE Bug860 -----------------------------
\* Snowcat was raising an MatchError when handling multi-argument operator
\* annotations that misused ->in place of =>.
\*
\* See https://github.com/informalsystems/apalache/issues/860

\* @type: (Int, Int) -> Bool;
Op(n, m) == TRUE
===============================================================================
