package edu.uci.python.nodes.profiler;

import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.instrument.*;
import com.oracle.truffle.api.nodes.*;

/**
 * @author Gulfem
 */

public final class ProfilerInstrument extends Instrument {

    private long counter;

    public ProfilerInstrument() {
        counter = 0;
    }

    @Override
    public void enter(Node astNode, VirtualFrame frame) {
        counter++;
    }

    public long getCounter() {
        return counter;
    }

}
