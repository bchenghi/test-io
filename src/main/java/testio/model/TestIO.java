package testio.model;

import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;

import java.util.List;

public class TestIO {
    private List<IOModel> inputs;
    private IOModel output;

    private boolean hasPassed = false;

    public TestIO(List<IOModel> inputs, IOModel output) {
        this.inputs = inputs;
        this.output = output;
    }

    public void setHasPassed(boolean hasPassed) {
        this.hasPassed = hasPassed;
    }

    public List<IOModel> getInputs() {
        return inputs;
    }

    public VarValue getOutput() {
        return output.getValue();
    }

    public TraceNode getOutputNode() {
        return output.getTraceNode();
    }

    public boolean hasPassed() {
        return hasPassed;
    }
}
