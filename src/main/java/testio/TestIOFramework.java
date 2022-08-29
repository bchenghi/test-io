package testio;

import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import testio.model.IOModel;
import testio.model.TestIO;
import tracecollection.model.InstrumentationResult;
import tracediff.model.PairList;
import tracediff.model.TraceNodePair;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestIOFramework {

    /**
     * Obtain IO for buggy trace
     * It requires the original trace to compare whether any inputs are missing due to bugs.
     * @param originalResult
     * @param originalResultWithAssertions
     * @param mutatedResult
     * @param mutatedResultWithAssertions
     * @param projectRoot
     * @param mutatedProjectRoot
     * @param pairList
     * @return
     */
    public TestIO getBuggyTestIOs(InstrumentationResult originalResult, InstrumentationResult originalResultWithAssertions,
                                   InstrumentationResult mutatedResult, InstrumentationResult mutatedResultWithAssertions,
                                   File projectRoot, File mutatedProjectRoot, PairList pairList, String testClass,
                                        String testSimpleName) {
        List<IOModel> outputsFromMutatedTrace = IOObtainer.getTestOutputs(mutatedResult, mutatedResultWithAssertions);
        List<IOModel> outputsFromOriginalTrace = IOObtainer.getTestOutputs(originalResult,
                originalResultWithAssertions);
        List<IOModel[]> outputPairs = formIOModelPairs(outputsFromMutatedTrace, outputsFromOriginalTrace, pairList);
        if (outputPairs.isEmpty()) {
            if (!outputsFromMutatedTrace.isEmpty()) {
                IOModel output = outputsFromMutatedTrace.get(outputsFromMutatedTrace.size() - 1);
                List<IOModel> inputsFromMutatedTrace = IOObtainer.getTestInputsFromOutputs(
                        output,
                        outputsFromMutatedTrace, mutatedProjectRoot, testClass, testSimpleName, mutatedResult);
                return new TestIO(inputsFromMutatedTrace, output);
            }
            return null;
        }
        IOModel[] failingOutputs = outputPairs.get(outputPairs.size() - 1);
        List<IOModel> inputsFromMutatedTrace = IOObtainer.getTestInputsFromOutputs(failingOutputs[0],
                outputsFromMutatedTrace, mutatedProjectRoot, testClass, testSimpleName, mutatedResult);
        List<IOModel> inputsFromNormalTrace = IOObtainer.getTestInputsFromOutputs(failingOutputs[1],
                outputsFromOriginalTrace, projectRoot, testClass, testSimpleName, originalResult);
        List<IOModel> missingIOModelsFromBuggyIOs = getMissingIOModels(inputsFromMutatedTrace, inputsFromNormalTrace);
        List<IOModel> createdIOModelsForBuggyIO = createIOModels(missingIOModelsFromBuggyIOs, pairList);
        inputsFromMutatedTrace.addAll(createdIOModelsForBuggyIO);
        return new TestIO(inputsFromMutatedTrace, failingOutputs[0]);
    }

    private Map<TraceNode, TestIO> convertToMapOfOutputNodeToIO(List<TestIO> testIOList) {
        Map<TraceNode, TestIO> result = new HashMap<>();
        for (TestIO testIO : testIOList) {
            TraceNode outputNode = testIO.getOutputNode();
            result.put(outputNode, testIO);
        }
        return result;
    }


    private Map<TraceNode, IOModel> convertToMapOfIOModelToIO(List<IOModel> ioModelList) {
        Map<TraceNode, IOModel> result = new HashMap<>();
        for (IOModel ioModel : ioModelList) {
            TraceNode outputNode = ioModel.getTraceNode();
            result.put(outputNode, ioModel);
        }
        return result;
    }
    /**
     * For a given output, obtain the corresponding output in the other list of IOs.
     * @param buggyIOs
     * @param normalIOs
     * @param pairList
     * @return
     */
    private List<TestIO[]> formTestIOPairs(List<TestIO> buggyIOs, List<TestIO> normalIOs, PairList pairList) {
        // Use pair list to match the test ios.
        // For buggy io that has no match in output, e.g. a crash, use order for now...
        // 1st IO in buggy match to 1st IO in normal.
        List<TestIO[]> result = new ArrayList<>();
        Map<TraceNode, TestIO> mapOfNodeToIOFromNormalTrace = convertToMapOfOutputNodeToIO(normalIOs);
        for (TestIO buggyTestIO : buggyIOs) {
            TraceNode outputNode = buggyTestIO.getOutputNode();
            TraceNodePair traceNodePair = pairList.findByBeforeNode(outputNode);
            TraceNode correspondingNormalOutputNode = traceNodePair.getAfterNode();
            TestIO correspondingNormalTestIO = mapOfNodeToIOFromNormalTrace.get(correspondingNormalOutputNode);
            TestIO[] testIOPair = new TestIO[] {buggyTestIO, correspondingNormalTestIO};
            result.add(testIOPair);
        }
        return result;
    }


    /**
     * For a given input/output, obtain the corresponding input/output in the other list of inputs/outputs.
     * @param buggyIOs
     * @param normalIOs
     * @param pairList
     * @return
     */
    private List<IOModel[]> formIOModelPairs(List<IOModel> buggyIOs, List<IOModel> normalIOs, PairList pairList) {
        // Use pair list to match the test ios.
        // For buggy io that has no match in output, e.g. a crash, use order for now...
        // 1st IO in buggy match to 1st IO in normal.
        List<IOModel[]> result = new ArrayList<>();
        Map<TraceNode, IOModel> mapOfIOModelToIOFromNormalTrace = convertToMapOfIOModelToIO(normalIOs);
        for (IOModel buggyTestIO : buggyIOs) {
            TraceNode outputNode = buggyTestIO.getTraceNode();
            TraceNodePair traceNodePair = pairList.findByBeforeNode(outputNode);
            if (traceNodePair == null) {
                continue;
            }
            TraceNode correspondingNormalOutputNode = traceNodePair.getAfterNode();
            IOModel correspondingNormalIOModel = mapOfIOModelToIOFromNormalTrace.get(correspondingNormalOutputNode);
            if (correspondingNormalIOModel == null) {
                continue;
            }
            IOModel[] ioModelPair = new IOModel[] {buggyTestIO, correspondingNormalIOModel};
            result.add(ioModelPair);
        }
        return result;
    }
    /**
     * Obtain the missing IOModels in the buggyIO, due to breaks in data dependencies.
     * @param buggyIOs
     * @param normalIOs
     * @return
     */
    private List<IOModel> getMissingIOModels(List<IOModel> buggyIOs, List<IOModel> normalIOs) {
        List<IOModel> result = new ArrayList<>();
        Map<VarValue, IOModel> valueToIOModelMap = new HashMap<>();
        Set<String> buggyStringValues = new HashSet<>();
        for (IOModel buggyIO : buggyIOs) {
            valueToIOModelMap.put(buggyIO.getValue(), buggyIO);
            buggyStringValues.add(buggyIO.getValue().getStringValue());
        }
        for (IOModel normalIO : normalIOs) {
            // Using VarVal equality can identify the same var vals. However, not all.
            // Thus, string value is also used to check if a VarVal exists in the other IO.
            boolean normalIOVarValInBuggyIO = valueToIOModelMap.containsKey(normalIO.getValue());
            boolean normalIOStringValInBuggyIO = buggyStringValues.contains(normalIO.getValue().getStringValue());
            if (!normalIOStringValInBuggyIO && !normalIOVarValInBuggyIO) {
                result.add(normalIO);
            }
        }
        return result;
    }

    private List<IOModel> createIOModels(List<IOModel> ioModelsFromNormalTrace, PairList pairList) {
        List<IOModel> result = new ArrayList<>();
        for (IOModel ioModel : ioModelsFromNormalTrace) {
            TraceNodePair traceNodePair = pairList.findByAfterNode(ioModel.getTraceNode());
            TraceNode correspondingMutatedTraceNode = traceNodePair.getBeforeNode();
            IOModel newIOModel = findCorrespondingValueInTraceNode(ioModel, correspondingMutatedTraceNode);
            if (newIOModel == null) {
                continue;
            }
            result.add(newIOModel);
        }
        return result;
    }

    private IOModel findCorrespondingValueInTraceNode(IOModel ioToFind, TraceNode nodeToSearchIn) {
        boolean valueIsInReadVars = false;
        boolean valueIsInWrittenVars = false;
        TraceNode traceNode = ioToFind.getTraceNode();
        VarValue val = ioToFind.getValue();
        List<VarValue> readVarVals = traceNode.getReadVariables();
        for (VarValue readVarVal : readVarVals) {
            if (readVarVal.equals(val)) {
                valueIsInReadVars = true;
            }
        }
        List<VarValue> writtenVarVals = traceNode.getWrittenVariables();
        for (VarValue writtenVarVal : writtenVarVals) {
            if (writtenVarVal.equals(val)) {
                valueIsInWrittenVars = true;
            }
        }
        VarValue varValueFound = null;
        if (valueIsInReadVars) {
            varValueFound = searchForVarValue(val, nodeToSearchIn.getReadVariables());
        } else if (valueIsInWrittenVars) {
            varValueFound = searchForVarValue(val, nodeToSearchIn.getWrittenVariables());
        }
        if (varValueFound == null) {
            return null;
        }
        return new IOModel(varValueFound, nodeToSearchIn);
    }

    private VarValue searchForVarValue(VarValue varValueForReference, List<VarValue> varValuesToSearchIn) {
        for (VarValue varValue : varValuesToSearchIn) {
            if (varValue.getVarID().equals(varValueForReference.getVarID())) {
                return varValue;
            }
        }
        return null;
    }

}
