package testio;

import testio.utils.ASTNodeRetriever;
import testio.utils.ProjectParser;
import microbat.model.BreakPoint;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.ReferenceValue;
import microbat.model.value.VarValue;
import microbat.model.variable.ArrayElementVar;
import microbat.model.variable.FieldVar;
import microbat.model.variable.LocalVar;
import microbat.model.variable.Variable;
import testio.model.IOModel;
import tracecollection.model.InstrumentationResult;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodInvocation;
import testio.model.TestIO;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class IOObtainer {

    private static Set<String> assertionClasses = new HashSet<>(Arrays.asList("org.junit.Assert", "org.junit.jupiter.api.Assertions", "org.testng.Assert"));
    public static List<IOModel> getTestOutputs(InstrumentationResult instrumentationResult,
                                               InstrumentationResult instrumentationResultWithAssertions) {

        Trace trace = instrumentationResult.getMainTrace();
        Trace traceWithAssertion = instrumentationResultWithAssertions.getMainTrace();
        List<TraceNode> executionList = trace.getExecutionList();
        List<TraceNode> executionListWithAssertions = traceWithAssertion.getExecutionList();
        List<IOModel> result = new ArrayList<>();
        if (executionList.isEmpty()) {
            return result;
        }
        // Store the outputs and nodesToIgnore. Then rerun the loop, and only obtain input for last one.
        for (int i = 0; i < executionList.size(); i++) {
            TraceNode traceNode = executionList.get(i);
            TraceNode traceNodeWithAssertion = executionListWithAssertions.get(i);
            BreakPoint breakPoint = traceNode.getBreakPoint();
            String currentMethodName = breakPoint.getMethodName();
            if (currentMethodName.equals("<init>") || currentMethodName.equals("<clinit>")) {
                continue;
            }
            boolean shouldCallGetOutput = isOutputNode(traceNodeWithAssertion);
            if (shouldCallGetOutput) {
                IOModel output = getOutput(traceNode, traceNodeWithAssertion);
                result.add(output);
            }
        }

        // If crashed, obtain the last read/written var
        if (instrumentationResult.hasThrownException()) {
            IOModel outputForCrashingTrace = getOutputForCrashingTrace(instrumentationResult);
            result.add(outputForCrashingTrace);
        }
        return result;
    }

    public static IOModel getOutputForCrashingTrace(InstrumentationResult instrumentationResult) {
        Trace trace = instrumentationResult.getMainTrace();
        List<TraceNode> executionList = trace.getExecutionList();
        int lastIdx = executionList.size() - 1;
        int idx = lastIdx;
        int numOfNodesToCheck = 2;
        String programMsg = instrumentationResult.getProgramMsg();
        String stringValOfOutput = programMsg.substring(programMsg.indexOf(';') + 1);
        while (idx > lastIdx - numOfNodesToCheck) {
            TraceNode current = executionList.get(idx);
            List<VarValue> varValues = new ArrayList<>(current.getWrittenVariables());
            varValues.addAll(current.getReadVariables());
            for (VarValue varValue : varValues) {
                // Array element, index out of bounds, can use varID to check idx.
                // e.g. varID = 1365008457[-1], check if [-1] inside varID
                boolean shouldSkip = true;
                if (stringValOfOutput.equals(varValue.getStringValue())) {
                    shouldSkip = false;
                } else if (varValue instanceof ReferenceValue) {
                    Variable var = varValue.getVariable();
                    if (var instanceof ArrayElementVar) {
                        if (var.getVarID().contains("[" + stringValOfOutput + "]")) {
                            shouldSkip = false;
                        }
                    }
                }

                if (shouldSkip) {
                    continue;
                }

                return new IOModel(varValue, current);
            }
            idx--;
        }

        return null;
    }

    public static List<IOModel> getTestInputsFromOutputs(IOModel output, List<IOModel> outputs, File projectRoot,
                                                               String testClass, String testSimpleName,
                                                               InstrumentationResult instrumentationResult) {
        List<TraceNode> executionList = instrumentationResult.getMainTrace().getExecutionList();
        Set<TraceNode> pastOutputNodes = new HashSet<>();
        for (IOModel currentOutput : outputs) {
            TraceNode traceNode = currentOutput.getTraceNode();
            BreakPoint breakPoint = traceNode.getBreakPoint();
            String currentMethodName = breakPoint.getMethodName();
            if (currentMethodName.equals("<init>") || currentMethodName.equals("<clinit>")) {
                continue;
            }
            if (currentOutput.equals(output)) {
               break;
            }
            int[] startAndEndLineNums = getLineNumsForAssertion(traceNode, projectRoot);
            List<TraceNode> assertionTraceNodes = getTraceNodesBetweenLineNums(executionList,
                    traceNode.getDeclaringCompilationUnitName(), startAndEndLineNums);
            pastOutputNodes.addAll(assertionTraceNodes);
        }

        Set<IOModel> inputSet = getInputsFromDataDep(output.getValue(), output.getTraceNode(), instrumentationResult.getMainTrace(), new HashSet<>(),
                pastOutputNodes, testClass, testSimpleName);
        List<IOModel> inputs = new ArrayList<>(inputSet);
        return inputs;
    }

    private static TestIO getTestIOForCrashingTrace(InstrumentationResult instrumentationResult,
                                                    Set<TraceNode> pastOutputNodes, String testClass,
                                                    String testSimpleName) {
        Trace trace = instrumentationResult.getMainTrace();
        List<TraceNode> executionList = trace.getExecutionList();
        int idx = executionList.size() - 1;
        while (idx >= 0) {
            TraceNode current = executionList.get(idx);
            List<VarValue> varValues = new ArrayList<>(current.getWrittenVariables());
            varValues.addAll(current.getReadVariables());
            String programMsg = instrumentationResult.getProgramMsg();
            String stringValOfOutput = programMsg.substring(programMsg.indexOf(';') + 1);
            for (VarValue varValue : varValues) {
                // Array element, index out of bounds, can use varID to check idx.
                // e.g. varID = 1365008457[-1], check if [-1] inside varID
                boolean shouldSkip = true;
                if (!stringValOfOutput.equals(varValue.getStringValue())) {
                    shouldSkip = false;
                } else if (varValue instanceof ReferenceValue) {
                    Variable var = varValue.getVariable();
                    if (var instanceof ArrayElementVar) {
                        if (var.getVarID().contains("[" + stringValOfOutput + "]")) {
                            shouldSkip = false;
                        }
                    }
                }

                if (shouldSkip) {
                    continue;
                }

                Set<IOModel> inputs = getInputsFromDataDep(varValue, current, trace, new HashSet<>(), pastOutputNodes,
                        testClass, testSimpleName);
                IOModel output = new IOModel(varValue, current);
                return new TestIO(new ArrayList<>(inputs), output);
            }
            idx--;
        }
        return null;
    }

    /**
     * Gets inputs using data dependency.
     * Has issues obtaining user-defined inputs when data dependency is broken midway. e.g. when "return 0;" encountered.
     * @param output
     * @param outputNode
     * @param trace
     * @param encounteredVars
     * @return
     */
    private static Set<IOModel> getInputsFromDataDep(VarValue output, TraceNode outputNode, Trace trace,
                                                     Set<Integer> encounteredVars, Set<TraceNode> nodesToIgnore,
                                                     String testClass, String testSimpleName) {
        Set<IOModel> result = new HashSet<>();
        Stack<VarValue> outputsToCheck = new Stack<>();
        Stack<TraceNode> outputNodesToCheck = new Stack<>();
        outputsToCheck.add(output);
        outputNodesToCheck.add(outputNode);
        while (!outputsToCheck.isEmpty()) {
            VarValue currentOutput = outputsToCheck.pop();
            TraceNode currentOutputNode = outputNodesToCheck.pop();

            int varID = hashVarValAndNode(currentOutput, currentOutputNode);
            if (encounteredVars.contains(varID)) {
                continue;
            }
            encounteredVars.add(varID);
            TraceNode dataDependency = trace.findDataDependency(currentOutputNode, currentOutput);

            // For values in top layer that is read
            if (dataDependency == null) {
                // Check current node's (read) var for input.
                if (nodeIsInMethod(currentOutputNode, testClass, testSimpleName) && !nodesToIgnore.contains(currentOutput)) {
                    result.add(new IOModel(currentOutput, currentOutputNode));
                }
                continue;
            }

            // For values in top layer that is only written e.g. 2 in funcCall(2).
            if (dataDependency.getReadVariables().isEmpty()) {
                if (nodeIsInMethod(dataDependency, testClass, testSimpleName) && !nodesToIgnore.contains(dataDependency)) {
                    VarValue outputVal;
                    if (dataDependency.getWrittenVariables().contains(currentOutput)) {
                        // Check using var ID.
                        outputVal = currentOutput;
                    } else {
                        // Check using Alias Var ID (Heap address). For arrays, etc.
                        outputVal = dataDependency.getWrittenVariables().stream().filter(writtenVarVal ->
                                        writtenVarVal.getVariable().getAliasVarID() != null &&
                                                writtenVarVal.getVariable().getAliasVarID().equals(currentOutput.getVariable().getAliasVarID())).
                                findFirst().
                                orElse(null);
                    }

                    if (outputVal == null) {
                        continue;
                    }

                    result.add(new IOModel(outputVal, dataDependency));
                }
                continue;
            }

            // For intermediate dependency, but value is written in it. i.e. still has parent data dependencies, but value
            // was written in this traceNode, so must capture.
            if (dataDependency.getWrittenVariables().contains(currentOutput) &&
                    nodeIsInMethod(dataDependency, testClass, testSimpleName) && !nodesToIgnore.contains(dataDependency)) {
                TraceNode higherDataDependency = trace.findDataDependency(dataDependency, currentOutput);
                if (higherDataDependency == null) {
                    result.add(new IOModel(currentOutput, dataDependency));
                }
            }

            for (VarValue readVarValue : dataDependency.getReadVariables()) {
                outputsToCheck.add(readVarValue);
                outputNodesToCheck.add(dataDependency);
            }
        }
        return result;
    }

    private static boolean nodeIsInMethod(TraceNode node, String className, String methodName) {
        String nodeFullMethodName = node.getDeclaringCompilationUnitName() + "#" + node.getMethodName();
        return nodeFullMethodName.equals(className + "#" + methodName);
    }

    /**
     * Returns the output of a test case (assertion)
     * Caller must implement logic to know when to call this method. i.e. isOutputNode method
     * @param node
     * @return
     */
    private static IOModel getOutput(TraceNode node, TraceNode traceNodeWithAssertion) {
        List<VarValue> writtenVarValues = traceNodeWithAssertion.getWrittenVariables();
        for (VarValue varValue : writtenVarValues) {
            Variable var = varValue.getVariable();
            if (assertionClasses.contains(getVarLocation(var)) && varIsOutput(var)) {
                // Sometimes the assertion spans multiple lines, the output var may be in a diff line from the assertion call.
                // Get step over previous node, until the output var is found.
                // e.g.
                // assertEquals(1,
                // 2);
                // 2nd line is called first before the assertion call at 1st line. Use stepOverPrevious from assertion call node to line 2.
                TraceNode current = node;
                while (current != null) {
                    List<VarValue> readVarVals = current.getReadVariables();
                    for (VarValue readVarVal : readVarVals) {
                        if (readVarVal.getStringValue().equals(varValue.getStringValue())) {
                            return new IOModel(readVarVal, current);
                        }
                    }
                    current = current.getStepOverPrevious();
                }
                // TODO:
                // Should not reach here, take some var val close to assertion's "actual" value so that output is not null.
                // If reference values are correctly stored in writtenVariables, should not reach here.
                // Remove once instrumentator fixed.
                current = node;
                while (current != null) {
                    List<VarValue> readVarVals = current.getReadVariables();
                    for (VarValue readVarVal : readVarVals) {
                        return new IOModel(readVarVal, current);
                    }
                    current = current.getStepOverPrevious();
                }
            }
        }
        return null;
    }

    /**
     * Checks if trace node should be used to obtain test output i.e. whether to call getOutput method
     * @param node
     * @return
     */
    private static boolean isOutputNode(TraceNode node) {
        List<VarValue> writtenVarValues = node.getWrittenVariables();
        for (VarValue varValue : writtenVarValues) {
            Variable var = varValue.getVariable();
            if (assertionClasses.contains(getVarLocation(var)) && varIsOutput(var)) {
                return true;
            }
        }
        return false;
    }

    private static String getVarLocation(Variable var) {
        String varLocation;
        if (var instanceof LocalVar) {
            varLocation = ((LocalVar) var).getLocationClass();
        } else if (var instanceof FieldVar) {
            varLocation = ((FieldVar) var).getDeclaringType();
        } else {
            varLocation = "";
        }
        return varLocation;
    }

    private static boolean varIsOutput(Variable var) {
        String varName = var.getName();
        return varName.equals("actual") || varName.equals("actuals") || varName.equals("condition") || varName.equals("object");
    }

    private static int[] getLineNumsForAssertion(TraceNode assertionTraceNode, File projectRoot) {
        File file = ProjectParser.getFileOfClass(assertionTraceNode.getDeclaringCompilationUnitName(), projectRoot);
        String fileContent;
        try {
            fileContent = Files.readString(file.toPath());
        } catch (IOException e) {
            throw new RuntimeException("Could not read file at " + file.toPath());
        }

        CompilationUnit unit = ProjectParser.parseCompilationUnit(fileContent);
        ASTNodeRetriever<MethodInvocation> methodInvocationASTNodeRetriever = new ASTNodeRetriever<>(MethodInvocation.class);
        unit.accept(methodInvocationASTNodeRetriever);
        int lineNumOfTraceNode = assertionTraceNode.getLineNumber();
        for (MethodInvocation mi : methodInvocationASTNodeRetriever.getNodes()) {
            String methodName = mi.getName().toString();
            if (!methodName.contains("assert")) {
                continue;
            }
            int startLine = unit.getLineNumber(mi.getStartPosition());
            int endLine = unit.getLineNumber(mi.getStartPosition() + mi.getLength() - 1);
            if (lineNumOfTraceNode < startLine || lineNumOfTraceNode > endLine) {
                continue;
            }
            return new int[] {startLine, endLine};
        }
        return null;
    }

    private static List<TraceNode> getTraceNodesBetweenLineNums(List<TraceNode> executionList, String assertionClassName, int[] startAndEndLineNums) {
        List<TraceNode> result = new ArrayList<>();
        for (TraceNode traceNode : executionList) {
            int lineNum = traceNode.getLineNumber();
            if (lineNum >= startAndEndLineNums[0] && lineNum <= startAndEndLineNums[1] && traceNode.getDeclaringCompilationUnitName().equals(assertionClassName)) {
                result.add(traceNode);
            }
        }
        return result;
    }

    private static int hashVarValAndNode(VarValue varValue, TraceNode node) {
        int result = System.identityHashCode(varValue);
        result *= 13;
        result += node.getOrder();
        return result;
    }
}
