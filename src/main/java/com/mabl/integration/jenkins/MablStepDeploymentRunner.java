package com.mabl.integration.jenkins;

import com.google.common.collect.ImmutableSet;
import com.mabl.integration.jenkins.domain.CreateDeploymentResult;
import com.mabl.integration.jenkins.domain.ExecutionResult;
import com.mabl.integration.jenkins.test.output.Failure;
import com.mabl.integration.jenkins.test.output.Properties;
import com.mabl.integration.jenkins.test.output.Property;
import com.mabl.integration.jenkins.test.output.TestCase;
import com.mabl.integration.jenkins.test.output.TestSuite;
import com.mabl.integration.jenkins.test.output.TestSuites;
import hudson.FilePath;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.IOException;
import java.io.PrintStream;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static com.mabl.integration.jenkins.MablStepConstants.PLUGIN_NAME;
import static com.mabl.integration.jenkins.MablStepConstants.PLUGIN_VERSION;

/**
 * mabl runner to launch all plans for a given
 * environment and application
 * <p>
 * NOTE: Runner will attempt to run until all tests are completion.
 * It is the responsibility of the Step to terminate at max time.
 */
public class MablStepDeploymentRunner implements Callable<Boolean> {

    private static final Set<String> COMPLETE_STATUSES = ImmutableSet.of(
            "succeeded",
            "failed",
            "cancelled",
            "completed",
            "terminated"
    );

    private final MablRestApiClient client;
    private final PrintStream outputStream;
    private final long pollingIntervalMilliseconds;

    private final String environmentId;
    private final String applicationId;
    private final boolean continueOnPlanFailure;
    private final boolean continueOnMablError;
    private final FilePath buildPath;

    @SuppressWarnings("WeakerAccess") // required public for DataBound
    @DataBoundConstructor
    public MablStepDeploymentRunner(
            final MablRestApiClient client,
            final PrintStream outputStream,
            final long pollingIntervalMilliseconds,
            final String environmentId,
            final String applicationId,
            final boolean continueOnPlanFailure,
            final boolean continueOnMablError,
            final FilePath buildPath

    ) {
        this.outputStream = outputStream;
        this.client = client;
        this.pollingIntervalMilliseconds = pollingIntervalMilliseconds;
        this.environmentId = environmentId;
        this.applicationId = applicationId;
        this.continueOnPlanFailure = continueOnPlanFailure;
        this.continueOnMablError = continueOnMablError;
        this.buildPath = buildPath;
    }

    @Override
    public Boolean call() {
        try {
            outputStream.printf("%nmabl Jenkins plugin v%s running...%n", PLUGIN_VERSION);
            execute();
            return true;

        } catch (MablSystemError error) {
            printException(error);
            return continueOnMablError;

        } catch (MablPlanExecutionFailure failure) {
            printException(failure);
            return continueOnPlanFailure;

        } catch (Exception e) {
            outputStream.printf("Unexpected %s exception%n", PLUGIN_NAME);
            e.printStackTrace(outputStream);
            return continueOnMablError;
        }
        finally {
            outputStream.print("mabl journey execution step complete.\n\n");
        }
    }

