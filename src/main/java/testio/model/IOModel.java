package testio.model;

import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;

import java.util.Objects;

/**
 * Represents an input/output.
 * Wrapper for the IO value and the TraceNode that it was obtained from.
 */
public class IOModel {
    private VarValue value;
    private TraceNode node;

    public IOModel(VarValue value, TraceNode node) {
        this.value = value;
        this.node = node;
    }

    public VarValue getValue() {
        return value;
    }

    public TraceNode getTraceNode() {
        return node;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof IOModel)) {
            return false;
        }
        IOModel otherIOModel = (IOModel) other;
        return value.equals(otherIOModel.getValue());
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
