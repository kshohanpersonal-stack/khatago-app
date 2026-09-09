Run: https://github.com/kshohanpersonal-stack/khatago-app/actions/runs/34295392126
```
--- probe
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:testDebugUnitTest'.
> There were failing tests. See the report at: file:///home/runner/work/khatago-app/khatago-app/app/build/reports/tests/testDebugUnitTest/index.html

* Try:
> Run with --scan to get full insights.

* Exception is:
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:testDebugUnitTest'.
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:130)
	at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:293)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:128)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:116)
	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)

===== tests.log tail =====
	at org.gradle.internal.execution.steps.AbstractSkipEmptyWorkStep.execute(AbstractSkipEmptyWorkStep.java:36)
	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsStartedStep.execute(MarkSnapshottingInputsStartedStep.java:38)
	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.execute(LoadPreviousExecutionStateStep.java:36)
	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.execute(LoadPreviousExecutionStateStep.java:23)
	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.execute(HandleStaleOutputsStep.java:75)
	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.execute(HandleStaleOutputsStep.java:41)
	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.lambda$execute$0(AssignMutableWorkspaceStep.java:35)
	at org.gradle.api.internal.tasks.execution.TaskExecution$4.withWorkspace(TaskExecution.java:289)
	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.execute(AssignMutableWorkspaceStep.java:31)
	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.execute(AssignMutableWorkspaceStep.java:22)
	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:40)
	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:23)
	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.lambda$execute$2(ExecuteWorkBuildOperationFiringStep.java:67)
	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:67)
	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:39)
	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:46)
	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:34)
	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:48)
	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:35)
	at org.gradle.internal.execution.impl.DefaultExecutionEngine$1.execute(DefaultExecutionEngine.java:61)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:127)
	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:116)
	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)
	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:209)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:204)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:166)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)


Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.9/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD FAILED in 30s
33 actionable tasks: 13 executed, 20 from cache
Configuration cache entry stored.
```
com.khatago.finance.core.CsvExportTest.newlines inside a note survive as a quoted field
   org.junit.ComparisonFailure: expected:<line one[ line two]> but was:<line one[]>
   org.junit.ComparisonFailure: expected:<line one[
   line two]> but was:<line one[]>
   at org.junit.Assert.assertEquals(Assert.java:117)
   at org.junit.Assert.assertEquals(Assert.java:146)
   at com.khatago.finance.core.CsvExportTest.newlines inside a note survive as a quoted field(CsvExportTest.kt:40)

com.khatago.finance.core.money.MoneyCoreTest.multiplication overflow is refused
   java.lang.IllegalArgumentException: Multiplication overflow: 500000000000000 x 3
   java.lang.IllegalArgumentException: Multiplication overflow: 500000000000000 x 3
   at com.khatago.finance.core.money.MoneyMinor.times(MoneyMinor.kt:44)
   at com.khatago.finance.core.money.MoneyCoreTest.multiplication overflow is refused(MoneyCoreTest.kt:132)
   at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
   at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)

com.khatago.finance.core.money.MoneyCoreTest.currency symbol and spaces are tolerated because users type them
   java.lang.AssertionError: expected:<1000> but was:<-1>
   java.lang.AssertionError: expected:<1000> but was:<-1>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

com.khatago.finance.core.money.MoneyCoreTest.overpayment protection compares against the remaining limit exactly
   java.lang.AssertionError
   java.lang.AssertionError
   at org.junit.Assert.fail(Assert.java:87)
   at org.junit.Assert.assertTrue(Assert.java:42)
   at org.junit.Assert.assertFalse(Assert.java:65)
   at org.junit.Assert.assertFalse(Assert.java:75)

com.khatago.finance.data.PaymentEngineTest.the derived total always equals the sum of the payment rows
   java.lang.AssertionError: expected:<5000> but was:<3000>
   java.lang.AssertionError: expected:<5000> but was:<3000>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

com.khatago.finance.data.PaymentEngineTest.deleting a payment repairs the installment line it was applied to
   java.lang.AssertionError: only the down payment is left on the loan's own ledger expected:<200000> but was:<0>
   java.lang.AssertionError: only the down payment is left on the loan's own ledger expected:<200000> but was:<0>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at com.khatago.finance.data.PaymentEngineTest$deleting a payment repairs the installment line it was applied to$1.invokeSuspend(PaymentEngineTest.kt:290)

com.khatago.finance.data.PaymentEngineTest.a loan down payment counts as paid once on both sides of the balance
   java.lang.AssertionError: a settled loan must not be reported as outstanding expected:<0> but was:<200000>
   java.lang.AssertionError: a settled loan must not be reported as outstanding expected:<0> but was:<200000>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at com.khatago.finance.data.PaymentEngineTest$a loan down payment counts as paid once on both sides of the balance$1.invokeSuspend(PaymentEngineTest.kt:336)

com.khatago.finance.data.PaymentEngineTest.an EMI down payment leaves the financed remainder payable and nothing more
   java.lang.AssertionError: expected:<4000000> but was:<3600000>
   java.lang.AssertionError: expected:<4000000> but was:<3600000>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

com.khatago.finance.data.PaymentEngineTest.editing an EMI plan keeps the payments and repairs the schedule lines
   java.lang.AssertionError: expected:<300000> but was:<100000>
   java.lang.AssertionError: expected:<300000> but was:<100000>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

com.khatago.finance.data.PaymentEngineTest.the three EMI derivations agree while the plan is still open
   java.lang.AssertionError: expected:<54000000> but was:<48000000>
   java.lang.AssertionError: expected:<54000000> but was:<48000000>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

com.khatago.finance.domain.calc.FinancialMathTest.overpayment is rejected with the ExceedsRemaining kind
   java.lang.AssertionError
   java.lang.AssertionError
   at org.junit.Assert.fail(Assert.java:87)
   at org.junit.Assert.assertTrue(Assert.java:42)
   at org.junit.Assert.assertTrue(Assert.java:53)
   at com.khatago.finance.domain.calc.FinancialMathTest.overpayment is rejected with the ExceedsRemaining kind(FinancialMathTest.kt:94)

com.khatago.finance.domain.calc.FinancialMathTest.paid amounts on lines count before the obligation-level pool
   java.lang.AssertionError: expected:<400> but was:<200>
   java.lang.AssertionError: expected:<400> but was:<200>
   at org.junit.Assert.fail(Assert.java:89)
   at org.junit.Assert.failNotEquals(Assert.java:835)
   at org.junit.Assert.assertEquals(Assert.java:647)
   at org.junit.Assert.assertEquals(Assert.java:633)

-- app/build/test-results/testDebugUnitTest: 12 failing of 93 tests in 5 class(es)
== 12 failing of 93 tests across 5 result file(s)
