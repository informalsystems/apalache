---------------------------- MODULE Inline -------------------------------
VARIABLE
    \* @type: Int;
    x

A == 3

B == A

Init == x = B

Next == UNCHANGED x
==========================================================================