    private void execute() throws MablSystemError, MablPlanExecutionFailure {
        // TODO descriptive error messages on 401/403
        // TODO retry on 50x errors (proxy, redeploy)
        outputStream.printf("mabl is creating a deployment event:%n  environment_id: %s %n  application_id: %s%n",
                environmentId,
                applicationId
        );

        try {
            final CreateDeploymentResult deployment = client.createDeploymentEvent(environmentId, applicationId);
            outputStream.printf("Deployment event was created with id [%s] in mabl.%n", deployment.id);

            try {

                // Poll until we are successful or failed - note execution service is responsible for timeout
                ExecutionResult executionResult;
                do {
                    Thread.sleep(pollingIntervalMilliseconds);
                    executionResult = client.getExecutionResults(deployment.id);

                    if (executionResult == null) {
                        // No such id - this shouldn't happen
                        throw new MablSystemError(String.format("Oh snap! No deployment event found for id [%s] in mabl.", deployment.id));
                    }

                    printAllJourneyExecutionStatuses(executionResult);

                } while (!allPlansComplete(executionResult));

                printFinalStatuses(executionResult);

                if (!allPlansSuccess(executionResult)) {
                    throw new MablPlanExecutionFailure("One or more plans were unsuccessful running in mabl.");
                }

            } catch (InterruptedException e) {
                // TODO better error handling/logging
                Thread.currentThread().interrupt();
                e.printStackTrace();
            }

        } catch (IOException e) {
            throw new MablSystemError("Oh no!. There was an API error trying to run journeys in mabl.", e);

        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private boolean allPlansComplete(final ExecutionResult result) {

        boolean isComplete = true;

        for (ExecutionResult.ExecutionSummary summary : result.executions) {
            isComplete &= COMPLETE_STATUSES.contains(summary.status.toLowerCase());
        }
        return isComplete;
    }

    private boolean allPlansSuccess(final ExecutionResult result) {

        boolean isSuccess = true;

        for (ExecutionResult.ExecutionSummary summary : result.executions) {
            isSuccess &= summary.success;
        }
        return isSuccess;
    }

    private void printFinalStatuses(final ExecutionResult result) throws MablSystemError {
        ArrayList<TestSuite> suites = new ArrayList<TestSuite>();

        outputStream.println("The final Plan states in mabl:");
        for (ExecutionResult.ExecutionSummary summary : result.executions) {
            TestSuite testSuite = getTestSuite(summary);
            final String successState = summary.success ? "SUCCESSFUL" : "FAILED";
            outputStream.printf("  Plan [%s] is %s in state [%s]%n", safePlanName(summary), successState, summary.status);

            for (ExecutionResult.JourneyExecutionResult journeyResult : summary.journeyExecutions) {

                TestCase testCase = new TestCase(
                        safePlanName(summary),
                        safeJourneyName(summary, journeyResult.id),
                        getDuration(summary)
                );

                testSuite.addToTestCases(testCase).incrementTests();

                if (!journeyResult.success) {
                    Failure failure = new Failure(journeyResult.status, journeyResult.statusCause);
                    testCase.setFailure(failure);
                    testSuite.incrementFailures();
                }

            }

            suites.add(testSuite);
        }

        outputTestSuiteXml(new TestSuites(suites));
    }

    private void printAllJourneyExecutionStatuses(final ExecutionResult result) {

        outputStream.println("Running mabl journey(s) status update:");
        for (ExecutionResult.ExecutionSummary summary : result.executions) {
            outputStream.printf("  Plan [%s] is [%s]%n", safePlanName(summary), summary.status);
            for (ExecutionResult.JourneyExecutionResult journeyResult : summary.journeyExecutions) {
                outputStream.printf("  Journey [%s] is [%s]%n", safeJourneyName(summary, journeyResult.id), journeyResult.status);
            }
        }
    }

    private void outputTestSuiteXml(TestSuites testSuites) throws MablSystemError {
        try {
            JAXBContext context = JAXBContext.newInstance(TestSuites.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.marshal(testSuites, buildPath.write());
        } catch (JAXBException e) {
            throw new MablSystemError("There was an error trying to output test results in mabl.", e);
        } catch (IOException e) {
            throw new MablSystemError("There was an error trying to write test results in mabl.", e);
        } catch (InterruptedException e) {
            throw new MablSystemError("There was an interruption trying to write test results in mabl.", e);
        }
    }

    private TestSuite getTestSuite(final ExecutionResult.ExecutionSummary summary) {
        Date startDate = new Date(summary.startTime);
        Format format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        String timestamp = format.format(startDate);
        ArrayList<Property> props = new ArrayList<Property>();
        props.add(new Property("environmentId", this.environmentId));
        props.add(new Property("applicationId", this.applicationId));

        return new TestSuite(safePlanName(summary), getDuration(summary), timestamp, new Properties(props));
    }

    private long getDuration(ExecutionResult.ExecutionSummary summary) {
        return TimeUnit.SECONDS.convert( (summary.stopTime - summary.startTime), TimeUnit.MILLISECONDS);
    }

    private void printException(final Exception exception) {
        outputStream.print(exception.getMessage());

        if (exception.getCause() != null) {
            exception.getCause().printStackTrace(outputStream);
        }
    }

    private String safePlanName(final ExecutionResult.ExecutionSummary summary) {
        // Defensive treatment of possibly malformed future payloads
        return summary.plan != null &&
                summary.plan.name != null &&
                !summary.plan.name.isEmpty()
                    ? summary.plan.name :
                    "<Unnamed Plan>";
    }

    private String safeJourneyName(
            final ExecutionResult.ExecutionSummary summary,
            final String journeyId
    ) {
        // Defensive treatment of possibly malformed future payloads
        String journeyName = "<Unnamed Journey>";
        for(ExecutionResult.JourneySummary journeySummary: summary.journeys) {
            if(journeySummary.id.equals(journeyId) && !journeySummary.name.isEmpty()) {
                journeyName = journeySummary.name;
                break;
            }
        }

        return journeyName;
    }
}