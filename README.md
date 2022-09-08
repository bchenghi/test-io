# test-io
Obtain inputs (user-defined values in test cases that will affect the `actual` value in an assertion) and outputs (`actual` variable in assertions) of Junit4 test cases, with the help of trace collection library used in Microbat

## Setup
The project uses trace-model and trace-diff as submodules.
Run setup.bat script found in the root directory.
It should generate a jar for usage as an external library in the `./target` directory.

## Usage
The method `testio.TestIOFramework#getBuggyTestIOs` should be used.<br/>
Your own microbat runner is required to generate traces.<br/>
4 InstrumentationResults (Wrapper for trace, and other results from microbat trace collection) are required. The non-buggy trace, buggy trace, non-buggy + buggy traces with `org.junit.Assert` included.<br/>
The non-buggy and buggy traces should be passed to `trace-diff` project's `tracediff#getTraceAlignment` method to generate a `PairList` to pass to `test-io` project.<br/>
Several other arguments are also required.
In total, the required arguments are:
- The 4 InstrumentationResults
- PairList
- Test case class and method name
- Project roots to non-buggy and buggy traces

## How it works (Developers)
It first obtains the outputs to the test case.
It utilises the trace with `org.junit.Assert` included, by checking if the trace node contains variable belonging to that class, and it takes the variable with the name indicating it is the output. (e.g. the variable name `actual` for `assertEquals` method)
This is done for both non-buggy and buggy traces.<br/>
Using the PairList, the TraceNodes of the outputs from the 2 traces are compared, and paired.

It then uses `microbat.model.Trace#findDataDependency` method on the last outputs of the 2 traces repeatedly to find VarValues that are the inputs to the provided output.<br/>
Only takes variables read/written that are in the test method.<br/>
It checks if the data dependency is null, and adds to the inputs list if the read/written VarValue is in the test method.<br/>
Some values are written, but still has data dependencies. For such cases, it checks whether the variable is only written, and not read by the previous node. <br/>

The inputs are compared, and any missing input in the buggy trace from the non-buggy trace is added. This is because certain bugs can cause breaks in data dependencies, and some inputs cannot be captured just by using the buggy trace.
