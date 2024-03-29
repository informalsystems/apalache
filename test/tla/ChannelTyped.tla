---------------------- MODULE ChannelTyped ----------------------------
\* This is a typed version of the example from Specifying Systems:
\* https://github.com/tlaplus/Examples/blob/master/specifications/SpecifyingSystems/FIFO/Channel.tla
EXTENDS Naturals
\* ANCHOR: declarations
CONSTANT
    \* @type: Set(DATUM);
    Data
VARIABLE
    \* @type: { val: DATUM, rdy: Int, ack: Int };
    chan 
\* ANCHOR_END: declarations

TypeInvariant  ==  chan \in [val : Data,  rdy : {0, 1},  ack : {0, 1}]
-----------------------------------------------------------------------
Init  ==  /\ TypeInvariant
          /\ chan.ack = chan.rdy 

Send(d) ==  /\ chan.rdy = chan.ack
            /\ chan' = [chan EXCEPT !.val = d, !.rdy = 1 - @]

Rcv     ==  /\ chan.rdy # chan.ack
            /\ chan' = [chan EXCEPT !.ack = 1 - @]

Next  ==  (\E d \in Data : Send(d)) \/ Rcv

Spec  ==  Init /\ [][Next]_chan
-----------------------------------------------------------------------
THEOREM Spec => []TypeInvariant
=======================================================================
