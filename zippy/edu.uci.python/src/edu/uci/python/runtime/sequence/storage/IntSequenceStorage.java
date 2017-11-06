/*
 * Copyright (c) 2013, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.uci.python.runtime.sequence.storage;

import java.io.PrintStream;
import java.util.Arrays;

import org.python.core.Py;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import edu.uci.python.runtime.PythonOptions;
import edu.uci.python.runtime.sequence.SequenceUtil;

public final class IntSequenceStorage extends BasicSequenceStorage {

    private int[] values;

    public IntSequenceStorage() {
        values = new int[]{};
    }

    public IntSequenceStorage(int[] elements) {
        this.values = elements;
        capacity = values.length;
        length = elements.length;
    }

    @Override
    protected void increaseCapacityExactWithCopy(int newCapacity) {
        values = Arrays.copyOf(values, newCapacity);
        capacity = values.length;
    }

    @Override
    protected void increaseCapacityExact(int newCapacity) {
        values = new int[newCapacity];
        capacity = values.length;
    }

    @Override
    public SequenceStorage copy() {
        return new IntSequenceStorage(Arrays.copyOf(values, length));
    }

    @Override
    public Object[] getInternalArray() {
        /**
         * Have to box and copy.
         */
        Object[] boxed = new Object[length];

        for (int i = 0; i < length; i++) {
            boxed[i] = values[i];
        }

        return boxed;
    }

    public int[] getInternalIntArray() {
        return values;
    }

    @Override
    public Object[] getCopyOfInternalArray() {
        return getInternalArray();
    }

    @Override
    public Object getItemNormalized(int idx) {
        return getIntItemNormalized(idx);
    }

    public int getIntItemNormalized(int idx) {
        try {
            return values[idx];
        } catch (ArrayIndexOutOfBoundsException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw Py.IndexError("list index out of range");
        }
    }

    @Override
    public void setItemNormalized(int idx, Object value) throws SequenceStoreException {
        if (value instanceof Integer) {
            setIntItemNormalized(idx, (int) value);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void setIntItemNormalized(int idx, int value) {
        try {
            values[idx] = value;
        } catch (ArrayIndexOutOfBoundsException e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw Py.IndexError("list assignment index out of range");
        }
    }

    @Override
    public void insertItem(int idx, Object value) throws SequenceStoreException {
        if (value instanceof Integer) {
            insertIntItem(idx, (int) value);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void insertIntItem(int idx, int value) {
        ensureCapacity(length + 1);

        // shifting tail to the right by one slot
        for (int i = values.length - 1; i > idx; i--) {
            values[i] = values[i - 1];
        }

        values[idx] = value;
        length++;
    }

    @Override
    public SequenceStorage getSliceInBound(int start, int stop, int step, int sliceLength) {
        int[] newArray = new int[sliceLength];

        if (step == 1) {
            System.arraycopy(values, start, newArray, 0, sliceLength);
            return new IntSequenceStorage(newArray);
        }

        for (int i = start, j = 0; j < sliceLength; i += step, j++) {
            newArray[j] = values[i];
        }

        return new IntSequenceStorage(newArray);
    }

    @Override
    public void setSliceInBound(int start, int stop, int step, SequenceStorage sequence) throws SequenceStoreException {
        if (sequence instanceof IntSequenceStorage) {
            setIntSliceInBound(start, stop, step, (IntSequenceStorage) sequence);
        } else {
            throw new SequenceStoreException();
        }
    }

    public void setIntSliceInBound(int start, int stop, int step, IntSequenceStorage sequence) {
        int otherLength = sequence.length();

        // range is the whole sequence?
        if (start == 0 && stop == length) {
            values = Arrays.copyOf(sequence.values, otherLength);
            length = otherLength;
            minimizeCapacity();
            return;
        }

        ensureCapacity(stop);

        for (int i = start, j = 0; i < stop; i += step, j++) {
            values[i] = sequence.values[j];
        }

        length = length > stop ? length : stop;
    }

    @Override
    public void delSlice(int start, int stop) {
        if (stop == SequenceUtil.MISSING_INDEX) {
            length = start;
        }
    }

    @Override
    public void delItemInBound(int idx) {
        if (values.length - 1 == idx) {
            popInt();
        } else {
            popInBound(idx);
        }
    }

    @Override
    public Object popInBound(int idx) {
        int pop = values[idx];

        for (int i = idx; i < values.length - 1; i++) {
            values[i] = values[i + 1];
        }

        length--;
        return pop;
    }

    public int popInt() {
        int pop = values[capacity - 1];
        length--;
        return pop;
    }

    @Override
    public int index(Object value) {
        if (value instanceof Integer) {
            return indexOfInt((int) value);
        } else {
            return super.index(value);
        }

    }

    @ExplodeLoop
    public int indexOfInt(int value) {
        for (int i = 0; i < length; i++) {
            if (values[i] == value) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void append(Object value) throws SequenceStoreException {
        if (value instanceof Integer) {
            appendInt((int) value);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void appendInt(int value) {
        ensureCapacity(length + 1);
        values[length] = value;
        length++;
    }

    @Override
    public void extend(SequenceStorage other) throws SequenceStoreException {
        if (other instanceof IntSequenceStorage) {
            extendWithIntStorage((IntSequenceStorage) other);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

// @ExplodeLoop
    public void extendWithIntStorage(IntSequenceStorage other) {
        int extendedLength = length + other.length();
        ensureCapacity(extendedLength);
        int[] otherValues = other.values;

        for (int i = length, j = 0; i < extendedLength; i++, j++) {
            values[i] = otherValues[j];
        }

        length = extendedLength;
    }

    @ExplodeLoop
    @Override
    public void reverse() {
        int head = 0;
        int tail = length - 1;
        int middle = (length - 1) / 2;

        for (; head <= middle; head++, tail--) {
            int temp = values[head];
            values[head] = values[tail];
            values[tail] = temp;
        }
    }

    @ExplodeLoop
    @Override
    public void sort() {
        int[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        values = copy;
        minimizeCapacity();
    }

    @Override
    public SequenceStorage generalizeFor(Object value) {
        if (value instanceof Long) {
            if (PythonOptions.TraceSequenceStorageGeneralization) {
                PrintStream ps = System.out;
                ps.println("[ZipPy]" + this + " generalizing to LongSequenceStorage");
            }
            return new LongSequenceStorage(getInternalIntArray());
        }

        if (PythonOptions.TraceSequenceStorageGeneralization) {
            PrintStream ps = System.out;
            ps.println("[ZipPy]" + this + " generalizing to ObjectSequenceStorage");
        }

        return new ObjectSequenceStorage(getInternalArray());
    }

    @Override
    public Object getIndicativeValue() {
        return 0;
    }

    @ExplodeLoop
    @Override
    public boolean equals(SequenceStorage other) {
        if (other.length() != length() || !(other instanceof IntSequenceStorage)) {
            return false;
        }

        int[] otherArray = ((IntSequenceStorage) other).getInternalIntArray();
        for (int i = 0; i < length(); i++) {
            if (values[i] != otherArray[i]) {
                return false;
            }
        }

        return true;
    }

}
