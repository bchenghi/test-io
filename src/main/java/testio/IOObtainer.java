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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

public class IOObtainer {
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
                VarValue outputValue = getOutput(traceNode, traceNodeWithAssertion);
                IOModel output = new IOModel(outputValue, traceNode);
                result.add(output);
            }
        }

        // If crashed, obtain the last read/written var
        if (instrumentationResult.hasThrownException()) {
            IOModel outputForCrashingTrace = getOutputForCrashingTrace(instrumentationResult);
            if (outputForCrashingTrace != null) {
                result.add(outputForCrashingTrace);
            }
        }
        return result;
    }

    public static IOModel getOutputForCrashingTrace(InstrumentationResult instrumentationResult) {
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

//    public static List<TestIO> getTestInputOutputs(InstrumentationResult instrumentationResult,
//                                                   InstrumentationResult instrumentationResultWithAssertions,
//                                                   File projectRoot, String testClass, String testSimpleName) {
//        Trace trace = instrumentationResult.getMainTrace();
//        Trace traceWithAssertion = instrumentationResultWithAssertions.getMainTrace();
//        List<TraceNode> executionList = trace.getExecutionList();
//        List<TraceNode> executionListWithAssertions = traceWithAssertion.getExecutionList();
//        List<TestIO> result = new ArrayList<>();
//        Set<TraceNode> pastOutputNodes = new HashSet<>();
//        if (executionList.isEmpty()) {
//            return result;
//        }
//        // Store the outputs and nodesToIgnore. Then rerun the loop, and only obtain input for last one.
//        int lastEncounteredOutputIdx = -1;
//        for (int i = 0; i < executionList.size(); i++) {
//            TraceNode traceNode = executionList.get(i);
//            TraceNode traceNodeWithAssertion = executionListWithAssertions.get(i);
//            BreakPoint breakPoint = traceNode.getBreakPoint();
//            String currentMethodName = breakPoint.getMethodName();
//            if (currentMethodName.equals("<init>") || currentMethodName.equals("<clinit>")) {
//                continue;
//            }
//            boolean shouldCallGetOutput = isOutputNode(traceNodeWithAssertion);
//            if (shouldCallGetOutput) {
//                VarValue outputValue = getOutput(traceNode, traceNodeWithAssertion);
//                IOModel output = new IOModel(outputValue, traceNode);
//                result.add(new TestIO(output));
//                int[] startAndEndLineNums = getLineNumsForAssertion(traceNode, projectRoot);
//                List<TraceNode> assertionTraceNodes = getTraceNodesBetweenLineNums(executionList,
//                        traceNode.getDeclaringCompilationUnitName(), startAndEndLineNums);
//                pastOutputNodes.addAll(assertionTraceNodes);
//                lastEncounteredOutputIdx = i;
//            }
//        }
//
//        // If crashed, obtain the last read/written var
//        if (instrumentationResult.hasThrownException()) {
//            TestIO lastIOForCrashingTrace = getTestIOForCrashingTrace(instrumentationResult, pastOutputNodes,
//                    testClass, testSimpleName);
//            if (lastIOForCrashingTrace != null) {
//                result.add(lastIOForCrashingTrace);
//            }
//        } else {
//            TraceNode traceNode = executionList.get(lastEncounteredOutputIdx);
//            TraceNode traceNodeWithAssertion = executionListWithAssertions.get(lastEncounteredOutputIdx);
//            VarValue outputValue = getOutput(traceNode, traceNodeWithAssertion);
//
//            Set<IOModel> inputSet = getInputsFromDataDep(outputValue, traceNode, trace, new HashSet<>(),
//                    pastOutputNodes, testClass, testSimpleName);
//            IOModel output = new IOModel(outputValue, traceNode);
//            int[] startAndEndLineNums = getLineNumsForAssertion(traceNode, projectRoot);
//            List<TraceNode> assertionTraceNodes = getTraceNodesBetweenLineNums(executionList,
//                    traceNode.getDeclaringCompilationUnitName(), startAndEndLineNums);
//            pastOutputNodes.addAll(assertionTraceNodes);
//
//            List<IOModel> inputs = new ArrayList<>(inputSet);
//            TestIO testIO = new TestIO(inputs, output);
//            result.add(testIO);
//        }
//        return result;
//    }

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
        int varID = System.identityHashCode(output);
        if (encounteredVars.contains(varID)) {
            return result;
        }
        encounteredVars.add(varID);
        TraceNode dataDependency = trace.findDataDependency(outputNode, output);
        if (dataDependency == null || dataDependency.getReadVariables().isEmpty()) {
            if (!nodeIsInMethod(outputNode, testClass, testSimpleName)) {
                return result;
            }
            if (nodesToIgnore.contains(output)) {
                return result;
            }
            result.add(new IOModel(output, outputNode));
            return result;
        }
        if (dataDependency.getWrittenVariables().contains(output) &&
                nodeIsInMethod(dataDependency, testClass, testSimpleName) && !nodesToIgnore.contains(dataDependency)) {
            result.add(new IOModel(output, outputNode));
        }
        for (VarValue readVarValue : dataDependency.getReadVariables()) {
            result.addAll(getInputsFromDataDep(readVarValue, dataDependency, trace, encounteredVars, nodesToIgnore,
                    testClass, testSimpleName));
        }
        return result;
    }

    private static boolean nodeIsInMethod(TraceNode node, String className, String methodName) {
        String nodeFullMethodName = node.getDeclaringCompilationUnitName() + "#" + node.getMethodName();
        return nodeFullMethodName.equals(className + "#" + methodName);
    }

    private static List<VarValue> getInputsFromTrace(List<TraceNode> executionList, TraceNode assertionNode, Map<String, List<VarValue>> referenceVarToValMap, Map<String, VarValue> primitiveVarToValMap, File projectRoot) {
        int[] startAndEndLineNums = getLineNumsForAssertion(assertionNode, projectRoot);
        List<TraceNode> assertionTraceNodes = getTraceNodesBetweenLineNums(executionList, assertionNode.getDeclaringCompilationUnitName(), startAndEndLineNums);
        List<VarValue> inputs = new ArrayList<>();
        Set<String> alreadyAddedInputs = new HashSet<>();
        for (TraceNode assertionTraceNode : assertionTraceNodes) {
           List<VarValue> varVals = new ArrayList<>();
           varVals.addAll(assertionTraceNode.getReadVariables());
           varVals.addAll(assertionTraceNode.getWrittenVariables());
           for (VarValue readVarVal : varVals) {
               Variable var = readVarVal.getVariable();
               if (readVarVal instanceof ReferenceValue) {
                   List<VarValue> newInputs = referenceVarToValMap.get(formKeyForInputMap(var));
                   if (newInputs == null) {
                       continue;
                   }
                   for (VarValue newInput : newInputs) {
                       String varID = newInput.getVarID();
                       if (alreadyAddedInputs.contains(varID)) {
                           continue;
                       }
                       alreadyAddedInputs.add(varID);
                       inputs.add(newInput);
                   }
               } else {
                   VarValue newInput = primitiveVarToValMap.get(formKeyForInputMap(var));
                   if (newInput == null) {
                       continue;
                   }
                   String varID = newInput.getVarID();
                   if (alreadyAddedInputs.contains(varID)) {
                       continue;
                   }
                   alreadyAddedInputs.add(varID);
                   inputs.add(newInput);
               }
           }
       }
       return inputs;
    }

    /**
     * Returns the output of a test case (assertion)
     * Caller must implement logic to know when to call this method. i.e. isOutputNode method
     * @param node
     * @return
     */
    private static VarValue getOutput(TraceNode node, TraceNode traceNodeWithAssertion) {
        List<VarValue> writtenVarValues = traceNodeWithAssertion.getWrittenVariables();
        for (VarValue varValue : writtenVarValues) {
            Variable var = varValue.getVariable();
            if (getVarLocation(var).equals("org.junit.Assert") && varIsOutput(var)) {
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
                            return readVarVal;
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
                        return readVarVal;
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
            if (getVarLocation(var).equals("org.junit.Assert") && varIsOutput(var)) {
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

    private static void setVarVals(Map<String, VarValue> primitiveVarToVal, Map<String, List<VarValue>> referenceVarToVal, TraceNode traceNode) {
        List<VarValue> varVals = new ArrayList<>();
        varVals.addAll(traceNode.getReadVariables());
        varVals.addAll(traceNode.getWrittenVariables());
        for (VarValue writtenVarVal : varVals) {
            Variable writtenVariable = writtenVarVal.getVariable();
            String key = formKeyForInputMap(writtenVariable);
            // Ignore objects e.g. Object x = constructor(); x is not user defined input
            if (writtenVarVal instanceof ReferenceValue) {
                if (shouldGetInputsForRefVal(traceNode)) {
                    List<VarValue> initializersForRefVal = new ArrayList<>();
                    if (referenceVarToVal.containsKey(key)) {
                        initializersForRefVal = referenceVarToVal.get(key);
                    }
                    initializersForRefVal.addAll(getInitializersForReferenceVal(traceNode, (ReferenceValue) writtenVarVal));
                    referenceVarToVal.put(key, initializersForRefVal);
                    continue;
                }
            }

            primitiveVarToVal.put(key, writtenVarVal);
        }
    }

    private static String formKeyForInputMap(Variable var) {
        // Location of var has to be appended as the location is not used in equals method for vars.
        // i.e. int x = 1; and func(int x);
        // Both x will be treated the same without location when they should be different inputs
        String varLocation;
        if (var instanceof LocalVar) {
            varLocation = ((LocalVar) var).getLocationClass();
        } else if (var instanceof FieldVar) {
            varLocation = ((FieldVar) var).getDeclaringType();
        } else {
            varLocation = "";
        }
        return var.toString() + varLocation;
    }

    private static List<VarValue> getInitializersForReferenceVal(TraceNode referenceValNode, ReferenceValue refValue) {
        TraceNode stepOverPrevious = referenceValNode.getStepOverPrevious();
        List<VarValue> result = new ArrayList<>();
        List<VarValue> fieldsInReferenceVal = refValue.getChildren();
        // The written vals could be arguments passed into the ref, however, not all are stored in the reference obj.
        // Use getChildren to obtain the fields in the obj, and check if the written val was written to some field in the obj.
        // If the obj stores the val, then add to the map.
        for (VarValue fieldVal : fieldsInReferenceVal) {
            for (VarValue writtenVal : stepOverPrevious.getWrittenVariables()) {
                if (fieldVal.getStringValue().equals(writtenVal.getStringValue())) {
                    result.add(writtenVal);
                }
            }
        }
        return result;
    }

    private static boolean shouldGetInputsForRefVal(TraceNode referenceValNode) {
        TraceNode stepOverPrevious = referenceValNode.getStepOverPrevious();
        if (stepOverPrevious == null) {
            return false;
        }
        if (!referenceValNode.getClassCanonicalName().equals(stepOverPrevious.getClassCanonicalName())) {
            return false;
        }
        if (referenceValNode.getLineNumber() != stepOverPrevious.getLineNumber()) {
            return false;
        }
        return true;
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
}